package com.tungph7.learnpostgres.feature.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;

import com.tungph7.learnpostgres.common.domain.UuidV7;

/**
 * Một dòng hàng của đơn — {@code app.order_items} (V13). Bất biến sau khi tạo.
 *
 * <p>KHÔNG extend {@code BaseEntity}: V13 không có cột {@code updated_at / created_by / updated_by
 * / version} (cùng lý do với {@code CouponRedemption}).
 *
 * <p>{@code productName}, {@code productSku}, {@code unitPrice} là snapshot tại thời điểm đặt:
 * product đổi tên / đổi giá sau đó không ảnh hưởng đơn cũ.
 *
 * <p><b>{@code lineTotal} là GENERATED STORED column.</b> DB từ chối mọi giá trị ghi vào, kể cả
 * {@code NULL} (SQLSTATE 428C9), nên column phải nằm ngoài INSERT/UPDATE. {@link Generated} làm cả
 * hai việc: bỏ column khỏi DML và đọc lại giá trị DB vừa tính ngay sau INSERT — có {@code
 * lineTotal} sau {@code flush()} mà không cần {@code refresh()}.
 */
@Entity
@Table(name = "order_items", schema = "app")
public class OrderItem {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "product_name", nullable = false, updatable = false, length = 255)
  private String productName;

  @Column(name = "product_sku", updatable = false, length = 64)
  private String productSku;

  @Column(name = "quantity", nullable = false, updatable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal unitPrice;

  @Generated
  @Column(name = "line_total", insertable = false, updatable = false, precision = 14, scale = 2)
  private BigDecimal lineTotal;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected OrderItem() {
    // for JPA
  }

  public OrderItem(
      UUID orderId,
      UUID productId,
      String productName,
      String productSku,
      int quantity,
      BigDecimal unitPrice) {
    this.orderId = orderId;
    this.productId = productId;
    this.productName = productName;
    this.productSku = productSku;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  @PrePersist
  protected void onPrePersist() {
    if (id == null) {
      id = UuidV7.generate();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  // -----------------------------------------------------------------
  // Getters only — bất biến
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public String getProductSku() {
    return productSku;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  /** {@code quantity * unit_price} do DB tính. {@code null} cho tới khi entity được flush. */
  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
