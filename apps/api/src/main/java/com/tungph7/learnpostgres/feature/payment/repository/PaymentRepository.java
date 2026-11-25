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

import com.tungph7.learnpostgres.feature.payment.domain.Payment;

/**
 * Repository của {@code app.payments}.
 *
 * <p>Mọi đường GHI là native SQL, mỗi đường đúng một câu lệnh tự kiểm điều kiện của nó — không có
 * khe giữa "kiểm" và "ghi":
 *
 * <ul>
 *   <li>tạo: {@link #insertIfAbsent} — {@code INSERT ... ON CONFLICT DO NOTHING};
 *   <li>đổi trạng thái: {@link #captureAtomic}, {@link #markFailed}, {@link #recomputeRefundStatus}
 *       — {@code UPDATE ... WHERE status IN (...)}. 0 row nghĩa là trạng thái đã khác (giao dịch
 *       khác đổi trước, hoặc vốn không hợp lệ) — caller đọc lại rồi mới phân loại.
 * </ul>
 *
 * <p>Method ghi / khóa phải chạy trong transaction <b>read-write</b>: Postgres từ chối {@code
 * INSERT}, {@code UPDATE}, {@code SELECT ... FOR NO KEY UPDATE} trong transaction read-only
 * (SQLSTATE 25006). Service gọi chúng từ method {@code @Transactional} thường.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  /** Tra theo idempotency key — UNIQUE nên tối đa 1 row. */
  Optional<Payment> findByIdempotencyKey(String idempotencyKey);

  /**
   * Payment của một order, mới nhất trước — kể cả FAILED.
   *
   * <p>{@code id} (UUIDv7) phá hoà khi {@code created_at} trùng: DEFAULT {@code now()} là thời điểm
   * bắt đầu TRANSACTION, nên mọi payment tạo trong cùng một transaction có cùng {@code created_at}.
   */
  List<Payment> findByOrderIdOrderByCreatedAtDescIdDesc(UUID orderId);

  // -----------------------------------------------------------------
  // Tạo — idempotent
  // -----------------------------------------------------------------

  /**
   * Tạo payment PENDING nếu chưa có. Idempotent.
   *
   * <p><b>{@code ON CONFLICT DO NOTHING} không chỉ định cột — có chủ ý.</b> {@code payments} có 2
   * UNIQUE đụng được lúc INSERT: {@code payments_idempotency_key_unique} và partial {@code
   * uq_payments_order_non_failed}. {@code ON CONFLICT (idempotency_key)} chỉ nuốt xung đột của cái
   * đầu; cái sau vẫn ném 23505 — với request khác key cho cùng order, và CÓ LÚC với chính 2 request
   * giống hệt nhau chạy chồng (bài 01 đo được 13/50 vòng với 50 client). Không chỉ định cột thì
   * nuốt cả hai, caller tra lại theo key để biết là trường hợp nào:
   *
   * <ul>
   *   <li>có row với key, cùng order + gateway → request đã xử lý trước đó (retry) → EXISTING;
   *   <li>có row với key nhưng khác order / gateway → key bị dùng lại cho request khác → từ chối;
   *   <li>không có row với key → order đã có payment chưa FAILED → 409.
   * </ul>
   *
   * <p>Vi phạm CHECK (gateway lạ, key rỗng, {@code amount <= 0}) không phải conflict — vẫn ném lỗi.
   *
   * <p>{@code id}, {@code status}, {@code currency}, timestamp, {@code version} để DB DEFAULT:
   * payment luôn bắt đầu PENDING, và F06 chỉ thu VND ({@code orders} không có cột tiền tệ). {@code
   * RETURNING *} trả row đúng như DB đã ghi.
   *
   * @param gateway tên {@code PaymentGateway} ({@code gateway.name()}). Enum truyền thẳng vào
   *     native query được bind theo ordinal, không theo tên
   * @param actor ghi vào {@code created_by} / {@code updated_by}
   * @return list 1 phần tử là payment vừa tạo; list rỗng nghĩa là có xung đột — xem 3 trường hợp ở
   *     trên
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO app.payments
              (order_id, idempotency_key, amount, gateway, created_by, updated_by)
          VALUES
              (:orderId, :idempotencyKey, :amount, :gateway, :actor, :actor)
          ON CONFLICT DO NOTHING
          RETURNING *
          """,
      nativeQuery = true)
  List<Payment> insertIfAbsent(
      @Param("orderId") UUID orderId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("amount") BigDecimal amount,
      @Param("gateway") String gateway,
      @Param("actor") String actor);

  // -----------------------------------------------------------------
  // Đổi trạng thái — UPDATE có điều kiện
  // -----------------------------------------------------------------

  /**
   * PENDING / AUTHORIZED → CAPTURED. Race-safe: N webhook chạy chồng thì đúng 1 câu ra 1 row — câu
   * thứ hai chờ khóa row, rồi đánh giá lại {@code WHERE} trên phiên bản mới (đã CAPTURED) → 0 row.
   *
   * <p>Ghi đủ dữ liệu đi kèm trong CÙNG câu: {@code payment_reference} ({@code
   * ck_payments_captured_has_ref}), {@code captured_at} ({@code
   * ck_payments_captured_at_with_status}), {@code gateway_response}. {@code authorized_at} giữ
   * nguyên.
   *
   * <p>0 row KHÔNG có nghĩa là "đã capture": payment có thể FAILED, đã refund, hoặc không tồn tại.
   * Caller đọc lại: đã thu tiền với đúng reference → idempotent; còn lại → 409.
   *
   * <p>Lỗi từ DB, đều là {@code DataIntegrityViolationException}: reference đã gắn với payment khác
   * cùng gateway → 23505 {@code uq_payments_reference_gateway}; {@code gatewayResponse} là JSON
   * nhưng không phải object → 23514 {@code ck_payments_gateway_response_object}; không phải JSON →
   * 22P02. Tầng API nên kiểm JSON trước khi gọi.
   *
   * <p>{@code flushAutomatically} + {@code clearAutomatically} như {@code
   * OrderRepository.transitionStatus}: {@code findById} kế tiếp đọc trạng thái mới, không phải
   * instance cũ đang cache.
   *
   * @param gatewayResponse JSON object dạng text; {@code null} nếu không có
   * @return 1 nếu capture được; 0 nếu payment không tồn tại hoặc không còn PENDING / AUTHORIZED
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.payments
          SET status            = 'CAPTURED',
              payment_reference = :reference,
              gateway_response  = CAST(:gatewayResponse AS jsonb),
              captured_at       = now()
          WHERE id = :paymentId
            AND status IN ('PENDING', 'AUTHORIZED')
          """,
      nativeQuery = true)
  int captureAtomic(
      @Param("paymentId") UUID paymentId,
      @Param("reference") String reference,
      @Param("gatewayResponse") String gatewayResponse);

  /**
   * PENDING / AUTHORIZED → FAILED, ghi {@code failure_reason} + {@code failed_at} ({@code
   * ck_payments_failed_at_with_status}) trong cùng câu.
   *
   * <p>FAILED nằm ngoài predicate của {@code uq_payments_order_non_failed} → order tạo được payment
   * mới (với idempotency key mới).
   *
   * @return 1 nếu chuyển được; 0 nếu payment không tồn tại hoặc không còn PENDING / AUTHORIZED (kể
   *     cả khi đã FAILED từ trước — caller đọc lại để coi là idempotent)
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.payments
          SET status         = 'FAILED',
              failure_reason = :reason,
              failed_at      = now()
          WHERE id = :paymentId
            AND status IN ('PENDING', 'AUTHORIZED')
          """,
      nativeQuery = true)
  int markFailed(@Param("paymentId") UUID paymentId, @Param("reason") String reason);

  /**
   * Tính lại status của payment đã thu tiền từ tổng refund COMPLETED: 0 → CAPTURED, nhỏ hơn {@code
   * amount} → PARTIALLY_REFUNDED, bằng {@code amount} → REFUNDED.
   *
   * <p>Status là HÀM của {@code (amount, tổng COMPLETED)} — gọi lại bao nhiêu lần cũng ra cùng kết
   * quả. Không lùi được: refund COMPLETED là terminal nên tổng chỉ tăng. Refund PENDING đang giữ
   * tiền (trigger V15 có tính) nhưng chưa trả lại → không làm đổi status.
   *
   * <p>Bản kế hoạch chỉ có 2 nhánh ({@code >= amount} → REFUNDED, còn lại → PARTIALLY_REFUNDED):
   * gọi khi chưa có refund COMPLETED nào là payment CAPTURED thành PARTIALLY_REFUNDED dù chưa hoàn
   * đồng nào.
   *
   * @return 1 nếu payment đang CAPTURED / PARTIALLY_REFUNDED (status được ghi lại, có thể không
   *     đổi); 0 nếu payment không tồn tại hoặc ở trạng thái khác
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.payments p
          SET status = CASE
                           WHEN r.completed = 0        THEN 'CAPTURED'
                           WHEN r.completed < p.amount THEN 'PARTIALLY_REFUNDED'
                           ELSE 'REFUNDED'
                       END
          FROM (SELECT COALESCE(SUM(amount), 0) AS completed
                FROM app.payment_refunds
                WHERE payment_id = :paymentId
                  AND status = 'COMPLETED') r
          WHERE p.id = :paymentId
            AND p.status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
          """,
      nativeQuery = true)
  int recomputeRefundStatus(@Param("paymentId") UUID paymentId);

  // -----------------------------------------------------------------
  // Khóa
  // -----------------------------------------------------------------

  /**
   * Đọc payment và khóa row tới hết transaction — {@code requestRefund} (bài 03) khóa trước khi
   * kiểm status và ghi refund.
   *
   * <p><b>{@code FOR NO KEY UPDATE}, không phải {@code FOR UPDATE}.</b> Đây là mức khóa trigger
   * {@code trg_payment_refunds_check_payment} xin, và là thứ Hibernate sinh cho {@code
   * PESSIMISTIC_WRITE} trên PostgreSQL. Nó xung đột với chính nó và với mọi UPDATE của payment
   * (capture, markFailed, recompute) → các refund của cùng payment xếp hàng tại đây; nó không chặn
   * {@code FOR KEY SHARE} mà FK {@code payment_refunds_payment_id_fkey} xin.
   *
   * <p><b>Bẫy:</b> payment đã nằm trong persistence context (đã đọc trước đó trong cùng
   * transaction) thì Hibernate trả lại đúng instance cũ — row vẫn bị khóa, nhưng field mang giá trị
   * lúc đọc trước. Gọi method này trước mọi lần đọc payment đó trong transaction, hoặc clear
   * persistence context trước.
   */
  @Query(
      value = "SELECT * FROM app.payments WHERE id = :paymentId FOR NO KEY UPDATE",
      nativeQuery = true)
  Optional<Payment> lockById(@Param("paymentId") UUID paymentId);
}
