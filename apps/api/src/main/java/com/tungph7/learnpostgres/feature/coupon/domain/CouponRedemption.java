package com.tungph7.learnpostgres.feature.coupon.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.tungph7.learnpostgres.common.domain.UuidV7;

/**
 * Log một lần coupon được áp vào một order. Append-only.
 *
 * <p>KHÔNG extend {@code BaseEntity}: bảng {@code app.coupon_redemptions} (V11) không có cột {@code
 * updated_at / created_by / updated_by / version}. Ghép BaseEntity vào sẽ làm {@code ddl-auto:
 * validate} fail vì thiếu cột.
 *
 * <p>Không có setter — row không bao giờ được sửa. Revoke là DELETE, không phải UPDATE.
 *
 * <p>Idempotency do UNIQUE {@code (coupon_id, order_id)} đảm nhiệm; đường ghi chính là {@code
 * RedemptionRepository.insertIfAbsent} (INSERT ... ON CONFLICT DO NOTHING) chứ không phải {@code
 * save()}. Entity này chủ yếu phục vụ đọc; giữ constructor để test và các đường ghi không cần
 * idempotency dùng được.
 */
@Entity
@Table(name = "coupon_redemptions", schema = "app")
public class CouponRedemption {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "coupon_id", nullable = false, updatable = false)
  private UUID couponId;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private UUID customerId;

  @Column(name = "discount_amount", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal discountAmount;

  @Column(name = "redeemed_at", nullable = false, updatable = false)
  private Instant redeemedAt;

  protected CouponRedemption() {
    // for JPA
  }

  public CouponRedemption(UUID couponId, UUID orderId, UUID customerId, BigDecimal discountAmount) {
    this.couponId = couponId;
    this.orderId = orderId;
    this.customerId = customerId;
    this.discountAmount = discountAmount;
  }

  @PrePersist
  protected void onPrePersist() {
    if (id == null) {
      id = UuidV7.generate();
    }
    if (redeemedAt == null) {
      redeemedAt = Instant.now();
    }
  }

  // -----------------------------------------------------------------
  // Getters only — append-only
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public UUID getCouponId() {
    return couponId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public Instant getRedeemedAt() {
    return redeemedAt;
  }
}
