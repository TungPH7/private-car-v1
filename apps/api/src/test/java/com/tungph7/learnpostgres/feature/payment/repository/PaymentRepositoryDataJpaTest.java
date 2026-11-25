package com.tungph7.learnpostgres.feature.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;
import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus;
import com.tungph7.learnpostgres.feature.payment.domain.RefundStatus;

import tools.jackson.databind.json.JsonMapper;

/**
 * Slice test cho {@link PaymentRepository} trên Postgres thật, {@code ddl-auto: validate}.
 *
 * <p>Mọi đường ghi là native SQL, nên chỉ DB thật chứng minh được: {@code ON CONFLICT} nuốt đúng
 * những xung đột nào, câu UPDATE có điều kiện ra 0 hay 1 row, CHECK / UNIQUE nào chặn, JSONB đọc
 * lại ra sao.
 *
 * <p>Mọi thứ chạy trong MỘT transaction rollback cuối test. Hành vi khi nhiều transaction chạy
 * chồng (2 request cùng key, webhook gửi lặp) nằm ở test đồng thời của bài 05 và thí nghiệm của
 * {@code 01-migration.md §4.5}. Test chờ lỗi DB thì câu lỗi phải là thao tác DB cuối cùng: sau lỗi,
 * Postgres từ chối mọi câu tiếp theo của transaction đó.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class PaymentRepositoryDataJpaTest extends AbstractContainerBaseTest {

  private static final BigDecimal AMOUNT = new BigDecimal("300.00");
  private static final String ACTOR = "payment-test";
  private static final Pattern QUOTED_VALUE = Pattern.compile("'([A-Z_]+)'");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired PaymentRepository repository;
  @Autowired PaymentRefundRepository refundRepository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  /** 3 order PENDING của cùng một customer — payment cần order thật để thoả FK. */
  private List<UUID> orderIds;

  @BeforeEach
  void setUp() {
    UUID customerId =
        jdbc.queryForObject(
            """
            INSERT INTO app.customers (email, first_name, last_name)
            VALUES ('payment.repo@example.com', 'Payment', 'Probe')
            RETURNING id
            """,
            UUID.class);
    UUID addressId = OrderFixtures.insertShippingAddress(jdbc, customerId);
    orderIds = OrderFixtures.insertOrders(jdbc, addressId, 3);
  }

  private Payment newPayment(UUID orderId, String key, PaymentGateway gateway) {
    return repository.insertIfAbsent(orderId, key, AMOUNT, gateway.name(), ACTOR).getFirst();
  }

  private Payment newPayment(UUID orderId, String key) {
    return newPayment(orderId, key, PaymentGateway.STRIPE);
  }

  /**
   * Payment CAPTURED 300 — instance trả về đã detached (captureAtomic clear context), chỉ dùng id.
   */
  private Payment capturedPayment(String key) {
    Payment payment = newPayment(orderIds.getFirst(), key);
    repository.captureAtomic(payment.getId(), "pi_" + key, null);
    return payment;
  }

  private void completedRefund(UUID paymentId, String key, String amount) {
    PaymentRefund refund =
        refundRepository
            .insertIfAbsent(paymentId, key, new BigDecimal(amount), "probe", ACTOR)
            .getFirst();
    refundRepository.confirmRefund(refund.getId(), "re_" + key, null);
  }

  private String statusInDb(UUID paymentId) {
    return jdbc.queryForObject(
        "SELECT status FROM app.payments WHERE id = ?", String.class, paymentId);
  }

  // -----------------------------------------------------------------
  // Mapping
  // -----------------------------------------------------------------

  @Test
  @DisplayName("insertIfAbsent — payment PENDING, các DEFAULT của V15 đọc lại qua RETURNING *")
  void insertIfAbsentCreatesPendingPayment() {
    UUID orderId = orderIds.getFirst();

    List<Payment> inserted = repository.insertIfAbsent(orderId, "PAY-NEW", AMOUNT, "STRIPE", ACTOR);

    assertThat(inserted).hasSize(1);
    Payment p = inserted.getFirst();
    assertThat(p.getId().version()).isEqualTo(7); // app.gen_uuid_v7()
    assertThat(p.getOrderId()).isEqualTo(orderId);
    assertThat(p.getIdempotencyKey()).isEqualTo("PAY-NEW");
    assertThat(p.getAmount()).isEqualByComparingTo("300");
    assertThat(p.getCurrency()).isEqualTo("VND");
    assertThat(p.getGateway()).isEqualTo(PaymentGateway.STRIPE);
    assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.getPaymentReference()).isNull();
    assertThat(p.getGatewayResponse()).isNull();
    assertThat(p.getFailureReason()).isNull();
    assertThat(p.getAuthorizedAt()).isNull();
    assertThat(p.getCapturedAt()).isNull();
    assertThat(p.getFailedAt()).isNull();
    assertThat(p.getCreatedAt()).isNotNull().isEqualTo(p.getUpdatedAt());
    assertThat(p.getCreatedBy()).isEqualTo(ACTOR);
    assertThat(p.getUpdatedBy()).isEqualTo(ACTOR);
    assertThat(p.getVersion()).isZero();

    // Cùng row đọc lại qua SELECT của Hibernate (không qua RETURNING) — mapping khớp V15.
    em.clear();
    assertThat(repository.findById(p.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(p);
  }

  @Test
  @DisplayName("3 enum khớp miền giá trị CHECK của V15 — thêm giá trị ở một bên là test đỏ")
  void enumsMatchCheckConstraints() {
    assertThat(checkValues("ck_payments_status")).isEqualTo(names(PaymentStatus.values()));
    assertThat(checkValues("ck_payments_gateway")).isEqualTo(names(PaymentGateway.values()));
    assertThat(checkValues("ck_refunds_status")).isEqualTo(names(RefundStatus.values()));
  }

  private Set<String> checkValues(String constraint) {
    String definition =
        jdbc.queryForObject(
            """
            SELECT pg_get_constraintdef(oid) FROM pg_constraint
            WHERE conname = ? AND connamespace = 'app'::regnamespace
            """,
            String.class,
            constraint);
    return QUOTED_VALUE
        .matcher(definition)
        .results()
        .map(m -> m.group(1))
        .collect(Collectors.toSet());
  }

  private static Set<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
  }

  // -----------------------------------------------------------------
  // insertIfAbsent — idempotency + 1 payment chưa FAILED / order
  // -----------------------------------------------------------------

  @Test
  @DisplayName("retry cùng key, cùng order — list rỗng, không ghi gì")
  void insertIfAbsentSameKeyIsNoop() {
    UUID orderId = orderIds.getFirst();
    Payment first = newPayment(orderId, "PAY-RETRY");

    List<Payment> retry = repository.insertIfAbsent(orderId, "PAY-RETRY", AMOUNT, "STRIPE", ACTOR);

    assertThat(retry).isEmpty();
    assertThat(repository.findByIdempotencyKey("PAY-RETRY"))
        .get()
        .extracting(Payment::getId)
        .isEqualTo(first.getId());
    assertThat(repository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId)).hasSize(1);
  }

  /**
   * ON CONFLICT không so tham số: key đã có thì bỏ qua, dù request mới cho order khác, gateway
   * khác. Phát hiện "key bị dùng lại cho request khác" là việc của caller — so order / gateway của
   * row tìm được theo key.
   */
  @Test
  @DisplayName(
      "cùng key nhưng order + gateway khác — vẫn list rỗng, row theo key là của request đầu")
  void insertIfAbsentSameKeyOtherOrder() {
    Payment first = newPayment(orderIds.get(0), "PAY-REUSED");

    List<Payment> reused =
        repository.insertIfAbsent(orderIds.get(1), "PAY-REUSED", AMOUNT, "VNPAY", ACTOR);

    assertThat(reused).isEmpty();
    Payment byKey = repository.findByIdempotencyKey("PAY-REUSED").orElseThrow();
    assertThat(byKey.getId()).isEqualTo(first.getId());
    assertThat(byKey.getOrderId()).isEqualTo(orderIds.get(0));
    assertThat(byKey.getGateway()).isEqualTo(PaymentGateway.STRIPE);
  }

  /**
   * Canary cho {@code ON CONFLICT DO NOTHING} không chỉ định cột. Đổi thành {@code ON CONFLICT
   * (idempotency_key) DO NOTHING} thì test này ném 23505 {@code uq_payments_order_non_failed} — đã
   * chạy phản chứng ({@code 02-domain-and-repository.md §7.1}).
   */
  @Test
  @DisplayName("key mới cho order đã có payment chưa FAILED — list rỗng, KHÔNG ném 23505")
  void insertIfAbsentSwallowsNonFailedOrderConflict() {
    UUID orderId = orderIds.getFirst();
    newPayment(orderId, "PAY-FIRST");

    List<Payment> second = repository.insertIfAbsent(orderId, "PAY-SECOND", AMOUNT, "MOMO", ACTOR);

    assertThat(second).isEmpty();
    // Không có row nào mang key mới → caller biết xung đột nằm ở partial UNIQUE: order đã có
    // payment.
    assertThat(repository.findByIdempotencyKey("PAY-SECOND")).isEmpty();
    assertThat(repository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId)).hasSize(1);
  }

  @Test
  @DisplayName(
      "payment trước FAILED — order tạo được payment mới (FAILED nằm ngoài partial UNIQUE)")
  void insertIfAbsentAfterFailedPayment() {
    UUID orderId = orderIds.getFirst();
    Payment failed = newPayment(orderId, "PAY-1ST");
    repository.markFailed(failed.getId(), "Gateway timeout");

    List<Payment> retry = repository.insertIfAbsent(orderId, "PAY-2ND", AMOUNT, "VNPAY", ACTOR);

    assertThat(retry).hasSize(1);
    assertThat(repository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
        .extracting(Payment::getStatus)
        .containsExactlyInAnyOrder(PaymentStatus.FAILED, PaymentStatus.PENDING);
  }

  @Test
  @DisplayName("vi phạm CHECK không phải conflict — ON CONFLICT DO NOTHING không nuốt")
  void insertIfAbsentDoesNotSwallowCheckViolation() {
    UUID orderId = orderIds.getFirst();

    assertThatThrownBy(() -> repository.insertIfAbsent(orderId, "PAY-X", AMOUNT, "PAYPAL", ACTOR))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_payments_gateway");
  }

  /**
   * AC-09. Qua app không có đường nào ra số tiền ≤ 0: tiền copy từ {@code orders.total} (V12 giữ nó
   * ≥ 0) và service chặn order tổng 0 ({@code PAY_ORDER_NOT_PAYABLE}). Repository thì nhận mọi số —
   * đây là đường "bỏ qua app" của AC, và CHECK của V15 là chốt cuối.
   */
  @ParameterizedTest(name = "amount = {0}")
  @ValueSource(strings = {"0.00", "-300.00"})
  @DisplayName("AC-09: amount ≤ 0 ghi thẳng qua repository → ck_payments_amount_positive")
  void nonPositiveAmountIsRejectedByCheck(String amount) {
    UUID orderId = orderIds.getFirst();
    BigDecimal money = new BigDecimal(amount);

    assertThatThrownBy(
            () -> repository.insertIfAbsent(orderId, "PAY-AMOUNT", money, "STRIPE", ACTOR))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_payments_amount_positive");
  }

  /**
   * AC-13. {@code currency} không phải input của API, và không đường ghi nào của app đặt nó: {@code
   * insertIfAbsent} để DEFAULT {@code 'VND'} của V15 điền. Chỉ SQL thô mới chạm được CHECK này.
   */
  @Test
  @DisplayName("AC-13: currency chữ thường 'vnd' → ck_payments_currency_iso")
  void lowercaseCurrencyIsRejectedByCheck() {
    UUID orderId = orderIds.getFirst();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO app.payments (order_id, idempotency_key, amount, gateway, currency)
                    VALUES (?, 'PAY-CURRENCY', 300, 'STRIPE', 'vnd')
                    """,
                    orderId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_payments_currency_iso");
  }

  // -----------------------------------------------------------------
  // findByOrderId...
  // -----------------------------------------------------------------

  /**
   * created_at = {@code now()} của transaction, nên trong test (1 transaction) mọi payment trùng
   * created_at. Đẩy payment đầu lùi 1 giờ; 2 payment còn lại trùng nhau → thứ tự theo id giảm dần.
   * Thứ tự id lấy từ chính Postgres: Java so UUID theo long có dấu, Postgres so theo byte.
   */
  @Test
  @DisplayName(
      "payment của order — mới nhất trước, kể cả FAILED; created_at trùng thì id lớn trước")
  void paymentsOfOrderNewestFirst() {
    UUID orderId = orderIds.getFirst();
    Payment oldest = newPayment(orderId, "PAY-A");
    repository.markFailed(oldest.getId(), "declined");
    Payment tiedA = newPayment(orderId, "PAY-B");
    repository.markFailed(tiedA.getId(), "declined");
    Payment tiedB = newPayment(orderId, "PAY-C");
    jdbc.update(
        "UPDATE app.payments SET created_at = created_at - interval '1 hour' WHERE id = ?",
        oldest.getId());
    List<UUID> tiedByIdDesc =
        jdbc.queryForList(
            "SELECT id FROM app.payments WHERE id IN (?, ?) ORDER BY id DESC",
            UUID.class,
            tiedA.getId(),
            tiedB.getId());

    List<Payment> payments = repository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);

    assertThat(payments)
        .extracting(Payment::getId)
        .containsExactly(tiedByIdDesc.get(0), tiedByIdDesc.get(1), oldest.getId());
  }

  // -----------------------------------------------------------------
  // captureAtomic
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "PENDING → CAPTURED — 1 row, ghi reference + captured_at + gateway_response cùng câu")
  void captureFromPending() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-CAP");

    int updated =
        repository.captureAtomic(
            payment.getId(), "pi_cap_1", "{\"transactionId\":\"txn_1\",\"status\":\"succeeded\"}");

    assertThat(updated).isOne();
    Payment found = repository.findById(payment.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(found.getPaymentReference()).isEqualTo("pi_cap_1");
    assertThat(found.getCapturedAt()).isNotNull();
    assertThat(found.getAuthorizedAt()).isNull();
    assertThat(found.getFailedAt()).isNull();
    // jsonb lưu dạng đã chuẩn hoá: key sắp lại (key ngắn trước), khoảng trắng sau ':' và ','.
    assertThat(found.getGatewayResponse())
        .isEqualTo("{\"status\": \"succeeded\", \"transactionId\": \"txn_1\"}");
    assertThat(JSON.readTree(found.getGatewayResponse()))
        .isEqualTo(JSON.readTree("{\"transactionId\":\"txn_1\",\"status\":\"succeeded\"}"));
  }

  @Test
  @DisplayName("AUTHORIZED → CAPTURED — giữ authorized_at; gateway_response null vẫn hợp lệ")
  void captureFromAuthorized() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-AUTH");
    // F06 không có đường authorize — đặt trạng thái bằng SQL, kèm mốc mà CHECK V15 đòi.
    jdbc.update(
        "UPDATE app.payments SET status = 'AUTHORIZED', authorized_at = now() WHERE id = ?",
        payment.getId());

    assertThat(repository.captureAtomic(payment.getId(), "pi_auth_1", null)).isOne();

    Payment found = repository.findById(payment.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(found.getAuthorizedAt()).isNotNull();
    assertThat(found.getGatewayResponse()).isNull();
  }

  @Test
  @DisplayName("capture lần 2 (webhook gửi lặp, kể cả reference khác) — 0 row, giữ kết quả lần đầu")
  void secondCaptureIsNoop() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-TWICE");
    repository.captureAtomic(payment.getId(), "pi_first", null);

    assertThat(repository.captureAtomic(payment.getId(), "pi_second", null)).isZero();
    assertThat(repository.findById(payment.getId()).orElseThrow().getPaymentReference())
        .isEqualTo("pi_first");
  }

  @Test
  @DisplayName("payment FAILED hoặc không tồn tại — capture ra 0 row")
  void captureFailedOrUnknownPayment() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-LATE");
    repository.markFailed(payment.getId(), "declined");

    assertThat(repository.captureAtomic(payment.getId(), "pi_late", null)).isZero();
    assertThat(statusInDb(payment.getId())).isEqualTo("FAILED");
    assertThat(repository.captureAtomic(UUID.randomUUID(), "pi_ghost", null)).isZero();
  }

  @Test
  @DisplayName(
      "cùng reference: khác gateway được, cùng gateway → 23505 uq_payments_reference_gateway")
  void referenceIsUniquePerGateway() {
    Payment stripe = newPayment(orderIds.get(0), "PAY-S1", PaymentGateway.STRIPE);
    Payment vnpay = newPayment(orderIds.get(1), "PAY-V1", PaymentGateway.VNPAY);
    Payment stripeAgain = newPayment(orderIds.get(2), "PAY-S2", PaymentGateway.STRIPE);
    repository.captureAtomic(stripe.getId(), "14012345", null);

    assertThat(repository.captureAtomic(vnpay.getId(), "14012345", null)).isOne();
    assertThatThrownBy(() -> repository.captureAtomic(stripeAgain.getId(), "14012345", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_payments_reference_gateway");
  }

  /**
   * Mảng, và chuỗi JSON bị serialize thêm một lần — cả hai là jsonb hợp lệ, nhưng @> không khớp.
   */
  @ParameterizedTest
  @ValueSource(strings = {"[1, 2]", "\"{\\\"transactionId\\\": \\\"txn_1\\\"}\""})
  @DisplayName("gateway_response là JSON nhưng không phải object → 23514")
  void captureRejectsNonObjectJson(String gatewayResponse) {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-NOTOBJ");

    assertThatThrownBy(
            () -> repository.captureAtomic(payment.getId(), "pi_notobj", gatewayResponse))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_payments_gateway_response_object");
  }

  /**
   * JSON hỏng cũng ra DataIntegrityViolationException (22P02) — API phải validate trước khi gọi.
   */
  @Test
  @DisplayName("gateway_response không phải JSON → DataIntegrityViolationException (22P02)")
  void captureRejectsMalformedJson() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-BADJSON");

    assertThatThrownBy(() -> repository.captureAtomic(payment.getId(), "pi_bad", "{oops"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("invalid input syntax for type json");
  }

  /**
   * {@code clearAutomatically = true}: không có nó, {@code findById} trả lại đúng instance đang
   * cache (từ RETURNING * của insertIfAbsent) — vẫn mang PENDING dù DB đã là CAPTURED.
   */
  @Test
  @DisplayName("sau capture, findById đọc trạng thái MỚI chứ không phải instance cũ đang cache")
  void captureClearsPersistenceContext() {
    Payment managed = newPayment(orderIds.getFirst(), "PAY-CLEAR");
    assertThat(em.contains(managed)).isTrue();

    repository.captureAtomic(managed.getId(), "pi_clear", null);

    assertThat(em.contains(managed)).isFalse();
    assertThat(repository.findById(managed.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.CAPTURED);
  }

  // -----------------------------------------------------------------
  // markFailed
  // -----------------------------------------------------------------

  @Test
  @DisplayName("PENDING → FAILED — ghi failure_reason + failed_at; lần 2 ra 0 row")
  void markFailedFromPending() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-FAIL");

    assertThat(repository.markFailed(payment.getId(), "Gateway timeout")).isOne();
    assertThat(repository.markFailed(payment.getId(), "again")).isZero();

    Payment found = repository.findById(payment.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(found.getFailureReason()).isEqualTo("Gateway timeout");
    assertThat(found.getFailedAt()).isNotNull();
    assertThat(found.getCapturedAt()).isNull();
  }

  @Test
  @DisplayName("đã thu tiền thì không FAILED được — 0 row, vẫn CAPTURED")
  void markFailedAfterCaptureIsNoop() {
    Payment payment = capturedPayment("PAY-CAPFAIL");

    assertThat(repository.markFailed(payment.getId(), "too late")).isZero();
    assertThat(statusInDb(payment.getId())).isEqualTo("CAPTURED");
  }

  // -----------------------------------------------------------------
  // recomputeRefundStatus
  // -----------------------------------------------------------------

  /**
   * Canary cho nhánh "tổng COMPLETED = 0 → CAPTURED". Bản kế hoạch 2 nhánh ra PARTIALLY_REFUNDED ở
   * đây — đã chạy phản chứng ({@code 02-domain-and-repository.md §7.4}).
   */
  @Test
  @DisplayName("refund mới PENDING (chưa trả tiền) — recompute giữ CAPTURED")
  void recomputeWithoutCompletedRefundKeepsCaptured() {
    Payment payment = capturedPayment("PAY-RC0");
    refundRepository.insertIfAbsent(
        payment.getId(), "REF-RC0", new BigDecimal("100.00"), "probe", ACTOR);

    assertThat(repository.recomputeRefundStatus(payment.getId())).isOne();
    assertThat(statusInDb(payment.getId())).isEqualTo("CAPTURED");
  }

  @Test
  @DisplayName(
      "theo tổng COMPLETED: 100 → PARTIALLY, 150 → vẫn PARTIALLY, 300 → REFUNDED, rồi 0 row")
  void recomputeFollowsCompletedTotal() {
    Payment payment = capturedPayment("PAY-RC1");

    completedRefund(payment.getId(), "REF-RC1", "100.00");
    assertThat(repository.recomputeRefundStatus(payment.getId())).isOne();
    assertThat(statusInDb(payment.getId())).isEqualTo("PARTIALLY_REFUNDED");

    completedRefund(payment.getId(), "REF-RC2", "50.00");
    assertThat(repository.recomputeRefundStatus(payment.getId())).isOne();
    assertThat(statusInDb(payment.getId())).isEqualTo("PARTIALLY_REFUNDED");

    completedRefund(payment.getId(), "REF-RC3", "150.00");
    assertThat(repository.recomputeRefundStatus(payment.getId())).isOne();
    assertThat(statusInDb(payment.getId())).isEqualTo("REFUNDED");

    // REFUNDED nằm ngoài WHERE — terminal, không tính lại nữa.
    assertThat(repository.recomputeRefundStatus(payment.getId())).isZero();
  }

  @Test
  @DisplayName("payment chưa thu tiền — recompute ra 0 row, không đụng PENDING")
  void recomputeOnPendingPaymentIsNoop() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-RCP");

    assertThat(repository.recomputeRefundStatus(payment.getId())).isZero();
    assertThat(statusInDb(payment.getId())).isEqualTo("PENDING");
  }

  // -----------------------------------------------------------------
  // lockById
  // -----------------------------------------------------------------

  @Test
  @DisplayName("lockById — trả payment (row đã khóa); id không tồn tại → empty")
  void lockByIdReturnsPayment() {
    Payment payment = newPayment(orderIds.getFirst(), "PAY-LOCK");
    em.clear();

    assertThat(repository.lockById(payment.getId()))
        .get()
        .extracting(Payment::getIdempotencyKey)
        .isEqualTo("PAY-LOCK");
    assertThat(repository.lockById(UUID.randomUUID())).isEmpty();
  }

  /**
   * Canary cho bẫy ghi ở Javadoc của {@code lockById}: Hibernate trả instance đang có trong
   * persistence context, không chép giá trị của row vừa khóa đè lên. UPDATE bằng SQL thẳng (không
   * qua repository nên context không bị clear) mô phỏng giao dịch khác đã đổi trạng thái.
   */
  @Test
  @DisplayName("payment đã managed — lockById trả instance CŨ; clear trước thì đọc giá trị mới")
  void lockByIdReturnsManagedInstanceAsIs() {
    Payment managed = newPayment(orderIds.getFirst(), "PAY-STALE");
    jdbc.update(
        """
        UPDATE app.payments SET status = 'FAILED', failed_at = now(), failure_reason = 'x'
        WHERE id = ?
        """,
        managed.getId());

    Payment locked = repository.lockById(managed.getId()).orElseThrow();
    assertThat(locked).isSameAs(managed);
    assertThat(locked.getStatus()).isEqualTo(PaymentStatus.PENDING); // DB đã là FAILED

    em.clear();
    assertThat(repository.lockById(managed.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.FAILED);
  }
}
