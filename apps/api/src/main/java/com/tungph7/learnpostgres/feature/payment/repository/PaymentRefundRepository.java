package com.tungph7.learnpostgres.feature.payment.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;

/**
 * Repository của {@code app.payment_refunds}.
 *
 * <p>Hai rule "chỉ refund payment đã thu tiền" và "tổng refund đang giữ tiền ≤ {@code
 * payment.amount}" nằm ở trigger {@code trg_payment_refunds_check_payment} (V15) — đúng với mọi
 * đường ghi, kể cả khi caller quên khóa payment. Repository không lặp lại hai kiểm tra đó.
 *
 * <p>Method ghi phải chạy trong transaction read-write, và KHÔNG dưới REPEATABLE READ: trigger từ
 * chối isolation đó (SQLSTATE 25000 — {@code 01-migration.md §2.4}).
 */
@Repository
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {

  /** Tra theo idempotency key — UNIQUE nên tối đa 1 row. */
  Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

  /**
   * Refund của một payment, mới nhất trước — chạy trên {@code idx_refunds_payment_id}. {@code id}
   * phá hoà khi {@code requested_at} trùng (cùng transaction — xem {@code
   * PaymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc}).
   */
  List<PaymentRefund> findByPaymentIdOrderByRequestedAtDescIdDesc(UUID paymentId);

  /**
   * Tạo refund PENDING nếu idempotency key chưa có. Idempotent.
   *
   * <p><b>{@code ON CONFLICT (idempotency_key)} — CÓ chỉ định cột</b>, khác {@code
   * PaymentRepository.insertIfAbsent}: lúc INSERT, {@code refund_reference} còn NULL nên {@code
   * idempotency_key} là UNIQUE duy nhất đụng được. Chỉ định cột thì một xung đột ngoài dự kiến (nếu
   * sau này có) vẫn ném lỗi thay vì bị nuốt im lặng.
   *
   * <p>Trigger AFTER chỉ chạy cho row THẬT SỰ được ghi: retry đúng key bị bỏ qua êm kể cả khi cộng
   * thêm lần nữa thì vượt ({@code 01-migration.md §2.1}). Row mới mà vượt tổng, hoặc payment chưa
   * thu tiền → trigger raise {@code PAY_REFUND_EXCEEDS_PAYMENT} / {@code PAY_INVALID_STATE}
   * (23514); payment không tồn tại → 23503 {@code payment_refunds_payment_id_fkey}. Cả hai là
   * {@code DataIntegrityViolationException} — ON CONFLICT không nuốt.
   *
   * <p>{@code id}, {@code status}, {@code requested_at}, {@code updated_at} để DB DEFAULT. {@code
   * RETURNING *} trả row đúng như DB đã ghi.
   *
   * @return list 1 phần tử là refund vừa tạo; list rỗng nghĩa là key đã có → caller đọc bằng {@link
   *     #findByIdempotencyKey}
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO app.payment_refunds
              (payment_id, idempotency_key, amount, reason, actor)
          VALUES
              (:paymentId, :idempotencyKey, :amount, :reason, :actor)
          ON CONFLICT (idempotency_key) DO NOTHING
          RETURNING *
          """,
      nativeQuery = true)
  List<PaymentRefund> insertIfAbsent(
      @Param("paymentId") UUID paymentId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("amount") BigDecimal amount,
      @Param("reason") String reason,
      @Param("actor") String actor);

  /**
   * PENDING → COMPLETED, ghi {@code refund_reference} ({@code ck_refunds_completed_has_ref}) +
   * {@code completed_at} ({@code ck_refunds_completed_at_with_status}) + {@code gateway_response}
   * trong cùng câu. Race-safe như {@code PaymentRepository.captureAtomic}.
   *
   * <p>Câu UPDATE đổi {@code status} nên trigger kiểm refund vẫn chạy: nó khóa row payment ({@code
   * FOR NO KEY UPDATE}) và cộng lại tổng — PENDING → COMPLETED không đổi tổng đang giữ nên luôn
   * qua. Nhờ vậy {@code recomputeRefundStatus} ngay sau đó trong cùng transaction không phải chờ
   * khóa.
   *
   * <p>Lỗi từ DB, đều là {@code DataIntegrityViolationException}: {@code refund_reference} đã thuộc
   * refund khác → 23505 {@code payment_refunds_reference_unique}; {@code gatewayResponse} không
   * phải JSON object → 23514 {@code ck_refunds_gateway_response_object} (không phải JSON → 22P02).
   *
   * @return 1 nếu chuyển được; 0 nếu refund không tồn tại hoặc không còn PENDING
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.payment_refunds
          SET status           = 'COMPLETED',
              refund_reference = :reference,
              gateway_response = CAST(:gatewayResponse AS jsonb),
              completed_at     = now()
          WHERE id = :refundId
            AND status = 'PENDING'
          """,
      nativeQuery = true)
  int confirmRefund(
      @Param("refundId") UUID refundId,
      @Param("reference") String reference,
      @Param("gatewayResponse") String gatewayResponse);
}
