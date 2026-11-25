package com.tungph7.learnpostgres.feature.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.UnprocessableContentException;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.service.OrderService;
import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus;
import com.tungph7.learnpostgres.feature.payment.domain.RefundStatus;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.repository.PaymentRefundRepository;
import com.tungph7.learnpostgres.feature.payment.repository.PaymentRepository;

/**
 * Unit test cho {@link PaymentServiceImpl}: thứ tự gọi repository, cách phân loại kết quả của câu
 * ghi (1 row / 0 row / list rỗng) và mã lỗi của từng nhánh.
 *
 * <p>Những thứ mock không chứng minh được — {@code ON CONFLICT} thật, 2 transaction chạy chồng,
 * rollback-only khi {@code markPaid} ném lỗi, trigger V15 — ở {@code
 * PaymentServiceIntegrationTest}.
 *
 * <p>Entity được mock (không có constructor public, không setter — bài 02 §5) với strictness
 * LENIENT: mỗi helper stub đủ mọi getter, test nào dùng tới cái nào thì dùng. Fixture phải dựng ra
 * biến TRƯỚC câu {@code when(...)} cần nó — dựng ngay trong {@code thenReturn(...)} là stub lồng
 * trong một stub chưa xong, Mockito ném {@code UnfinishedStubbingException}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

  private static final String KEY = "pay-key-1";
  private static final String REFUND_KEY = "refund-key-1";
  private static final String REF = "pi_ref_1";
  private static final String ACTOR = "user";
  private static final BigDecimal AMOUNT = new BigDecimal("300.00");
  private static final Set<PaymentStatus> CAPTURED_GROUP =
      EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

  @Mock PaymentRepository paymentRepo;
  @Mock PaymentRefundRepository refundRepo;
  @Mock OrderService orderService;

  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();
  private final UUID refundId = UUID.randomUUID();

  private PaymentServiceImpl service() {
    return new PaymentServiceImpl(paymentRepo, refundRepo, orderService);
  }

  // ----------------------------------------------------------------
  // Fixture
  // ----------------------------------------------------------------

  private static <T> T lenientMock(Class<T> type) {
    return mock(type, withSettings().strictness(Strictness.LENIENT));
  }

  private Order order(OrderStatus status, String total) {
    Order order = lenientMock(Order.class);
    when(order.getId()).thenReturn(orderId);
    when(order.getOrderNumber()).thenReturn("ORD-202610-000001");
    when(order.getStatus()).thenReturn(status);
    when(order.getTotal()).thenReturn(new BigDecimal(total));
    return order;
  }

  /** Payment 300.00 VND; đã thu tiền thì có {@code captured_at} (như CHECK của V15). */
  private Payment payment(UUID ofOrder, PaymentGateway gateway, PaymentStatus status, String ref) {
    Payment payment = lenientMock(Payment.class);
    when(payment.getId()).thenReturn(paymentId);
    when(payment.getOrderId()).thenReturn(ofOrder);
    when(payment.getIdempotencyKey()).thenReturn(KEY);
    when(payment.getAmount()).thenReturn(AMOUNT);
    when(payment.getCurrency()).thenReturn("VND");
    when(payment.getGateway()).thenReturn(gateway);
    when(payment.getStatus()).thenReturn(status);
    when(payment.getPaymentReference()).thenReturn(ref);
    when(payment.getCapturedAt())
        .thenReturn(CAPTURED_GROUP.contains(status) ? Instant.parse("2026-10-03T08:00:00Z") : null);
    return payment;
  }

  /** Payment STRIPE của {@link #orderId}. */
  private Payment payment(PaymentStatus status, String ref) {
    return payment(orderId, PaymentGateway.STRIPE, status, ref);
  }

  private PaymentRefund refund(
      UUID ofPayment, String amount, String reason, RefundStatus status, String ref) {
    PaymentRefund refund = lenientMock(PaymentRefund.class);
    when(refund.getId()).thenReturn(refundId);
    when(refund.getPaymentId()).thenReturn(ofPayment);
    when(refund.getIdempotencyKey()).thenReturn(REFUND_KEY);
    when(refund.getAmount()).thenReturn(new BigDecimal(amount));
    when(refund.getReason()).thenReturn(reason);
    when(refund.getStatus()).thenReturn(status);
    when(refund.getRefundReference()).thenReturn(ref);
    return refund;
  }

  /** Refund PENDING của {@link #paymentId}. */
  private PaymentRefund pendingRefund(String amount, String reason) {
    return refund(paymentId, amount, reason, RefundStatus.PENDING, null);
  }

  private static void assertError(Throwable thrown, Class<?> type, ErrorCode code) {
    assertThat(thrown).isInstanceOf(type);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  // ================================================================
  // createPaymentIntent
  // ================================================================

  @Nested
  @DisplayName("createPaymentIntent")
  class CreatePaymentIntent {

    private Throwable create() {
      return catchThrowable(
          () -> service().createPaymentIntent(orderId, PaymentGateway.STRIPE, KEY, ACTOR));
    }

    @Test
    @DisplayName("lần đầu: tra key → kiểm order → INSERT với total của order → NEW")
    void newPayment() {
      Order order = order(OrderStatus.PENDING, "300.00");
      Payment created = payment(PaymentStatus.PENDING, null);
      BigDecimal total = order.getTotal();
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
      when(orderService.getById(orderId)).thenReturn(order);
      when(paymentRepo.insertIfAbsent(orderId, KEY, total, "STRIPE", ACTOR))
          .thenReturn(List.of(created));

      PaymentIntentResult result =
          service().createPaymentIntent(orderId, PaymentGateway.STRIPE, KEY, ACTOR);

      assertThat(result)
          .isEqualTo(
              new PaymentIntentResult(
                  paymentId, "PENDING", AMOUNT, "VND", "STRIPE", ResultKind.NEW));
      InOrder steps = inOrder(paymentRepo, orderService);
      steps.verify(paymentRepo).findByIdempotencyKey(KEY);
      steps.verify(orderService).getById(orderId);
      steps.verify(paymentRepo).insertIfAbsent(orderId, KEY, total, "STRIPE", ACTOR);
    }

    /** Thứ tự "key trước order": retry tới sau khi order đã PAID vẫn nhận payment cũ. */
    @Test
    @DisplayName("key đã có, cùng order + gateway → EXISTING, không nhìn tới order")
    void replayDoesNotLookAtOrder() {
      Payment existing = payment(PaymentStatus.CAPTURED, REF);
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

      PaymentIntentResult result =
          service().createPaymentIntent(orderId, PaymentGateway.STRIPE, KEY, ACTOR);

      assertThat(result.kind()).isEqualTo(ResultKind.EXISTING);
      assertThat(result.paymentId()).isEqualTo(paymentId);
      assertThat(result.status()).isEqualTo("CAPTURED");
      verifyNoInteractions(orderService);
      verify(paymentRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("key đã có cho order khác / gateway khác → 422 PAY_IDEMPOTENCY_KEY_REUSED")
    void keyReusedForAnotherRequest() {
      Payment otherOrder =
          payment(UUID.randomUUID(), PaymentGateway.STRIPE, PaymentStatus.PENDING, null);
      Payment otherGateway = payment(orderId, PaymentGateway.VNPAY, PaymentStatus.PENDING, null);
      when(paymentRepo.findByIdempotencyKey(KEY))
          .thenReturn(Optional.of(otherOrder), Optional.of(otherGateway));

      assertError(
          create(), UnprocessableContentException.class, ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
      assertError(
          create(), UnprocessableContentException.class, ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
      verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @EnumSource(
        value = OrderStatus.class,
        names = {"PENDING"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("lần đầu, order không còn PENDING → 409 PAY_ORDER_NOT_PAYABLE, không INSERT")
    void orderNotPending(OrderStatus status) {
      Order order = order(status, "300.00");
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
      when(orderService.getById(orderId)).thenReturn(order);

      assertError(create(), ConflictException.class, ErrorCode.PAY_ORDER_NOT_PAYABLE);
      verify(paymentRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    /** Coupon 100% + phí ship 0 cho ra order PENDING tổng 0 — V15 cấm payment 0 đồng. */
    @Test
    @DisplayName("order PENDING nhưng tổng 0 → 409 PAY_ORDER_NOT_PAYABLE, không INSERT")
    void zeroTotalOrder() {
      Order order = order(OrderStatus.PENDING, "0.00");
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
      when(orderService.getById(orderId)).thenReturn(order);

      assertError(create(), ConflictException.class, ErrorCode.PAY_ORDER_NOT_PAYABLE);
      verify(paymentRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("order không tồn tại → ORD_NOT_FOUND của OrderService đi thẳng ra")
    void orderNotFound() {
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
      when(orderService.getById(orderId))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "missing"));

      assertError(create(), NotFoundException.class, ErrorCode.ORD_NOT_FOUND);
    }

    @Test
    @DisplayName("INSERT rỗng, tra lại thấy key của đúng request (chạy chồng) → EXISTING")
    void lostRaceToSameRequest() {
      Order order = order(OrderStatus.PENDING, "300.00");
      Payment winner = payment(PaymentStatus.PENDING, null);
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
      when(orderService.getById(orderId)).thenReturn(order);
      when(paymentRepo.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(List.of());

      PaymentIntentResult result =
          service().createPaymentIntent(orderId, PaymentGateway.STRIPE, KEY, ACTOR);

      assertThat(result.kind()).isEqualTo(ResultKind.EXISTING);
      assertThat(result.paymentId()).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("INSERT rỗng, tra lại thấy key nhưng của request khác → 422")
    void lostRaceToOtherRequestWithSameKey() {
      Order order = order(OrderStatus.PENDING, "300.00");
      Payment other = payment(orderId, PaymentGateway.MOMO, PaymentStatus.PENDING, null);
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(other));
      when(orderService.getById(orderId)).thenReturn(order);
      when(paymentRepo.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(List.of());

      assertError(
          create(), UnprocessableContentException.class, ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
    }

    /** List rỗng mà không có row theo key: xung đột nằm ở uq_payments_order_non_failed. */
    @Test
    @DisplayName("INSERT rỗng, tra lại không thấy key → 409 PAY_ORDER_ALREADY_HAS_PAYMENT")
    void orderAlreadyHasPayment() {
      Order order = order(OrderStatus.PENDING, "300.00");
      when(paymentRepo.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.empty());
      when(orderService.getById(orderId)).thenReturn(order);
      when(paymentRepo.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(List.of());

      assertError(create(), ConflictException.class, ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
          "",
          "   ",
          "k123456789k123456789k123456789k123456789k123456789k123456789k123456789k123456789X"
        })
    @DisplayName("key null / rỗng / dài hơn 80 → 400 VALIDATION_FAILED, không chạm DB")
    void invalidKey(String key) {
      assertError(
          catchThrowable(
              () -> service().createPaymentIntent(orderId, PaymentGateway.STRIPE, key, ACTOR)),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, orderService);
    }

    @Test
    @DisplayName("key đúng 80 ký tự vẫn hợp lệ")
    void keyAtColumnLimit() {
      String key80 = "k".repeat(80);
      Order order = order(OrderStatus.PENDING, "300.00");
      Payment created = payment(PaymentStatus.PENDING, null);
      when(paymentRepo.findByIdempotencyKey(key80)).thenReturn(Optional.empty());
      when(orderService.getById(orderId)).thenReturn(order);
      when(paymentRepo.insertIfAbsent(any(), any(), any(), any(), any()))
          .thenReturn(List.of(created));

      assertThat(service().createPaymentIntent(orderId, PaymentGateway.STRIPE, key80, ACTOR).kind())
          .isEqualTo(ResultKind.NEW);
    }

    @Test
    @DisplayName("gateway null → 400 VALIDATION_FAILED, không chạm DB")
    void nullGateway() {
      assertError(
          catchThrowable(() -> service().createPaymentIntent(orderId, null, KEY, ACTOR)),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, orderService);
    }
  }

  // ================================================================
  // confirmPayment
  // ================================================================

  @Nested
  @DisplayName("confirmPayment")
  class ConfirmPayment {

    private Throwable confirm() {
      return catchThrowable(() -> service().confirmPayment(paymentId, REF, "{}"));
    }

    @Test
    @DisplayName("capture 1 row → order PENDING chuyển PAID → captured")
    void capturesAndMarksOrderPaid() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      Order order = order(OrderStatus.PENDING, "300.00");
      when(paymentRepo.captureAtomic(paymentId, REF, "{}")).thenReturn(1);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(captured));
      when(orderService.getById(orderId)).thenReturn(order);

      ConfirmPaymentResult result = service().confirmPayment(paymentId, REF, "{}");

      assertThat(result).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));
      verify(orderService).markPaid(orderId);
    }

    /**
     * QnA Q13: gateway đã trừ tiền → capture phải ở lại. Không gọi markPaid — nó sẽ ném lỗi và đánh
     * dấu transaction rollback-only (integration test chứng minh phần đó).
     */
    @ParameterizedTest
    @EnumSource(
        value = OrderStatus.class,
        names = {"EXPIRED", "CANCELLED", "PAID"})
    @DisplayName("capture 1 row nhưng order đã rời PENDING → giữ capture, KHÔNG gọi markPaid")
    void orderNoLongerPendingKeepsCapture(OrderStatus status) {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      Order order = order(status, "300.00");
      when(paymentRepo.captureAtomic(paymentId, REF, null)).thenReturn(1);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(captured));
      when(orderService.getById(orderId)).thenReturn(order);

      ConfirmPaymentResult result = service().confirmPayment(paymentId, REF, null);

      assertThat(result).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));
      verify(orderService, never()).markPaid(any());
    }

    @ParameterizedTest
    @EnumSource(
        value = PaymentStatus.class,
        names = {"CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("0 row, đã thu tiền với ĐÚNG mã → ack (alreadyCaptured), status hiện tại")
    void replayWithSameReference(PaymentStatus status) {
      Payment existing = payment(status, REF);
      when(paymentRepo.captureAtomic(paymentId, REF, "{}")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(existing));

      ConfirmPaymentResult result = service().confirmPayment(paymentId, REF, "{}");

      assertThat(result).isEqualTo(new ConfirmPaymentResult(paymentId, status.name(), true));
      verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("0 row, đã thu tiền với mã KHÁC → 409 PAY_INVALID_STATE")
    void capturedWithOtherReference() {
      Payment existing = payment(PaymentStatus.CAPTURED, "pi_other");
      when(paymentRepo.captureAtomic(paymentId, REF, "{}")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(existing));

      assertError(confirm(), ConflictException.class, ErrorCode.PAY_INVALID_STATE);
      verifyNoInteractions(orderService);
    }

    /** FAILED có thể mang mã của gateway (V15 cho phép) — trùng mã vẫn không phải "đã capture". */
    @Test
    @DisplayName("0 row, payment FAILED (kể cả mang đúng mã) → 409 PAY_INVALID_STATE")
    void failedPayment() {
      Payment failed = payment(PaymentStatus.FAILED, REF);
      when(paymentRepo.captureAtomic(paymentId, REF, "{}")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(failed));

      assertError(confirm(), ConflictException.class, ErrorCode.PAY_INVALID_STATE);
    }

    @Test
    @DisplayName("0 row, payment không tồn tại → 404 PAY_NOT_FOUND")
    void unknownPayment() {
      when(paymentRepo.captureAtomic(paymentId, REF, "{}")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.empty());

      assertError(confirm(), NotFoundException.class, ErrorCode.PAY_NOT_FOUND);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
          " ",
          "r123456789r123456789r123456789r123456789r123456789r123456789r123456789r123456789X"
        })
    @DisplayName("mã giao dịch null / rỗng / dài hơn 80 → 400, không chạm DB")
    void invalidReference(String ref) {
      assertError(
          catchThrowable(() -> service().confirmPayment(paymentId, ref, "{}")),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, orderService);
    }
  }

  // ================================================================
  // markPaymentFailed
  // ================================================================

  @Nested
  @DisplayName("markPaymentFailed")
  class MarkPaymentFailed {

    @Test
    @DisplayName("1 row → trả payment đọc lại")
    void marksFailed() {
      Payment failed = payment(PaymentStatus.FAILED, null);
      when(paymentRepo.markFailed(paymentId, "timeout")).thenReturn(1);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(failed));

      assertThat(service().markPaymentFailed(paymentId, "timeout")).isSameAs(failed);
    }

    @Test
    @DisplayName("0 row, đã FAILED → no-op, trả payment")
    void alreadyFailedIsNoop() {
      Payment failed = payment(PaymentStatus.FAILED, null);
      when(paymentRepo.markFailed(paymentId, "timeout")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(failed));

      assertThat(service().markPaymentFailed(paymentId, "timeout")).isSameAs(failed);
    }

    @ParameterizedTest
    @EnumSource(
        value = PaymentStatus.class,
        names = {"CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("0 row, đã thu tiền → 409 PAY_INVALID_STATE")
    void capturedCannotFail(PaymentStatus status) {
      Payment captured = payment(status, REF);
      when(paymentRepo.markFailed(paymentId, "timeout")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(captured));

      assertError(
          catchThrowable(() -> service().markPaymentFailed(paymentId, "timeout")),
          ConflictException.class,
          ErrorCode.PAY_INVALID_STATE);
    }

    @Test
    @DisplayName("0 row, không tồn tại → 404 PAY_NOT_FOUND")
    void unknownPayment() {
      when(paymentRepo.markFailed(paymentId, "timeout")).thenReturn(0);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.empty());

      assertError(
          catchThrowable(() -> service().markPaymentFailed(paymentId, "timeout")),
          NotFoundException.class,
          ErrorCode.PAY_NOT_FOUND);
    }
  }

  // ================================================================
  // Đọc
  // ================================================================

  @Nested
  @DisplayName("đọc")
  class Reads {

    @Test
    @DisplayName("getById — có thì trả, không có → 404 PAY_NOT_FOUND")
    void getById() {
      Payment payment = payment(PaymentStatus.PENDING, null);
      UUID unknown = UUID.randomUUID();
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(payment));
      when(paymentRepo.findById(unknown)).thenReturn(Optional.empty());

      assertThat(service().getById(paymentId)).isSameAs(payment);
      assertError(
          catchThrowable(() -> service().getById(unknown)),
          NotFoundException.class,
          ErrorCode.PAY_NOT_FOUND);
    }

    @Test
    @DisplayName(
        "listPaymentsForOrder — order có thì trả list; không có → ORD_NOT_FOUND, không query")
    void listPaymentsForOrder() {
      Order order = order(OrderStatus.PENDING, "300.00");
      Payment payment = payment(PaymentStatus.FAILED, null);
      UUID unknown = UUID.randomUUID();
      when(orderService.getById(orderId)).thenReturn(order);
      when(orderService.getById(unknown))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "missing"));
      when(paymentRepo.findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
          .thenReturn(List.of(payment));

      assertThat(service().listPaymentsForOrder(orderId)).containsExactly(payment);
      assertError(
          catchThrowable(() -> service().listPaymentsForOrder(unknown)),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
      verify(paymentRepo, never()).findByOrderIdOrderByCreatedAtDescIdDesc(unknown);
    }

    @Test
    @DisplayName("getRefundsForPayment — payment có thì trả list; không có → 404, không query")
    void getRefundsForPayment() {
      PaymentRefund refund = pendingRefund("100.00", "test");
      UUID unknown = UUID.randomUUID();
      when(paymentRepo.existsById(paymentId)).thenReturn(true);
      when(paymentRepo.existsById(unknown)).thenReturn(false);
      when(refundRepo.findByPaymentIdOrderByRequestedAtDescIdDesc(paymentId))
          .thenReturn(List.of(refund));

      assertThat(service().getRefundsForPayment(paymentId)).containsExactly(refund);
      assertError(
          catchThrowable(() -> service().getRefundsForPayment(unknown)),
          NotFoundException.class,
          ErrorCode.PAY_NOT_FOUND);
      verify(refundRepo, never()).findByPaymentIdOrderByRequestedAtDescIdDesc(unknown);
    }

    @Test
    @DisplayName("getRefund — có thì trả, không có → 404 PAY_REFUND_NOT_FOUND")
    void getRefund() {
      PaymentRefund refund = pendingRefund("100.00", "test");
      UUID unknown = UUID.randomUUID();
      when(refundRepo.findById(refundId)).thenReturn(Optional.of(refund));
      when(refundRepo.findById(unknown)).thenReturn(Optional.empty());

      assertThat(service().getRefund(refundId)).isSameAs(refund);
      assertError(
          catchThrowable(() -> service().getRefund(unknown)),
          NotFoundException.class,
          ErrorCode.PAY_REFUND_NOT_FOUND);
    }
  }

  // ================================================================
  // requestRefund
  // ================================================================

  @Nested
  @DisplayName("requestRefund")
  class RequestRefund {

    private final BigDecimal amount = new BigDecimal("100.00");

    private Throwable request(BigDecimal value, String reason, String key) {
      return catchThrowable(() -> service().requestRefund(paymentId, value, reason, key, ACTOR));
    }

    @Test
    @DisplayName("lần đầu: tra key → khóa payment → INSERT → NEW")
    void newRefund() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      PaymentRefund created = pendingRefund("100.00", "test");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(captured));
      when(refundRepo.insertIfAbsent(paymentId, REFUND_KEY, amount, "test", ACTOR))
          .thenReturn(List.of(created));

      RefundResult result = service().requestRefund(paymentId, amount, "test", REFUND_KEY, ACTOR);

      assertThat(result).isEqualTo(new RefundResult(refundId, "PENDING", amount, ResultKind.NEW));
      InOrder steps = inOrder(refundRepo, paymentRepo);
      steps.verify(refundRepo).findByIdempotencyKey(REFUND_KEY);
      steps.verify(paymentRepo).lockById(paymentId);
      steps.verify(refundRepo).insertIfAbsent(paymentId, REFUND_KEY, amount, "test", ACTOR);
    }

    /** Thứ tự "key trước khóa": retry sau khi payment đã REFUNDED vẫn nhận refund cũ. */
    @Test
    @DisplayName(
        "key đã có, cùng payment + số tiền (so theo giá trị) + lý do → EXISTING, không khóa")
    void replayDoesNotLockPayment() {
      PaymentRefund existing = refund(paymentId, "100.00", "test", RefundStatus.COMPLETED, "re_1");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.of(existing));

      RefundResult result =
          service().requestRefund(paymentId, new BigDecimal("100"), "test", REFUND_KEY, ACTOR);

      assertThat(result.kind()).isEqualTo(ResultKind.EXISTING);
      assertThat(result.status()).isEqualTo("COMPLETED");
      verifyNoInteractions(paymentRepo);
      verify(refundRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("key đã có cho payment / số tiền / lý do khác → 422 PAY_IDEMPOTENCY_KEY_REUSED")
    void keyReusedForAnotherRequest() {
      PaymentRefund otherPayment =
          refund(UUID.randomUUID(), "100.00", "test", RefundStatus.PENDING, null);
      PaymentRefund otherAmount = pendingRefund("100.01", "test");
      PaymentRefund otherReason = pendingRefund("100.00", "khác lý do");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY))
          .thenReturn(
              Optional.of(otherPayment), Optional.of(otherAmount), Optional.of(otherReason));

      for (int i = 0; i < 3; i++) {
        assertError(
            request(amount, "test", REFUND_KEY),
            UnprocessableContentException.class,
            ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
      }
      verifyNoInteractions(paymentRepo);
    }

    @ParameterizedTest
    @EnumSource(
        value = PaymentStatus.class,
        names = {"PENDING", "AUTHORIZED", "FAILED", "REFUNDED"})
    @DisplayName("payment chưa thu tiền / đã hoàn hết → 409 PAY_INVALID_STATE, không INSERT")
    void paymentNotRefundable(PaymentStatus status) {
      Payment payment = payment(status, null);
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(payment));

      assertError(
          request(amount, "test", REFUND_KEY),
          ConflictException.class,
          ErrorCode.PAY_INVALID_STATE);
      verify(refundRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "riêng refund này vượt số tiền đã thu → 409 PAY_REFUND_EXCEEDS_PAYMENT, không INSERT")
    void singleRefundAboveCapturedAmount() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(captured));

      assertError(
          request(new BigDecimal("300.01"), "test", REFUND_KEY),
          ConflictException.class,
          ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT);
      verify(refundRepo, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("hoàn đúng bằng số tiền đã thu vẫn qua bước kiểm của service")
    void fullRefundIsAllowed() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      PaymentRefund created = pendingRefund("300.00", "full");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(captured));
      when(refundRepo.insertIfAbsent(paymentId, REFUND_KEY, AMOUNT, "full", ACTOR))
          .thenReturn(List.of(created));

      assertThat(service().requestRefund(paymentId, AMOUNT, "full", REFUND_KEY, ACTOR).kind())
          .isEqualTo(ResultKind.NEW);
    }

    @Test
    @DisplayName("payment không tồn tại → 404 PAY_NOT_FOUND")
    void unknownPayment() {
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.empty());

      assertError(
          request(amount, "test", REFUND_KEY), NotFoundException.class, ErrorCode.PAY_NOT_FOUND);
    }

    /** Tổng vượt là việc của trigger: lỗi đi thẳng ra cho GlobalExceptionHandler, không bọc. */
    @Test
    @DisplayName("trigger báo vượt tổng → DataIntegrityViolationException đi thẳng ra")
    void triggerErrorPropagates() {
      Payment partiallyRefunded = payment(PaymentStatus.PARTIALLY_REFUNDED, REF);
      DataIntegrityViolationException triggerError =
          new DataIntegrityViolationException("PAY_REFUND_EXCEEDS_PAYMENT: refunds 400 > 300");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(partiallyRefunded));
      when(refundRepo.insertIfAbsent(paymentId, REFUND_KEY, amount, "test", ACTOR))
          .thenThrow(triggerError);

      assertThat(request(amount, "test", REFUND_KEY)).isSameAs(triggerError);
    }

    @Test
    @DisplayName("INSERT rỗng → tra lại theo key: cùng request → EXISTING")
    void lostRaceToSameRequest() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      PaymentRefund winner = pendingRefund("100.00", "test");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY))
          .thenReturn(Optional.empty(), Optional.of(winner));
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(captured));
      when(refundRepo.insertIfAbsent(paymentId, REFUND_KEY, amount, "test", ACTOR))
          .thenReturn(List.of());

      assertThat(service().requestRefund(paymentId, amount, "test", REFUND_KEY, ACTOR).kind())
          .isEqualTo(ResultKind.EXISTING);
    }

    /**
     * ON CONFLICT (idempotency_key) chỉ nuốt xung đột của key → rỗng mà không thấy key là vô lý.
     */
    @Test
    @DisplayName("INSERT rỗng mà tra lại không thấy key → IllegalStateException")
    void emptyInsertWithoutKeyIsImpossible() {
      Payment captured = payment(PaymentStatus.CAPTURED, REF);
      when(refundRepo.findByIdempotencyKey(REFUND_KEY))
          .thenReturn(Optional.empty(), Optional.empty());
      when(paymentRepo.lockById(paymentId)).thenReturn(Optional.of(captured));
      when(refundRepo.insertIfAbsent(paymentId, REFUND_KEY, amount, "test", ACTOR))
          .thenReturn(List.of());

      assertThat(request(amount, "test", REFUND_KEY)).isInstanceOf(IllegalStateException.class);
    }

    /** 100.005: NUMERIC(12,2) lưu thành 100.01 — retry gửi lại 100.005 sẽ không khớp refund cũ. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-1", "0.001", "100.005"})
    @DisplayName("amount null / ≤ 0 / quá 2 chữ số lẻ → 400, không chạm DB")
    void invalidAmount(BigDecimal badAmount) {
      assertError(
          request(badAmount, "test", REFUND_KEY),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, refundRepo);
    }

    @Test
    @DisplayName("amount 100.500 (số 0 thừa sau dấu phẩy) vẫn hợp lệ, so khớp 100.50 đã lưu")
    void trailingZerosAreFine() {
      PaymentRefund existing = pendingRefund("100.50", "test");
      when(refundRepo.findByIdempotencyKey(REFUND_KEY)).thenReturn(Optional.of(existing));

      RefundResult result =
          service().requestRefund(paymentId, new BigDecimal("100.500"), "test", REFUND_KEY, ACTOR);

      assertThat(result.kind()).isEqualTo(ResultKind.EXISTING);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("lý do null / rỗng → 400, không chạm DB")
    void invalidReason(String reason) {
      assertError(
          request(amount, reason, REFUND_KEY),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, refundRepo);
    }

    @Test
    @DisplayName("lý do dài hơn 255 → 400; key rỗng → 400")
    void tooLongReasonAndBlankKey() {
      assertError(
          request(amount, "x".repeat(256), REFUND_KEY),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      assertError(
          request(amount, "test", " "), BadRequestException.class, ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, refundRepo);
    }
  }

  // ================================================================
  // confirmRefund
  // ================================================================

  @Nested
  @DisplayName("confirmRefund")
  class ConfirmRefund {

    private Throwable confirm() {
      return catchThrowable(() -> service().confirmRefund(refundId, "re_1", "{}"));
    }

    @Test
    @DisplayName("1 row → tính lại status payment → completed kèm status mới của payment")
    void completesAndRecomputes() {
      PaymentRefund completed = refund(paymentId, "100.00", "test", RefundStatus.COMPLETED, "re_1");
      Payment partiallyRefunded = payment(PaymentStatus.PARTIALLY_REFUNDED, REF);
      when(refundRepo.confirmRefund(refundId, "re_1", "{}")).thenReturn(1);
      when(refundRepo.findById(refundId)).thenReturn(Optional.of(completed));
      when(paymentRepo.recomputeRefundStatus(paymentId)).thenReturn(1);
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(partiallyRefunded));

      ConfirmRefundResult result = service().confirmRefund(refundId, "re_1", "{}");

      assertThat(result)
          .isEqualTo(
              new ConfirmRefundResult(
                  refundId, "COMPLETED", paymentId, "PARTIALLY_REFUNDED", false));
    }

    @Test
    @DisplayName("1 row nhưng tính lại ra 0 row (dữ liệu bị sửa ngoài app) → IllegalStateException")
    void recomputeMissIsIllegalState() {
      PaymentRefund completed = refund(paymentId, "100.00", "test", RefundStatus.COMPLETED, "re_1");
      when(refundRepo.confirmRefund(refundId, "re_1", "{}")).thenReturn(1);
      when(refundRepo.findById(refundId)).thenReturn(Optional.of(completed));
      when(paymentRepo.recomputeRefundStatus(paymentId)).thenReturn(0);

      assertThat(confirm()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("0 row, đã COMPLETED với ĐÚNG mã → ack (alreadyCompleted), không tính lại")
    void replayWithSameReference() {
      PaymentRefund completed = refund(paymentId, "300.00", "test", RefundStatus.COMPLETED, "re_1");
      Payment refunded = payment(PaymentStatus.REFUNDED, REF);
      when(refundRepo.confirmRefund(refundId, "re_1", "{}")).thenReturn(0);
      when(refundRepo.findById(refundId)).thenReturn(Optional.of(completed));
      when(paymentRepo.findById(paymentId)).thenReturn(Optional.of(refunded));

      ConfirmRefundResult result = service().confirmRefund(refundId, "re_1", "{}");

      assertThat(result)
          .isEqualTo(new ConfirmRefundResult(refundId, "COMPLETED", paymentId, "REFUNDED", true));
      verify(paymentRepo, never()).recomputeRefundStatus(any());
    }

    @Test
    @DisplayName("0 row, COMPLETED với mã khác → 409; FAILED → 409")
    void otherReferenceOrFailed() {
      PaymentRefund otherRef =
          refund(paymentId, "100.00", "test", RefundStatus.COMPLETED, "re_other");
      PaymentRefund failed = refund(paymentId, "100.00", "test", RefundStatus.FAILED, null);
      when(refundRepo.confirmRefund(refundId, "re_1", "{}")).thenReturn(0);
      when(refundRepo.findById(refundId)).thenReturn(Optional.of(otherRef), Optional.of(failed));

      assertError(confirm(), ConflictException.class, ErrorCode.PAY_INVALID_STATE);
      assertError(confirm(), ConflictException.class, ErrorCode.PAY_INVALID_STATE);
      verify(paymentRepo, never()).recomputeRefundStatus(any());
    }

    @Test
    @DisplayName("0 row, refund không tồn tại → 404 PAY_REFUND_NOT_FOUND")
    void unknownRefund() {
      when(refundRepo.confirmRefund(refundId, "re_1", "{}")).thenReturn(0);
      when(refundRepo.findById(refundId)).thenReturn(Optional.empty());

      assertError(confirm(), NotFoundException.class, ErrorCode.PAY_REFUND_NOT_FOUND);
    }

    @Test
    @DisplayName("mã refund rỗng → 400, không chạm DB")
    void blankReference() {
      assertError(
          catchThrowable(() -> service().confirmRefund(refundId, "", "{}")),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(paymentRepo, refundRepo);
    }
  }
}
