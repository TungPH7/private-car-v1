package com.tungph7.learnpostgres.feature.coupon.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.domain.TstzRange;
import com.tungph7.learnpostgres.common.domain.TstzRangeType;

/**
 * Coupon — mã giảm giá.
 *
 * <p>Vài điểm khác entity thường gặp:
 *
 * <ul>
 *   <li>{@code validPeriod} là cột {@code tstzrange}, map qua {@link TstzRangeType} (viết tay — xem
 *       lớp đó để biết vì sao không dùng thư viện).
 *   <li><b>{@code code} KHÔNG unique.</b> Cùng một code được phép có nhiều row active, miễn {@code
 *       validPeriod} không overlap — EXCLUDE constraint {@code ex_coupons_code_no_overlap} (V10)
 *       enforce đúng rule đó. Vì vậy mọi query lookup theo code phải trả về tập, hoặc phải kèm điều
 *       kiện thời gian; xem {@code CouponRepository}.
 *   <li><b>{@code usedCount} không có setter.</b> Counter chỉ được đổi bằng câu UPDATE atomic ở
 *       repository ({@code UPDATE ... WHERE used_count < max_uses RETURNING}). Set qua entity rồi
 *       {@code save()} là đường dẫn tới lost update khi có nhiều request cùng lúc.
 * </ul>
 */
@Entity
@Table(name = "coupons", schema = "app")
public class Coupon extends BaseEntity {

  @Column(name = "code", nullable = false, length = 40)
  private String code;

  @Column(name = "description")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 16)
  private CouponType type;

  @Column(name = "value", nullable = false, precision = 12, scale = 2)
  private BigDecimal value;

  @Column(name = "max_discount", precision = 12, scale = 2)
  private BigDecimal maxDiscount;

  @Column(name = "min_order_amount", precision = 12, scale = 2)
  private BigDecimal minOrderAmount;

  @Column(name = "max_uses")
  private Integer maxUses;

  @Column(name = "max_uses_per_customer")
  private Integer maxUsesPerCustomer;

  @Type(TstzRangeType.class)
  @Column(name = "valid_period", nullable = false, columnDefinition = "tstzrange")
  private TstzRange validPeriod;

  @Column(name = "used_count", nullable = false, insertable = false, updatable = false)
  private int usedCount;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  protected Coupon() {
    // for JPA
  }

  /**
   * Tạo coupon mới với khoảng hiệu lực {@code [validFrom, validTo)}.
   *
   * <p>{@code usedCount} không nằm trong constructor: giá trị khởi tạo do DB DEFAULT 0 quyết định,
   * và từ đó về sau chỉ đổi qua UPDATE atomic.
   */
  public Coupon(
      String code,
      CouponType type,
      BigDecimal value,
      OffsetDateTime validFrom,
      OffsetDateTime validTo) {
    this.code = code;
    this.type = type;
    this.value = value;
    this.validPeriod = TstzRange.of(validFrom, validTo);
    this.active = true;
  }

  // -----------------------------------------------------------------
  // Business helpers — service không cần biết validPeriod là kiểu gì
  // -----------------------------------------------------------------

  /** Coupon có đang trong khoảng hiệu lực tại {@code at} không (tương đương {@code @>} của PG). */
  public boolean isValidAt(OffsetDateTime at) {
    return validPeriod != null && validPeriod.contains(at);
  }

  public OffsetDateTime getValidFrom() {
    return validPeriod == null ? null : validPeriod.from();
  }

  public OffsetDateTime getValidTo() {
    return validPeriod == null ? null : validPeriod.to();
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public CouponType getType() {
    return type;
  }

  public void setType(CouponType type) {
    this.type = type;
  }

  public BigDecimal getValue() {
    return value;
  }

  public void setValue(BigDecimal value) {
    this.value = value;
  }

  public BigDecimal getMaxDiscount() {
    return maxDiscount;
  }

  public void setMaxDiscount(BigDecimal maxDiscount) {
    this.maxDiscount = maxDiscount;
  }

  public BigDecimal getMinOrderAmount() {
    return minOrderAmount;
  }

  public void setMinOrderAmount(BigDecimal minOrderAmount) {
    this.minOrderAmount = minOrderAmount;
  }

  public Integer getMaxUses() {
    return maxUses;
  }

  public void setMaxUses(Integer maxUses) {
    this.maxUses = maxUses;
  }

  public Integer getMaxUsesPerCustomer() {
    return maxUsesPerCustomer;
  }

  public void setMaxUsesPerCustomer(Integer maxUsesPerCustomer) {
    this.maxUsesPerCustomer = maxUsesPerCustomer;
  }

  public TstzRange getValidPeriod() {
    return validPeriod;
  }

  public void setValidPeriod(TstzRange validPeriod) {
    this.validPeriod = validPeriod;
  }

  /** Chỉ đọc. Ghi đi qua {@code CouponRepository.incrementUsedCount / decrementUsedCount}. */
  public int getUsedCount() {
    return usedCount;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
