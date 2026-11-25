package com.tungph7.learnpostgres.feature.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
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
 * Đơn hàng — {@code app.orders} (V12).
 *
 * <p>Ba quyết định khác entity thường gặp:
 *
 * <ul>
 *   <li><b>Trạng thái chỉ đọc.</b> {@code status}, 4 timestamp trạng thái và {@code cancel_reason}
 *       map {@code insertable = false, updatable = false}, không có setter — giống {@code
 *       Coupon.usedCount}. Mọi thay đổi trạng thái đi qua {@code OrderRepository.transitionStatus}
 *       ({@code UPDATE ... WHERE status = :from}). Lý do: câu UPDATE native không đi qua entity,
 *       nên một instance {@code Order} đang giữ trong persistence context có thể mang {@code
 *       status} cũ; nếu cột này updatable, lần flush kế tiếp (vd sửa {@code internalNote}) sẽ ghi
 *       đè trạng thái cũ lên trạng thái mới. Insert thì để DB DEFAULT {@code 'PENDING'} lo — đơn
 *       luôn bắt đầu ở PENDING.
 *   <li><b>Snapshot + tiền là bất biến.</b> Chụp lúc đặt đơn, {@code updatable = false}.
 *   <li><b>{@code total} do entity tính</b> = subtotal - discount + shipping, không nhận từ caller.
 *       Tiền phải có tối đa 2 chữ số thập phân: DB làm tròn từng cột về {@code NUMERIC(12,2)} TRƯỚC
 *       khi chạy {@code ck_orders_total_match}, nên số chưa làm tròn có thể lệch 0.01 (xem {@code
 *       01-migration.md §1.4}). Constructor chặn sớm bằng {@link IllegalArgumentException}.
 * </ul>
 */
@Entity
@Table(name = "orders", schema = "app")
public class Order extends BaseEntity {

  private static final int MONEY_SCALE = 2;

  @Column(name = "order_number", nullable = false, updatable = false, length = 32)
  private String orderNumber;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private UUID customerId;

  @Column(name = "shipping_address_id", nullable = false, updatable = false)
  private UUID shippingAddressId;

  @Column(name = "coupon_id", updatable = false)
  private UUID couponId;

  // -----------------------------------------------------------------
  // Snapshot shipping — bất biến sau khi đặt đơn
  // -----------------------------------------------------------------

  @Column(name = "shipping_recipient", nullable = false, updatable = false, length = 160)
  private String shippingRecipient;

  @Column(name = "shipping_phone", nullable = false, updatable = false, length = 32)
  private String shippingPhone;

  @Column(name = "shipping_address_line1", nullable = false, updatable = false, length = 255)
  private String shippingAddressLine1;

  @Column(name = "shipping_address_line2", updatable = false, length = 255)
  private String shippingAddressLine2;

  @Column(name = "shipping_city", nullable = false, updatable = false, length = 80)
  private String shippingCity;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "shipping_country", nullable = false, updatable = false, length = 2)
  private String shippingCountry;

  // -----------------------------------------------------------------
  // Money — bất biến, total do entity tính
  // -----------------------------------------------------------------

  @Column(name = "subtotal", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal subtotal;

  @Column(name = "discount_amount", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal discountAmount;

  @Column(name = "shipping_fee", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal shippingFee;

  @Column(name = "total", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal total;

  // -----------------------------------------------------------------
  // State machine — CHỈ ĐỌC, ghi qua OrderRepository.transitionStatus
  // -----------------------------------------------------------------

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, insertable = false, updatable = false, length = 16)
  private OrderStatus status = OrderStatus.PENDING;

  @Column(name = "paid_at", insertable = false, updatable = false)
  private Instant paidAt;

  @Column(name = "shipped_at", insertable = false, updatable = false)
  private Instant shippedAt;

  @Column(name = "delivered_at", insertable = false, updatable = false)
  private Instant deliveredAt;

  @Column(name = "cancelled_at", insertable = false, updatable = false)
  private Instant cancelledAt;

  @Column(name = "cancel_reason", insertable = false, updatable = false, length = 255)
  private String cancelReason;

  // -----------------------------------------------------------------
  // Note — sửa được
  // -----------------------------------------------------------------

  @Column(name = "customer_note")
  private String customerNote;

  @Column(name = "internal_note")
  private String internalNote;

  protected Order() {
    // for JPA
  }

  /**
   * Đơn mới ở trạng thái PENDING.
   *
   * @param discountAmount số tiền giảm đã làm tròn 2 chữ số (vd {@code
   *     CouponService.computeDiscount}); {@code BigDecimal.ZERO} khi không có coupon
   * @param couponId {@code null} khi không dùng coupon
   * @throws IllegalArgumentException nếu một khoản tiền có hơn 2 chữ số thập phân
   */
  public Order(
      String orderNumber,
      UUID customerId,
      UUID shippingAddressId,
      ShippingSnapshot shipping,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      BigDecimal shippingFee,
      UUID couponId) {
    this.orderNumber = orderNumber;
    this.customerId = customerId;
    this.shippingAddressId = shippingAddressId;
    this.couponId = couponId;
    this.shippingRecipient = shipping.recipient();
    this.shippingPhone = shipping.phone();
    this.shippingAddressLine1 = shipping.addressLine1();
    this.shippingAddressLine2 = shipping.addressLine2();
    this.shippingCity = shipping.city();
    this.shippingCountry = shipping.country();
    this.subtotal = money(subtotal, "subtotal");
    this.discountAmount = money(discountAmount, "discountAmount");
    this.shippingFee = money(shippingFee, "shippingFee");
    this.total = this.subtotal.subtract(this.discountAmount).add(this.shippingFee);
  }

  /** Chuẩn hoá về scale 2; từ chối nếu phải làm tròn mới về được scale 2. */
  private static BigDecimal money(BigDecimal amount, String field) {
    Objects.requireNonNull(amount, field);
    try {
      return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException(
          "%s phải có tối đa %d chữ số thập phân: %s".formatted(field, MONEY_SCALE, amount), ex);
    }
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public String getOrderNumber() {
    return orderNumber;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getShippingAddressId() {
    return shippingAddressId;
  }

  public UUID getCouponId() {
    return couponId;
  }

  public String getShippingRecipient() {
    return shippingRecipient;
  }

  public String getShippingPhone() {
    return shippingPhone;
  }

  public String getShippingAddressLine1() {
    return shippingAddressLine1;
  }

  public String getShippingAddressLine2() {
    return shippingAddressLine2;
  }

  public String getShippingCity() {
    return shippingCity;
  }

  public String getShippingCountry() {
    return shippingCountry;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public BigDecimal getShippingFee() {
    return shippingFee;
  }

  public BigDecimal getTotal() {
    return total;
  }

  /** Chỉ đọc. Đổi trạng thái qua {@code OrderRepository.transitionStatus}. */
  public OrderStatus getStatus() {
    return status;
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public Instant getShippedAt() {
    return shippedAt;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }

  /** Set cho cả CANCELLED lẫn EXPIRED (CHECK {@code ck_orders_cancelled_at_with_status}). */
  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public String getCustomerNote() {
    return customerNote;
  }

  public void setCustomerNote(String customerNote) {
    this.customerNote = customerNote;
  }

  public String getInternalNote() {
    return internalNote;
  }

  public void setInternalNote(String internalNote) {
    this.internalNote = internalNote;
  }
}
