package com.tungph7.learnpostgres.feature.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.ConcurrencyTestSupport;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.repository.PaymentRefundRepository;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

import tools.jackson.databind.ObjectMapper;

/**
 * AC-15, 16, 17 qua HTTP — và hai lần gửi lặp một refund lớn chạy chồng (AC-07).
 *
 * <p>Dạng "chắc chắn chồng nhau" của AC-15 / AC-17 — 2 giao dịch qua cổng khóa, gọi thẳng service —
 * đã có ở {@link PaymentServiceIntegrationTest}. Ở đây là bản ở quy mô của AC (10 webhook, 100
 * request) đi qua HTTP: chốt contract 200 / 201 dưới tranh chấp, và không request nào hỏng vì lý do
 * khác (409 / 500).
 *
 * <p>Một test không qua HTTP: lớp trigger của AC-16 tách riêng, ghi thẳng qua repository — qua HTTP
 * thì khóa của service che mất nó.
 *
 * <p>Hai cách tạo chồng lấn, chọn theo thứ đang đo (như F05 bài 05 §3.2):
 *
 * <ul>
 *   <li><b>Starting gate</b> ({@link ConcurrencyTestSupport}) cho AC-15, AC-17 — kết quả đúng phải
 *       giống nhau với MỌI cách xen kẽ: request tới khi bên thắng chưa commit thì đứng chờ khóa rồi
 *       đánh giá lại, tới sau thì thấy ngay kết quả — đường nào cũng ra cùng một response.
 *   <li><b>Cổng khóa</b> ({@link LockTestSupport}) cho AC-16 và refund gửi lặp — kết luận chỉ có
 *       nghĩa khi 2 request đã CÙNG qua bước tra key (chưa thấy refund nào) và cùng đứng chờ khóa
 *       payment. Tới lần lượt thì không đo được gì: request gửi lặp dừng ngay ở bước tra key, còn
 *       refund khác key thì SUM thấy refund đã commit mà không phải chờ khóa nào.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi request là một transaction riêng, commit
 * thật. Dữ liệu tự dọn — {@link #cleanup()}. Hikari profile test chỉ có 5 connection: 100 request
 * là 100 request ĐỢI nhau ở cửa pool, không phải 100 transaction chạy song song thật.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class PaymentConcurrencyIntegrationTest extends AbstractContainerBaseTest {

  /** AC-15: số webhook cùng mã giao dịch gửi chồng. */
  private static final int WEBHOOKS = 10;

  /** AC-17: số request tạo payment cùng key gửi chồng. */
  private static final int RETRIES = 100;

  private static final String PAYMENTS = "/api/v1/payments";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** Actor của bước dựng dữ liệu (gọi thẳng service) — request HTTP thì là {@code anonymous}. */
  private static final String ACTOR = "payment-conc";

  /**
   * Cổng khóa của 3 test refund. {@code FOR UPDATE} xung đột với mọi mức khóa row: với {@code FOR
   * NO KEY UPDATE} của {@code PaymentRepository.lockById} (request HTTP đứng chờ ở câu đó), và với
   * {@code FOR KEY SHARE} của bước kiểm FK khi INSERT refund (đường ghi thẳng qua repository đứng
   * chờ ở đó).
   */
  private static final String PAYMENT_ROW_LOCK =
      "SELECT 1 FROM app.payments WHERE id = ? FOR UPDATE";

  /** {@code detail} khi trigger V15 chặn TỔNG refund — service chặn một refund thì detail khác. */
  private static final String TRIGGER_EXCEEDS_DETAIL =
      "Tổng refund đang giữ tiền (PENDING + COMPLETED) vượt số tiền đã thu của payment.";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired PaymentService paymentService;
  @Autowired PaymentRefundRepository refundRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;

  private PaymentTestData data;

  @BeforeEach
  void setUp() {
    data = new PaymentTestData(jdbc, tx, "payment-conc-");
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

  // ================================================================
  // AC-15 — webhook gửi lặp
  // ================================================================

  /**
   * AC-15. UPDATE có điều kiện là trọng tài: đúng một request ra 1 row và capture; request tới khi
   * bên đó chưa commit thì chờ khóa row, rồi đánh giá lại {@code WHERE} trên row đã CAPTURED → 0
   * row → ack; request tới sau thì ra 0 row ngay. Đường nào cũng 200.
   *
   * <p>Mỗi request mang một payload riêng ({@code attempt}): payload trong DB phải là của đúng
   * request đã capture — 9 lần gửi lặp không ghi đè gì.
   */
  @Test
  @DisplayName(
      "AC-15: 10 webhook cùng mã gửi chồng → 10 × 200, đúng 1 capture + 9 ack; order PAID đúng"
          + " 1 lần")
  void tenWebhooksCaptureOnce() {
    UUID orderId = data.pendingOrder("300000.00");
    UUID paymentId =
        paymentService
            .createPaymentIntent(orderId, PaymentGateway.STRIPE, "pay-conc-webhook", ACTOR)
            .paymentId();
    AtomicInteger attempts = new AtomicInteger();

    List<Webhook> webhooks =
        ConcurrencyTestSupport.runConcurrently(
            WEBHOOKS,
            () -> {
              int attempt = attempts.incrementAndGet();
              return new Webhook(
                  attempt,
                  send(
                      post(PAYMENTS + "/{id}/confirm", paymentId)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              ("{\"paymentReference\":\"pi_conc_webhook\","
                                      + "\"gatewayResponse\":{\"attempt\":%d}}")
                                  .formatted(attempt))));
            });

    assertThat(webhooks).doesNotContainNull();
    List<Reply> replies = webhooks.stream().map(Webhook::reply).toList();
    assertThat(replies)
        .as("Có webhook không nhận 200: %s", describe(replies))
        .allMatch(reply -> reply.status() == 200);
    assertThat(replies)
        .extracting(reply -> read(reply, ConfirmPaymentResult.class))
        .allMatch(result -> result.paymentId().equals(paymentId))
        .allMatch(result -> "CAPTURED".equals(result.status()));
    List<Webhook> captures =
        webhooks.stream()
            .filter(webhook -> !read(webhook.reply(), ConfirmPaymentResult.class).alreadyCaptured())
            .toList();
    assertThat(captures).as("Số webhook thực sự capture").hasSize(1);

    assertThat(
            jdbc.queryForObject(
                "SELECT (gateway_response ->> 'attempt')::int FROM app.payments WHERE id = ?",
                Integer.class,
                paymentId))
        .as("Payload phải là của request đã capture")
        .isEqualTo(captures.getFirst().attempt());
    assertThat(data.paymentStatus(paymentId)).isEqualTo("CAPTURED");
    assertThat(data.orderStatus(orderId)).isEqualTo("PAID");
    assertThat(data.historyRows(orderId, "PAID")).isOne();
  }

  // ================================================================
  // AC-16 — 2 refund chạy chồng, tổng vượt số đã thu
  // ================================================================

  /**
   * AC-16. Mỗi refund 60% tự nó hợp lệ — service chỉ chặn được refund lớn hơn cả số đã thu. Tổng
   * 120% do trigger V15 chặn, và nó chỉ thấy được refund của bên kia nếu 2 bên XẾP HÀNG: bên sau
   * chờ khóa payment của bên trước ({@code lockById} ở service, {@code FOR NO KEY UPDATE} trong
   * trigger), bên trước commit, SUM của bên sau chạy trên snapshot mới (READ COMMITTED). Một trong
   * hai khóa là đủ; bỏ cả hai thì cả 2 refund cùng lọt (phản chứng ở {@code 05-tests.md}).
   *
   * <p>{@code detail} của bên thua là của nhánh trigger — chứng minh nó bị chặn ở tổng, không phải
   * ở bước kiểm từng refund.
   */
  @Test
  @DisplayName(
      "AC-16: 2 refund 60% cùng lúc, khác key → 1 × 201 + 1 × 409 PAY_REFUND_EXCEEDS_PAYMENT từ"
          + " trigger; chỉ 1 refund")
  void twoSixtyPercentRefundsOneWins() throws Exception {
    UUID paymentId = capturedPayment("pay-conc-ac16", "pi_conc_ac16");

    List<Reply> replies =
        raceThroughPaymentLock(
            paymentId,
            refundRequest(paymentId, "pay-conc-ac16-a", "180000"),
            refundRequest(paymentId, "pay-conc-ac16-b", "180000"));

    assertThat(replies)
        .extracting(Reply::status)
        .as("Status của 2 request: %s", describe(replies))
        .containsExactlyInAnyOrder(201, 409);
    Reply loser = replies.stream().filter(reply -> reply.status() == 409).findFirst().orElseThrow();
    assertThat(field(loser, "code")).isEqualTo("PAY_REFUND_EXCEEDS_PAYMENT");
    assertThat(field(loser, "detail")).isEqualTo(TRIGGER_EXCEEDS_DETAIL);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.payment_refunds WHERE payment_id = ?",
                Integer.class,
                paymentId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM app.payment_refunds WHERE payment_id = ?",
                BigDecimal.class,
                paymentId))
        .isEqualByComparingTo("180000");
  }

  /**
   * AC-16, riêng lớp trigger. Hai refund 60% ghi THẲNG qua repository — không qua {@code lockById}
   * của service, như một đường ghi "quên khóa". Cổng khóa giữ row payment nên cả hai dừng ở bước
   * kiểm FK của câu INSERT ({@code FOR KEY SHARE}); nhả ra thì cả hai cùng qua bước đó (KEY SHARE
   * không chặn nhau), rồi xếp hàng ở {@code FOR NO KEY UPDATE} trong trigger: bên sau chạy SUM sau
   * khi bên trước commit, thấy cả refund của bên trước.
   *
   * <p>Test HTTP ở trên không phân biệt được 2 lớp khóa: bỏ khóa trong trigger thì khóa của service
   * vẫn giữ đúng. Test này chốt câu "bảo đảm nằm ở trigger — đúng cả khi caller quên khóa".
   */
  @Test
  @DisplayName(
      "AC-16 (lớp trigger): 2 refund 60% ghi thẳng qua repository, không khóa ở app → 1 commit,"
          + " 1 PAY_REFUND_EXCEEDS_PAYMENT")
  void triggerAloneSerializesRefunds() throws Exception {
    UUID paymentId = capturedPayment("pay-conc-trigger", "pi_conc_trigger");

    List<Throwable> failures;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate = LockTestSupport.holdRowLock(dataSource, PAYMENT_ROW_LOCK, paymentId)) {
      Future<?> a = pool.submit(() -> insertRefundWithoutLock(paymentId, "pay-conc-trigger-a"));
      Future<?> b = pool.submit(() -> insertRefundWithoutLock(paymentId, "pay-conc-trigger-b"));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      gate.commit();
      failures = Stream.of(failureOf(a), failureOf(b)).filter(Objects::nonNull).toList();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures).as("Số lần ghi bị từ chối").hasSize(1);
    assertThat(failures.getFirst())
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining(
            "PAY_REFUND_EXCEEDS_PAYMENT: refunds 360000.00 > payment amount 300000.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(amount) FROM app.payment_refunds WHERE payment_id = ?",
                BigDecimal.class,
                paymentId))
        .isEqualByComparingTo("180000");
  }

  // ================================================================
  // AC-17 — 100 request tạo payment cùng key
  // ================================================================

  /**
   * AC-17. Bản kế hoạch đếm mọi exception là "existing" ("may get some conflicts"); ở đây 0 lỗi là
   * điều kiện đạt (bài 02 §10): {@code ON CONFLICT DO NOTHING} không chỉ định cột nuốt xung đột ở
   * cả 2 UNIQUE. Request cùng key tới khi request đầu chưa commit thì chờ ở UNIQUE của key, rồi đọc
   * lại theo key → EXISTING; request tới sau thì thấy ngay ở bước tra key.
   */
  @Test
  @DisplayName(
      "AC-17: 100 request tạo payment cùng key gửi chồng → 1 × 201 + 99 × 200, cùng một payment,"
          + " 1 row")
  void hundredRetriesCreateOnePayment() {
    UUID orderId = data.pendingOrder("300000.00");

    List<Reply> replies =
        ConcurrencyTestSupport.runConcurrently(
            RETRIES,
            () ->
                send(
                    post("/api/v1/orders/{orderId}/payments", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_KEY, "pay-conc-retry")
                        .content("{\"gateway\":\"STRIPE\"}")));

    assertThat(replies).doesNotContainNull();
    assertThat(replies)
        .as("Có request không nhận 201 / 200: %s", describe(replies))
        .allMatch(reply -> reply.status() == 201 || reply.status() == 200);
    List<Reply> created = replies.stream().filter(reply -> reply.status() == 201).toList();
    assertThat(created).as("Số request nhận 201").hasSize(1);
    PaymentIntentResult payment = read(created.getFirst(), PaymentIntentResult.class);
    assertThat(payment.kind()).isEqualTo(ResultKind.NEW);
    assertThat(created.getFirst().location()).endsWith(PAYMENTS + "/" + payment.paymentId());
    assertThat(replies)
        .filteredOn(reply -> reply.status() == 200)
        .hasSize(RETRIES - 1)
        .allMatch(reply -> reply.location() == null)
        .extracting(reply -> read(reply, PaymentIntentResult.class))
        .allMatch(result -> result.kind() == ResultKind.EXISTING)
        .allMatch(result -> result.paymentId().equals(payment.paymentId()));
    assertThat(data.count("SELECT count(*) FROM app.payments WHERE order_id = ?", orderId)).isOne();
  }

  // ================================================================
  // AC-07 — refund lớn gửi lặp, 2 lần chạy chồng
  // ================================================================

  /**
   * AC-07 khi 2 lần gửi lặp chạy chồng — và đúng kịch bản {@code 01-migration.md} §2.1, giờ đi qua
   * HTTP. Gửi lặp tuần tự không bao giờ chạm trigger (service tra key trước); chỉ 2 request cùng
   * qua bước tra key trước khi bên kia commit mới cùng tới câu INSERT. Bên sau bị {@code ON
   * CONFLICT (idempotency_key) DO NOTHING} bỏ qua → trigger AFTER không chạy → tra lại theo key →
   * EXISTING.
   *
   * <p>Refund 200 / 300 — lớn hơn nửa payment là điều kiện để thấy khác biệt: trigger BEFORE của
   * bản kế hoạch chạy cả cho row sắp bị bỏ qua, cộng 200 của bên trước + 200 của chính nó → 409.
   */
  @Test
  @DisplayName(
      "AC-07: 2 lần gửi lặp refund 200/300 chạy chồng → 1 × 201 + 1 × 200 EXISTING, không 409")
  void concurrentRetriesOfALargeRefundCreateOne() throws Exception {
    UUID paymentId = capturedPayment("pay-conc-ac07", "pi_conc_ac07");

    List<Reply> replies =
        raceThroughPaymentLock(
            paymentId,
            refundRequest(paymentId, "pay-conc-ac07-r", "200000"),
            refundRequest(paymentId, "pay-conc-ac07-r", "200000"));

    assertThat(replies)
        .extracting(Reply::status)
        .as("Status của 2 request: %s", describe(replies))
        .containsExactlyInAnyOrder(201, 200);
    List<RefundResult> results =
        replies.stream().map(reply -> read(reply, RefundResult.class)).toList();
    assertThat(results)
        .extracting(RefundResult::kind)
        .containsExactlyInAnyOrder(ResultKind.NEW, ResultKind.EXISTING);
    assertThat(results.get(0).refundId()).isEqualTo(results.get(1).refundId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.payment_refunds WHERE payment_id = ?",
                Integer.class,
                paymentId))
        .isOne();
  }

  // ================================================================
  // Helpers
  // ================================================================

  /** Payment 300.000 đã capture với mã {@code reference}, order đã PAID — dựng qua service. */
  private UUID capturedPayment(String key, String reference) {
    UUID paymentId =
        paymentService
            .createPaymentIntent(data.pendingOrder("300000.00"), PaymentGateway.STRIPE, key, ACTOR)
            .paymentId();
    paymentService.confirmPayment(paymentId, reference, null);
    return paymentId;
  }

  /**
   * INSERT refund 180.000 trong một transaction riêng (READ COMMITTED), KHÔNG khóa payment trước —
   * chỉ còn trigger của V15 kiểm tổng.
   */
  private void insertRefundWithoutLock(UUID paymentId, String key) {
    tx.executeWithoutResult(
        status ->
            refundRepository.insertIfAbsent(
                paymentId, key, new BigDecimal("180000"), "Hoan tien", ACTOR));
  }

  /** Exception của lần ghi; {@code null} nếu nó commit được. */
  private static Throwable failureOf(Future<?> write) throws Exception {
    try {
      write.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException ex) {
      return ex.getCause();
    }
  }

  private static RequestBuilder refundRequest(UUID paymentId, String key, String amount) {
    return post(PAYMENTS + "/{id}/refunds", paymentId)
        .contentType(MediaType.APPLICATION_JSON)
        .header(IDEMPOTENCY_KEY, key)
        .content("{\"amount\":%s,\"reason\":\"Hoan tien\"}".formatted(amount));
  }

  /**
   * Hai request chạy qua cổng khóa trên row payment: cổng khóa trước, đợi CẢ HAI request đứng chờ
   * khóa đó, rồi mới nhả. Lúc nhả, cả hai đã tra key xong và cùng đứng ở {@code lockById}.
   *
   * <p>Ngân sách connection (pool 5): cổng 1 + 2 request + 1 cho câu hỏi {@code pg_stat_activity}.
   */
  private List<Reply> raceThroughPaymentLock(
      UUID paymentId, RequestBuilder first, RequestBuilder second) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate = LockTestSupport.holdRowLock(dataSource, PAYMENT_ROW_LOCK, paymentId)) {
      Future<Reply> a = pool.submit(() -> send(first));
      Future<Reply> b = pool.submit(() -> send(second));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      gate.commit();
      return List.of(
          a.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS),
          b.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Gửi một request. Lỗi 4xx / 5xx là response bình thường; chỉ khi chính MockMvc ném (không có
   * response) thì {@code status = -1} và body là mô tả exception — để test in ra thay vì nuốt mất.
   */
  private Reply send(RequestBuilder request) {
    try {
      MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
      return new Reply(
          response.getStatus(),
          response.getContentAsString(),
          response.getHeader(HttpHeaders.LOCATION));
    } catch (Exception ex) {
      return new Reply(-1, ex.toString(), null);
    }
  }

  private <T> T read(Reply reply, Class<T> type) {
    return objectMapper.readValue(reply.body(), type);
  }

  /** Một field dạng chuỗi của body JSON — vd {@code code} / {@code detail} của ProblemDetail. */
  private String field(Reply reply, String name) {
    return objectMapper.readTree(reply.body()).path(name).asString();
  }

  /** Status + body của các response không phải 2xx — để assert in ra lý do khi đỏ. */
  private static String describe(List<Reply> replies) {
    return replies.stream()
        .filter(reply -> reply.status() < 200 || reply.status() >= 300)
        .map(reply -> reply.status() + " " + reply.body())
        .toList()
        .toString();
  }

  /** Một response: status, body, header {@code Location} ({@code null} nếu không có). */
  private record Reply(int status, String body, String location) {}

  /** Webhook thứ {@code attempt} và response của nó. */
  private record Webhook(int attempt, Reply reply) {}
}
