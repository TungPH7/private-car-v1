package com.tungph7.learnpostgres.feature.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tungph7.learnpostgres.common.domain.BaseEntity;

/**
 * Thanh toán của một order — {@code app.payments} (V15).
 *
 * <p>Hai quyết định khác entity thường gặp:
 *
 * <ul>
 *   <li><b>Không có constructor public, không setter.</b> Payment chỉ sinh ra qua {@code
 *       PaymentRepository.insertIfAbsent} ({@code INSERT ... ON CONFLICT DO NOTHING}). {@code
 *       save()} một instance mới là đường tạo payment thứ hai không idempotent: client retry cùng
 *       key sẽ nhận 23505 thay vì payment cũ. Entity này chỉ để ĐỌC — JPA không INSERT, không
 *       UPDATE bảng payments.
 *   <li><b>Trạng thái chỉ đọc.</b> {@code status}, 3 mốc thời gian, {@code payment_reference},
 *       {@code gateway_response}, {@code failure_reason} đổi cùng nhau trong MỘT câu UPDATE có điều
 *       kiện của repository — map {@code insertable = false, updatable = false} như {@code
 *       Order.status} (F05): instance cũ trong persistence context không có đường nào ghi đè trạng
 *       thái mới. Các cột còn lại ghi một lần lúc tạo ({@code updatable = false}).
 * </ul>
 *
 * <p>Các câu UPDATE native không tăng {@code version} (như F05): {@code version} chỉ canh cột do
 * JPA ghi, và ở đây không có cột nào như vậy.
 */
@Entity
@Table(name = "payments", schema = "app")
public class Payment extends BaseEntity {

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
  private String idempotencyKey;

  @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "gateway", nullable = false, updatable = false, length = 32)
  private PaymentGateway gateway;

  // -----------------------------------------------------------------
  // State machine — CHỈ ĐỌC, ghi qua câu UPDATE có điều kiện của PaymentRepository
  // -----------------------------------------------------------------

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, insertable = false, updatable = false, length = 24)
  private PaymentStatus status;

  @Column(name = "payment_reference", insertable = false, updatable = false, length = 80)
  private String paymentReference;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "gateway_response", insertable = false, updatable = false)
  private String gatewayResponse;

  @Column(name = "failure_reason", insertable = false, updatable = false)
  private String failureReason;

  @Column(name = "authorized_at", insertable = false, updatable = false)
  private Instant authorizedAt;

  @Column(name = "captured_at", insertable = false, updatable = false)
  private Instant capturedAt;

  @Column(name = "failed_at", insertable = false, updatable = false)
  private Instant failedAt;

  protected Payment() {
    // for JPA — tạo payment qua PaymentRepository.insertIfAbsent
  }

  // -----------------------------------------------------------------
  // Getters only
  // -----------------------------------------------------------------

  public UUID getOrderId() {
    return orderId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  /** ISO 4217, luôn 3 chữ hoa ({@code ck_payments_currency_iso}). F06 chỉ thu VND. */
  public String getCurrency() {
    return currency;
  }

  public PaymentGateway getGateway() {
    return gateway;
  }

  /** Chỉ đọc. Đổi qua {@code captureAtomic}, {@code markFailed}, {@code recomputeRefundStatus}. */
  public PaymentStatus getStatus() {
    return status;
  }

  /** Mã giao dịch do gateway cấp; có thể có từ lúc PENDING, bắt buộc khi đã thu tiền. */
  public String getPaymentReference() {
    return paymentReference;
  }

  /**
   * Payload gốc của gateway — JSON object dạng text, theo cách {@code jsonb} lưu: key đã sắp lại,
   * khoảng trắng đã chuẩn hoá. So sánh bằng cách parse JSON, đừng so chuỗi với payload đã gửi.
   */
  public String getGatewayResponse() {
    return gatewayResponse;
  }

  public String getFailureReason() {
    return failureReason;
  }

  /** {@code null} nếu payment đi thẳng PENDING → CAPTURED. */
  public Instant getAuthorizedAt() {
    return authorizedAt;
  }

  /** Có khi và chỉ khi đã thu tiền: CAPTURED / PARTIALLY_REFUNDED / REFUNDED. */
  public Instant getCapturedAt() {
    return capturedAt;
  }

  /** Có khi và chỉ khi FAILED. */
  public Instant getFailedAt() {
    return failedAt;
  }
}
