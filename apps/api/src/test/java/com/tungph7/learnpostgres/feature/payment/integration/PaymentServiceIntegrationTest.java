package com.tungph7.learnpostgres.feature.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.feature.order.service.OrderService;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

/**
 * {@link PaymentService} trên Postgres thật — những thứ unit test với mock không chứng minh được:
 *
 * <ul>
 *   <li>Idempotency đi qua {@code ON CONFLICT} thật: retry cùng key, key dùng lại cho request khác,
 *       order đã có payment chưa FAILED ({@code uq_payments_order_non_failed}).
 *   <li>Webhook tới khi order đã hết hạn: capture phải COMMIT được. Gọi {@code markPaid} rồi bắt
 *       lỗi (bản kế hoạch) thì transaction bị đánh dấu rollback-only và capture mất theo.
 *   <li>READ COMMITTED: 2 webhook / 2 request cùng key chạy chồng thật (cổng khóa {@link
 *       LockTestSupport}) → một bên ghi, bên kia ack / EXISTING. REPEATABLE READ thì bên sau nhận
 *       40001.
 *   <li>Trigger V15 chặn tổng refund; {@code GlobalExceptionHandler} dịch lỗi DB thật — message
 *       nguyên văn của Postgres, kể cả các dòng Detail / Where.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi lời gọi service là một transaction riêng,
 * commit thật. Dữ liệu tự dọn theo tiền tố — {@link #cleanup()}.
 */
@SpringBootTest
@Tag("integration")
class PaymentServiceIntegrationTest extends AbstractContainerBaseTest {

  private static final String ACTOR = "payment-it";
  private static final String GATEWAY_OK = "{\"status\": \"succeeded\"}";

  @Autowired PaymentService paymentService;
  @Autowired OrderService orderService;
  @Autowired GlobalExceptionHandler exceptionHandler;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;

  private PaymentTestData data;

  @BeforeEach
  void setUp() {
    data = new PaymentTestData(jdbc, tx, "payment-it-");
    cleanup();
  }

  /**
   * BẮT BUỘC dọn: payment / refund giữ FK RESTRICT tới order. Chỉ {@code @AfterEach} — {@link
   * #setUp()} đã tự gọi ở dòng đầu (hai method {@code @BeforeEach} không có thứ tự đảm bảo).
   */
  @AfterEach
  void cleanup() {
    data.cleanup();
  }

  private PaymentIntentResult create(UUID orderId, String key) {
    return paymentService.createPaymentIntent(orderId, PaymentGateway.STRIPE, key, ACTOR);
  }

  /** Payment 300 đã capture với mã {@code reference}, order đã PAID. */
  private UUID capturedPayment(String key, String reference) {
    UUID paymentId = create(data.pendingOrder("300.00"), key).paymentId();
    paymentService.confirmPayment(paymentId, reference, GATEWAY_OK);
    return paymentId;
  }

  private static void assertError(Throwable thrown, ErrorCode code) {
    assertThat(thrown).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  /** Cho lỗi DB thật đi qua handler thật — đúng đường của một request HTTP. */
  private ResponseEntity<ProblemDetail> handle(Throwable thrown) {
    assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
    return exceptionHandler.handleDataIntegrity(
        (DataIntegrityViolationException) thrown, new MockHttpServletRequest("POST", "/api/test"));
  }

  private static void assertProblem(
      ResponseEntity<ProblemDetail> response, HttpStatus status, ErrorCode code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().getProperties()).containsEntry("code", code.code());
  }

  // ================================================================
  // createPaymentIntent
  // ================================================================

  @Test
  @DisplayName("AC-01/02: lần đầu NEW (tiền lấy từ order), retry cùng key → EXISTING, vẫn 1 row")
  void createIsIdempotentPerKey() {
    UUID orderId = data.pendingOrder("405000.00");

    PaymentIntentResult first = create(orderId, "pay-it-create");
    PaymentIntentResult retry = create(orderId, "pay-it-create");

    assertThat(first)
        .isEqualTo(
            new PaymentIntentResult(
                first.paymentId(),
                "PENDING",
                new BigDecimal("405000.00"),
                "VND",
                "STRIPE",
                ResultKind.NEW));
    assertThat(retry.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(retry.paymentId()).isEqualTo(first.paymentId());
    assertThat(data.count("SELECT count(*) FROM app.payments WHERE order_id = ?", orderId)).isOne();
  }

  @Test
  @DisplayName("cùng key cho gateway khác / order khác → 422, không tạo thêm row")
  void keyReusedForAnotherRequestIsRejected() {
    UUID orderA = data.pendingOrder("300.00");
    UUID orderB = data.pendingOrder("300.00");
    create(orderA, "pay-it-reused");

    assertError(
        catchThrowable(
            () ->
                paymentService.createPaymentIntent(
                    orderA, PaymentGateway.VNPAY, "pay-it-reused", ACTOR)),
        ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
    assertError(
        catchThrowable(() -> create(orderB, "pay-it-reused")),
        ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
    assertThat(data.count("SELECT count(*) FROM app.payments WHERE order_id = ?", orderB)).isZero();
  }

  /**
   * Partial UNIQUE của V15 nhìn từ service: key mới cho order đang có payment → 409, FAILED nhả ra.
   */
  @Test
  @DisplayName("key mới khi order đang có payment → 409; fail payment cũ rồi tạo lại → NEW")
  void orderWithPaymentInProgressRejectsNewKey() {
    UUID orderId = data.pendingOrder("300.00");
    PaymentIntentResult first = create(orderId, "pay-it-active-1");

    assertError(
        catchThrowable(() -> create(orderId, "pay-it-active-2")),
        ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT);

    assertThat(paymentService.markPaymentFailed(first.paymentId(), "Gateway timeout").getStatus())
        .hasToString("FAILED");
    PaymentIntentResult second = create(orderId, "pay-it-active-2");

    assertThat(second.kind()).isEqualTo(ResultKind.NEW);
    assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
  }

  /** Thứ tự "tra key trước, kiểm order sau". Đảo lại thì retry này nhận 409. */
  @Test
  @DisplayName("retry cùng key sau khi order đã PAID → EXISTING (CAPTURED); key mới → 409")
  void retryAfterOrderPaidReturnsExisting() {
    UUID orderId = data.pendingOrder("300.00");
    UUID paymentId = create(orderId, "pay-it-paid").paymentId();
    paymentService.confirmPayment(paymentId, "pi_it_paid", GATEWAY_OK);
    assertThat(data.orderStatus(orderId)).isEqualTo("PAID");

    PaymentIntentResult retry = create(orderId, "pay-it-paid");

    assertThat(retry.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(retry.paymentId()).isEqualTo(paymentId);
    assertThat(retry.status()).isEqualTo("CAPTURED");
    assertError(
        catchThrowable(() -> create(orderId, "pay-it-paid-2")), ErrorCode.PAY_ORDER_NOT_PAYABLE);
  }

  // ================================================================
  // confirmPayment
  // ================================================================

  @Test
  @DisplayName("AC-03/04/12: capture + order PAID đúng 1 lần; gửi lặp → ack; mã khác → 409")
  void confirmCapturesOnceAndAcksReplay() {
    UUID orderId = data.pendingOrder("300.00");
    UUID paymentId = create(orderId, "pay-it-confirm").paymentId();

    ConfirmPaymentResult first = paymentService.confirmPayment(paymentId, "pi_it_once", GATEWAY_OK);
    ConfirmPaymentResult replay = paymentService.confirmPayment(paymentId, "pi_it_once", "{}");

    assertThat(first).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));
    assertThat(replay).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", true));
    assertThat(data.orderStatus(orderId)).isEqualTo("PAID");
    assertThat(data.historyRows(orderId, "PAID")).isOne();
    assertError(
        catchThrowable(() -> paymentService.confirmPayment(paymentId, "pi_it_other", "{}")),
        ErrorCode.PAY_INVALID_STATE);
    // Lần gửi lặp không ghi đè payload / mã của lần đầu.
    assertThat(
            data.count(
                "SELECT count(*) FROM app.payments WHERE id = ? AND payment_reference ="
                    + " 'pi_it_once' AND gateway_response @> '{\"status\": \"succeeded\"}'",
                paymentId))
        .isOne();
  }

  /**
   * QnA Q13 — chốt cho lỗi lớn nhất của bản kế hoạch. Bản kế hoạch gọi {@code markPaid} rồi bắt
   * {@code ConflictException}: exception đi qua proxy {@code @Transactional} của OrderService đã
   * đánh dấu transaction rollback-only → commit ném {@code UnexpectedRollbackException}, payment
   * vẫn PENDING dù gateway đã trừ tiền.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName(
      "webhook tới sau khi order đã EXPIRED → capture vẫn commit, order giữ EXPIRED, log WARN")
  void captureForExpiredOrderIsKept(CapturedOutput output) {
    UUID orderId = data.pendingOrder("300.00");
    UUID paymentId = create(orderId, "pay-it-late").paymentId();
    data.makeOverdue(orderId, "2 hours");
    orderService.expirePendingOrders();
    assertThat(data.orderStatus(orderId)).isEqualTo("EXPIRED");

    ConfirmPaymentResult result =
        paymentService.confirmPayment(paymentId, "pi_it_late", GATEWAY_OK);

    assertThat(result).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));
    assertThat(data.paymentStatus(paymentId)).isEqualTo("CAPTURED");
    assertThat(data.orderStatus(orderId)).isEqualTo("EXPIRED");
    assertThat(data.historyRows(orderId, "PAID")).isZero();
    assertThat(
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("WARN") && line.contains(paymentId.toString())))
        .as("Payment đã thu tiền mà order không PAID phải để lại dấu cho đối soát")
        .isNotEmpty();
  }

  // ================================================================
  // requestRefund + confirmRefund
  // ================================================================

  @Test
  @DisplayName(
      "AC-05/06/07: hoàn 100 → PARTIALLY_REFUNDED, hoàn nốt 200 → REFUNDED; mọi lần lặp đều ack")
  void refundLifecycle() {
    UUID paymentId = capturedPayment("pay-it-refund", "pi_it_refund");

    RefundResult r1 =
        paymentService.requestRefund(
            paymentId, new BigDecimal("100"), "Giao thiếu hàng", "pay-it-r1", "admin");
    RefundResult r1Retry =
        paymentService.requestRefund(
            paymentId, new BigDecimal("100.00"), "Giao thiếu hàng", "pay-it-r1", "admin");
    assertThat(r1)
        .isEqualTo(new RefundResult(r1.refundId(), "PENDING", r1.amount(), ResultKind.NEW));
    assertThat(r1.amount()).isEqualByComparingTo("100");
    assertThat(r1Retry.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(r1Retry.refundId()).isEqualTo(r1.refundId());
    assertError(
        catchThrowable(
            () ->
                paymentService.requestRefund(
                    paymentId, new BigDecimal("120"), "Giao thiếu hàng", "pay-it-r1", "admin")),
        ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);

    ConfirmRefundResult c1 = paymentService.confirmRefund(r1.refundId(), "re_it_1", GATEWAY_OK);
    ConfirmRefundResult c1Replay = paymentService.confirmRefund(r1.refundId(), "re_it_1", null);
    assertThat(c1)
        .isEqualTo(
            new ConfirmRefundResult(
                r1.refundId(), "COMPLETED", paymentId, "PARTIALLY_REFUNDED", false));
    assertThat(c1Replay.alreadyCompleted()).isTrue();

    // Gateway gửi lại webhook thanh toán SAU khi đã hoàn một phần → vẫn ack.
    assertThat(paymentService.confirmPayment(paymentId, "pi_it_refund", GATEWAY_OK))
        .isEqualTo(new ConfirmPaymentResult(paymentId, "PARTIALLY_REFUNDED", true));

    RefundResult r2 =
        paymentService.requestRefund(
            paymentId, new BigDecimal("200.00"), "Hoàn phần còn lại", "pay-it-r2", "admin");
    assertThat(paymentService.confirmRefund(r2.refundId(), "re_it_2", null).paymentStatus())
        .isEqualTo("REFUNDED");

    // Thứ tự "tra key trước, khóa sau": retry r1 khi payment đã REFUNDED vẫn nhận refund cũ.
    RefundResult r1Late =
        paymentService.requestRefund(
            paymentId, new BigDecimal("100"), "Giao thiếu hàng", "pay-it-r1", "admin");
    assertThat(r1Late.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(r1Late.status()).isEqualTo("COMPLETED");
    assertError(
        catchThrowable(
            () ->
                paymentService.requestRefund(
                    paymentId, new BigDecimal("1"), "Thêm", "pay-it-r3", "admin")),
        ErrorCode.PAY_INVALID_STATE);
    assertThat(paymentService.getRefundsForPayment(paymentId))
        .extracting(refund -> refund.getStatus().name())
        .containsExactly("COMPLETED", "COMPLETED");
  }

  /**
   * AC-10/11. Service không tự cộng tổng — trigger AFTER của V15 là chốt, handler dịch lỗi của nó.
   */
  @Test
  @DisplayName("tổng refund vượt số tiền đã thu → trigger chặn → 409 PAY_REFUND_EXCEEDS_PAYMENT")
  void refundBeyondCapturedAmountIsRejectedByTrigger() {
    UUID paymentId = capturedPayment("pay-it-exceed", "pi_it_exceed");
    paymentService.requestRefund(paymentId, new BigDecimal("200"), "60%", "pay-it-e1", "admin");

    Throwable thrown =
        catchThrowable(
            () ->
                paymentService.requestRefund(
                    paymentId, new BigDecimal("150"), "50%", "pay-it-e2", "admin"));

    assertProblem(handle(thrown), HttpStatus.CONFLICT, ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT);
    assertThat(paymentService.getRefundsForPayment(paymentId)).hasSize(1);
  }

  // ================================================================
  // Lỗi DB → mã lỗi cho client
  // ================================================================

  /**
   * Message thật của Postgres có nhiều dòng: dòng đầu là lỗi, sau đó Detail / Where chép lại DỮ
   * LIỆU của request. Payload dưới đây hỏng cú pháp và có chữ "unique slug": so khớp cả message thì
   * resolver của catalog ({@code unique && slug}) nhận nó là {@code PRODUCT_SLUG_DUPLICATED}.
   */
  @Test
  @DisplayName("lỗi DB thật đi qua handler: payload hỏng → 400; mã giao dịch đã có → 409")
  void databaseErrorsMapToClientCodes() {
    UUID taken = create(data.pendingOrder("300.00"), "pay-it-db-1").paymentId();
    UUID other = create(data.pendingOrder("300.00"), "pay-it-db-2").paymentId();
    paymentService.confirmPayment(taken, "pi_it_dup", GATEWAY_OK);

    Throwable malformed =
        catchThrowable(
            () ->
                paymentService.confirmPayment(
                    other, "pi_it_json", "{\"note\": \"unique slug\" oops}"));
    Throwable duplicate =
        catchThrowable(() -> paymentService.confirmPayment(other, "pi_it_dup", GATEWAY_OK));

    assertThat(malformed).rootCause().hasMessageContaining("unique slug");
    assertProblem(handle(malformed), HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED);
    assertProblem(handle(duplicate), HttpStatus.CONFLICT, ErrorCode.PAY_REFERENCE_TAKEN);
    assertThat(data.paymentStatus(other)).isEqualTo("PENDING");
  }

  /**
   * Khe còn lại của {@code markOrderPaid}: order đổi trạng thái GIỮA câu đọc order và câu UPDATE
   * trong {@code markPaid}. Cổng khóa đóng vai giao dịch đó (job expire / khách hủy): giữ row
   * order, đợi webhook đứng chờ ở UPDATE của {@code markPaid}, chuyển order sang EXPIRED rồi
   * commit. {@code markPaid} ra 0 row → {@code ORD_RACE_CONDITION} → CẢ transaction rollback, kể cả
   * capture. Gateway gửi lại → order đã EXPIRED từ đầu → capture + WARN.
   */
  @Test
  @DisplayName(
      "order hết hạn đúng lúc webhook capture → 409 ORD_RACE_CONDITION, không mất gì; gửi lại →"
          + " capture")
  void orderExpiringDuringCaptureIsSettledByRetry() throws Exception {
    UUID orderId = data.pendingOrder("300.00");
    UUID paymentId = create(orderId, "pay-it-race-expire").paymentId();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection gate =
        LockTestSupport.holdRowLock(
            dataSource, "SELECT 1 FROM app.orders WHERE id = ? FOR UPDATE", orderId)) {
      Future<ConfirmPaymentResult> webhook =
          pool.submit(
              () -> paymentService.confirmPayment(paymentId, "pi_it_race_expire", GATEWAY_OK));
      LockTestSupport.awaitLockWaiters(jdbc, 1);
      try (PreparedStatement expire =
          gate.prepareStatement(
              "UPDATE app.orders SET status = 'EXPIRED', cancelled_at = now(),"
                  + " cancel_reason = 'race' WHERE id = ?")) {
        expire.setObject(1, orderId);
        expire.executeUpdate();
      }
      gate.commit();

      Throwable thrown = catchThrowable(() -> await(webhook));
      assertThat(thrown).isInstanceOf(ExecutionException.class);
      assertError(thrown.getCause(), ErrorCode.ORD_RACE_CONDITION);
    } finally {
      pool.shutdownNow();
    }
    assertThat(data.paymentStatus(paymentId))
        .as("capture rollback cùng markPaid")
        .isEqualTo("PENDING");

    ConfirmPaymentResult retry =
        paymentService.confirmPayment(paymentId, "pi_it_race_expire", GATEWAY_OK);

    assertThat(retry).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));
    assertThat(data.orderStatus(orderId)).isEqualTo("EXPIRED");
  }

  // ================================================================
  // Chạy chồng — chốt READ COMMITTED
  // ================================================================

  /**
   * AC-15 ở dạng chắc chắn chồng nhau: cổng khóa giữ row payment, đợi CẢ HAI webhook đứng chờ ở câu
   * UPDATE của {@code captureAtomic} rồi mới nhả. Bên thứ hai chờ bên thứ nhất commit, đánh giá lại
   * WHERE trên row mới → 0 row → ack. REPEATABLE READ: bên thứ hai nhận 40001.
   */
  @Test
  @DisplayName("2 webhook cùng mã chạy chồng → đúng 1 capture, 1 ack, order PAID đúng 1 lần")
  void concurrentWebhooksCaptureOnce() throws Exception {
    UUID orderId = data.pendingOrder("300.00");
    UUID paymentId = create(orderId, "pay-it-race-webhook").paymentId();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate =
        LockTestSupport.holdRowLock(
            dataSource, "SELECT 1 FROM app.payments WHERE id = ? FOR UPDATE", paymentId)) {
      Future<ConfirmPaymentResult> a =
          pool.submit(() -> paymentService.confirmPayment(paymentId, "pi_it_race", GATEWAY_OK));
      Future<ConfirmPaymentResult> b =
          pool.submit(() -> paymentService.confirmPayment(paymentId, "pi_it_race", GATEWAY_OK));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      gate.commit();

      assertThat(List.of(await(a), await(b)))
          .extracting(ConfirmPaymentResult::alreadyCaptured)
          .containsExactlyInAnyOrder(false, true);
    } finally {
      pool.shutdownNow();
    }
    assertThat(data.orderStatus(orderId)).isEqualTo("PAID");
    assertThat(data.historyRows(orderId, "PAID")).isOne();
  }

  /**
   * AC-17 ở dạng chắc chắn chồng nhau: cổng khóa giữ row ORDER, nên câu INSERT payment của bên thứ
   * nhất dừng ở bước kiểm FK (cần {@code FOR KEY SHARE} trên order), còn bên thứ hai dừng sau nó ở
   * UNIQUE của key. Nhả → bên thứ nhất ghi, bên thứ hai bị {@code ON CONFLICT} bỏ qua rồi đọc lại
   * thấy payment vừa commit → EXISTING. REPEATABLE READ: row đó không nằm trong snapshot → 40001.
   */
  @Test
  @DisplayName("2 request tạo payment cùng key chạy chồng → 1 NEW, 1 EXISTING, cùng một payment")
  void concurrentCreateWithSameKeyReturnsOnePayment() throws Exception {
    UUID orderId = data.pendingOrder("300.00");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate =
        LockTestSupport.holdRowLock(
            dataSource, "SELECT 1 FROM app.orders WHERE id = ? FOR UPDATE", orderId)) {
      Future<PaymentIntentResult> a = pool.submit(() -> create(orderId, "pay-it-race-create"));
      Future<PaymentIntentResult> b = pool.submit(() -> create(orderId, "pay-it-race-create"));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      gate.commit();

      List<PaymentIntentResult> results = List.of(await(a), await(b));
      assertThat(results)
          .extracting(PaymentIntentResult::kind)
          .containsExactlyInAnyOrder(ResultKind.NEW, ResultKind.EXISTING);
      assertThat(results.get(0).paymentId()).isEqualTo(results.get(1).paymentId());
    } finally {
      pool.shutdownNow();
    }
    assertThat(data.count("SELECT count(*) FROM app.payments WHERE order_id = ?", orderId)).isOne();
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS);
  }
}
