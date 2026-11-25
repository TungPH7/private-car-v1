package com.tungph7.learnpostgres.feature.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một lần hoàn tiền (một phần hoặc toàn bộ) của payment — {@code app.payment_refunds} (V15).
 *
 * <p>KHÔNG extend {@code BaseEntity}: bảng không có {@code created_at / created_by / updated_by /
 * version} — {@code requested_at}, {@code actor}, {@code updated_at} thay vai trò đó. Ghép
 * BaseEntity vào thì {@code ddl-auto: validate} fail vì thiếu cột (cùng lý do với {@code
 * CouponRedemption} của F04).
 *
 * <p>Cùng nguyên tắc với {@link Payment}: không constructor public, không setter. Refund chỉ sinh
 * ra qua {@code PaymentRefundRepository.insertIfAbsent} ({@code INSERT ... ON CONFLICT DO NOTHING})
 * và đổi trạng thái qua {@code confirmRefund}. {@code id}, {@code requested_at}, {@code updated_at}
 * do DB điền (DEFAULT, trigger {@code set_updated_at}) — entity không sinh giá trị nào.
 */
@Entity
@Table(name = "payment_refunds", schema = "app")
public class PaymentRefund {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
  private String idempotencyKey;

  @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(name = "reason", nullable = false, updatable = false, length = 255)
  private String reason;

  @Column(name = "actor", nullable = false, updatable = false, length = 64)
  private String actor;

  @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
  private Instant requestedAt;

  // -----------------------------------------------------------------
  // State — CHỈ ĐỌC, ghi qua PaymentRefundRepository.confirmRefund
  // -----------------------------------------------------------------

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, insertable = false, updatable = false, length = 16)
  private RefundStatus status;

  @Column(name = "refund_reference", insertable = false, updatable = false, length = 80)
  private String refundReference;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "gateway_response", insertable = false, updatable = false)
  private String gatewayResponse;

  @Column(name = "completed_at", insertable = false, updatable = false)
  private Instant completedAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  protected PaymentRefund() {
    // for JPA — tạo refund qua PaymentRefundRepository.insertIfAbsent
  }

  // -----------------------------------------------------------------
  // Getters only
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getReason() {
    return reason;
  }

  public String getActor() {
    return actor;
  }

  /** {@code now()} của transaction tạo refund (DEFAULT). */
  public Instant getRequestedAt() {
    return requestedAt;
  }

  /** Chỉ đọc. Đổi qua {@code PaymentRefundRepository.confirmRefund}. */
  public RefundStatus getStatus() {
    return status;
  }

  /** Mã refund do gateway cấp — bắt buộc khi COMPLETED, unique toàn bảng. */
  public String getRefundReference() {
    return refundReference;
  }

  /** Payload gốc của gateway, JSON object dạng text theo cách {@code jsonb} lưu. */
  public String getGatewayResponse() {
    return gatewayResponse;
  }

  /** Có khi và chỉ khi COMPLETED. */
  public Instant getCompletedAt() {
    return completedAt;
  }

  /** Lần đổi trạng thái cuối (trigger). Refund FAILED không có cột thời điểm riêng — là mốc này. */
  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
