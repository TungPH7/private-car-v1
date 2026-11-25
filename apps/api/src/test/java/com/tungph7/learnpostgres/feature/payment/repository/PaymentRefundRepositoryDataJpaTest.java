package com.tungph7.learnpostgres.feature.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;
import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.domain.RefundStatus;

import tools.jackson.databind.json.JsonMapper;

/**
 * Slice test cho {@link PaymentRefundRepository} trên Postgres thật — gồm cả trigger {@code
 * trg_payment_refunds_check_payment} (V15) mà mọi câu ghi refund đều đi qua.
 *
 * <p>Fixture: 1 payment CAPTURED 300 (đường thật: insertIfAbsent + captureAtomic) và 1 payment
 * PENDING. Trigger khóa payment và cộng tổng trong cùng transaction của test — hành vi khi 2 refund
 * chạy chồng nằm ở bài 05 và {@code 01-migration.md §2}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class PaymentRefundRepositoryDataJpaTest extends AbstractContainerBaseTest {

  private static final String ACTOR = "refund-test";
  private static final String REASON = "Khách đổi ý";

  @Autowired PaymentRefundRepository repository;
  @Autowired PaymentRepository paymentRepository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  /** CAPTURED, amount 300. */
  private UUID paymentId;

  /** PENDING — chưa thu tiền, không refund được. */
  private UUID pendingPaymentId;

  @BeforeEach
  void setUp() {
    UUID customerId =
        jdbc.queryForObject(
            """
            INSERT INTO app.customers (email, first_name, last_name)
            VALUES ('refund.repo@example.com', 'Refund', 'Probe')
            RETURNING id
            """,
            UUID.class);
    UUID addressId = OrderFixtures.insertShippingAddress(jdbc, customerId);
    List<UUID> orderIds = OrderFixtures.insertOrders(jdbc, addressId, 2);

    Payment captured =
        paymentRepository
            .insertIfAbsent(orderIds.get(0), "PAY-REF", new BigDecimal("300.00"), "STRIPE", ACTOR)
            .getFirst();
    paymentRepository.captureAtomic(captured.getId(), "pi_refund_probe", null);
    paymentId = captured.getId();

    pendingPaymentId =
        paymentRepository
            .insertIfAbsent(
                orderIds.get(1), "PAY-PENDING", new BigDecimal("300.00"), "VNPAY", ACTOR)
            .getFirst()
            .getId();
  }

  private List<PaymentRefund> insert(UUID payment, String key, String amount) {
    return repository.insertIfAbsent(payment, key, new BigDecimal(amount), REASON, ACTOR);
  }

  private PaymentRefund newRefund(String key, String amount) {
    return insert(paymentId, key, amount).getFirst();
  }

  // -----------------------------------------------------------------
  // insertIfAbsent
  // -----------------------------------------------------------------

  @Test
  @DisplayName("insertIfAbsent — refund PENDING, các DEFAULT của V15 đọc lại qua RETURNING *")
  void insertIfAbsentCreatesPendingRefund() {
    List<PaymentRefund> inserted = insert(paymentId, "REF-NEW", "100.00");

    assertThat(inserted).hasSize(1);
    PaymentRefund r = inserted.getFirst();
    assertThat(r.getId().version()).isEqualTo(7); // app.gen_uuid_v7()
    assertThat(r.getPaymentId()).isEqualTo(paymentId);
    assertThat(r.getIdempotencyKey()).isEqualTo("REF-NEW");
    assertThat(r.getAmount()).isEqualByComparingTo("100");
    assertThat(r.getReason()).isEqualTo(REASON);
    assertThat(r.getActor()).isEqualTo(ACTOR);
    assertThat(r.getStatus()).isEqualTo(RefundStatus.PENDING);
    assertThat(r.getRefundReference()).isNull();
    assertThat(r.getGatewayResponse()).isNull();
    assertThat(r.getCompletedAt()).isNull();
    assertThat(r.getRequestedAt()).isNotNull().isEqualTo(r.getUpdatedAt());

    em.clear();
    assertThat(repository.findById(r.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(r);
  }

  @Test
  @DisplayName("retry cùng key (kể cả amount khác) — list rỗng, refund cũ giữ nguyên")
  void insertIfAbsentSameKeyIsNoop() {
    PaymentRefund first = newRefund("REF-RETRY", "100.00");

    assertThat(insert(paymentId, "REF-RETRY", "50.00")).isEmpty();

    PaymentRefund byKey = repository.findByIdempotencyKey("REF-RETRY").orElseThrow();
    assertThat(byKey.getId()).isEqualTo(first.getId());
    assertThat(byKey.getAmount()).isEqualByComparingTo("100");
  }

  /**
   * Đang giữ 200/300, retry đúng key 200 → nếu bị kiểm như refund mới là 400 > 300. Trigger AFTER
   * không chạy cho row bị ON CONFLICT bỏ qua nên retry êm; trigger BEFORE của bản kế hoạch ném
   * PAY_REFUND_EXCEEDS_PAYMENT ở đây ({@code 01-migration.md §2.1}).
   */
  @Test
  @DisplayName("retry cùng key mà cộng thêm thì vượt — vẫn list rỗng, KHÔNG báo vượt")
  void retryThatWouldExceedIsStillNoop() {
    newRefund("REF-BIG", "200.00");

    assertThat(insert(paymentId, "REF-BIG", "200.00")).isEmpty();
    assertThat(repository.findByPaymentIdOrderByRequestedAtDescIdDesc(paymentId)).hasSize(1);
  }

  @Test
  @DisplayName("tổng đúng bằng amount qua được; thêm 0.01 → trigger PAY_REFUND_EXCEEDS_PAYMENT")
  void refundBeyondAmountIsRejected() {
    newRefund("REF-200", "200.00");
    newRefund("REF-100", "100.00"); // 300 / 300 — vẫn hợp lệ

    assertThatThrownBy(() -> insert(paymentId, "REF-CENT", "0.01"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("PAY_REFUND_EXCEEDS_PAYMENT");
  }

  @Test
  @DisplayName("refund payment chưa thu tiền — trigger PAY_INVALID_STATE, ON CONFLICT không nuốt")
  void refundOfUncapturedPaymentIsRejected() {
    assertThatThrownBy(() -> insert(pendingPaymentId, "REF-PENDING", "10.00"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("PAY_INVALID_STATE");
  }

  @Test
  @DisplayName("refund payment không tồn tại — 23503 payment_refunds_payment_id_fkey")
  void refundOfUnknownPaymentViolatesFk() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> insert(unknown, "REF-GHOST", "10.00"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("payment_refunds_payment_id_fkey");
  }

  /**
   * Hợp đồng ở Javadoc của repository: trigger V15 từ chối REPEATABLE READ — snapshot cố định làm
   * SUM không thấy refund vừa commit ({@code 01-migration.md §2.4}).
   *
   * <p>Test này cũng canh lỗi đã gặp ở bài 02 ({@code 02-domain-and-repository.md §8.2}): với mã
   * 0A000, HikariCP loại connection khỏi pool, và test đỏ ở bước rollback cuối test ({@code Unable
   * to rollback against JDBC Connection}) — không phải ở assert.
   */
  @Test
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @DisplayName("ghi refund dưới REPEATABLE READ — trigger từ chối với SQLSTATE 25000")
  void repeatableReadIsRejected() {
    assertThat(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class))
        .isEqualTo("repeatable read");

    assertThatThrownBy(() -> insert(paymentId, "REF-RR", "10.00"))
        .rootCause()
        .hasMessageContaining("cannot run under REPEATABLE READ")
        .isInstanceOfSatisfying(
            SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("25000"));
  }

  // -----------------------------------------------------------------
  // confirmRefund
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "PENDING → COMPLETED — 1 row, ghi reference + completed_at + gateway_response cùng câu")
  void confirmRefundCompletesPendingRefund() {
    PaymentRefund refund = newRefund("REF-OK", "100.00");

    int updated =
        repository.confirmRefund(
            refund.getId(), "re_ok_1", "{\"refundId\":\"re_ok_1\",\"status\":\"succeeded\"}");

    assertThat(updated).isOne();
    PaymentRefund found = repository.findById(refund.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(found.getRefundReference()).isEqualTo("re_ok_1");
    assertThat(found.getCompletedAt()).isNotNull();
    JsonMapper json = JsonMapper.builder().build();
    assertThat(json.readTree(found.getGatewayResponse()))
        .isEqualTo(json.readTree("{\"refundId\":\"re_ok_1\",\"status\":\"succeeded\"}"));
  }

  @Test
  @DisplayName("confirm lần 2 hoặc refund không tồn tại — 0 row, giữ kết quả lần đầu")
  void secondConfirmIsNoop() {
    PaymentRefund refund = newRefund("REF-TWICE", "100.00");
    repository.confirmRefund(refund.getId(), "re_first", null);

    assertThat(repository.confirmRefund(refund.getId(), "re_second", null)).isZero();
    assertThat(repository.findById(refund.getId()).orElseThrow().getRefundReference())
        .isEqualTo("re_first");
    assertThat(repository.confirmRefund(UUID.randomUUID(), "re_ghost", null)).isZero();
  }

  @Test
  @DisplayName("refund_reference đã thuộc refund khác — 23505 payment_refunds_reference_unique")
  void refundReferenceIsUnique() {
    PaymentRefund first = newRefund("REF-R1", "100.00");
    PaymentRefund second = newRefund("REF-R2", "100.00");
    repository.confirmRefund(first.getId(), "re_dup", null);

    assertThatThrownBy(() -> repository.confirmRefund(second.getId(), "re_dup", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("payment_refunds_reference_unique");
  }

  @Test
  @DisplayName("gateway_response là JSON nhưng không phải object → 23514")
  void confirmRefundRejectsNonObjectJson() {
    PaymentRefund refund = newRefund("REF-NOTOBJ", "100.00");

    assertThatThrownBy(() -> repository.confirmRefund(refund.getId(), "re_notobj", "[1, 2]"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_refunds_gateway_response_object");
  }

  @Test
  @DisplayName("sau confirm, findById đọc trạng thái MỚI chứ không phải instance cũ đang cache")
  void confirmRefundClearsPersistenceContext() {
    PaymentRefund managed = newRefund("REF-CLEAR", "100.00");
    assertThat(em.contains(managed)).isTrue();

    repository.confirmRefund(managed.getId(), "re_clear", null);

    assertThat(em.contains(managed)).isFalse();
    assertThat(repository.findById(managed.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.COMPLETED);
  }

  // -----------------------------------------------------------------
  // findByPaymentId...
  // -----------------------------------------------------------------

  /** Cùng cách với {@code PaymentRepositoryDataJpaTest.paymentsOfOrderNewestFirst}. */
  @Test
  @DisplayName("refund của payment — mới nhất trước; requested_at trùng thì id lớn trước")
  void refundsOfPaymentNewestFirst() {
    PaymentRefund oldest = newRefund("REF-A", "50.00");
    PaymentRefund tiedA = newRefund("REF-B", "50.00");
    PaymentRefund tiedB = newRefund("REF-C", "50.00");
    jdbc.update(
        """
        UPDATE app.payment_refunds SET requested_at = requested_at - interval '1 hour'
        WHERE id = ?
        """,
        oldest.getId());
    List<UUID> tiedByIdDesc =
        jdbc.queryForList(
            "SELECT id FROM app.payment_refunds WHERE id IN (?, ?) ORDER BY id DESC",
            UUID.class,
            tiedA.getId(),
            tiedB.getId());

    assertThat(repository.findByPaymentIdOrderByRequestedAtDescIdDesc(paymentId))
        .extracting(PaymentRefund::getId)
        .containsExactly(tiedByIdDesc.get(0), tiedByIdDesc.get(1), oldest.getId());
    assertThat(repository.findByPaymentIdOrderByRequestedAtDescIdDesc(pendingPaymentId)).isEmpty();
  }
}
