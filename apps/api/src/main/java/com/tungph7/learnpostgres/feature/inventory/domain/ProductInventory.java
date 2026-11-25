package com.tungph7.learnpostgres.feature.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * ProductInventory — 1-1 với products, PK = product_id.
 *
 * <p>KHÔNG kế thừa BaseEntity vì:
 *
 * <ul>
 *   <li>PK là FK product_id, không phải UUID độc lập.
 *   <li>Không cần optimistic lock {@code @Version} (dùng atomic UPDATE thay vì optimistic).
 *   <li>Không cần created_by / updated_by (movement log đã có actor).
 * </ul>
 *
 * <p>Vẫn có created_at + updated_at + version để tracking, nhưng manual mapping. Cột version KHÔNG
 * annotate {@code @Version} → Hibernate không inject {@code WHERE version = ?}.
 */
@Entity
@Table(name = "product_inventory", schema = "app")
public class ProductInventory {

  @Id
  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "on_hand", nullable = false)
  private int onHand;

  @Column(name = "reserved", nullable = false)
  private int reserved;

  @Column(name = "sold", nullable = false)
  private long sold;

  @Column(name = "reorder_point")
  private Integer reorderPoint;

  @Column(name = "last_restock_at")
  private Instant lastRestockAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  // -----------------------------------------------------------------
  // Constructors
  // -----------------------------------------------------------------

  protected ProductInventory() {
    // for JPA
  }

  public ProductInventory(UUID productId) {
    this.productId = productId;
    this.onHand = 0;
    this.reserved = 0;
    this.sold = 0;
  }

  // -----------------------------------------------------------------
  // Computed
  // -----------------------------------------------------------------

  /** available = on_hand - reserved. KHÔNG lưu DB — tính tại chỗ. */
  public int getAvailable() {
    return onHand - reserved;
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public UUID getProductId() {
    return productId;
  }

  public int getOnHand() {
    return onHand;
  }

  public void setOnHand(int onHand) {
    this.onHand = onHand;
  }

  public int getReserved() {
    return reserved;
  }

  public void setReserved(int reserved) {
    this.reserved = reserved;
  }

  public long getSold() {
    return sold;
  }

  public void setSold(long sold) {
    this.sold = sold;
  }

  public Integer getReorderPoint() {
    return reorderPoint;
  }

  public void setReorderPoint(Integer reorderPoint) {
    this.reorderPoint = reorderPoint;
  }

  public Instant getLastRestockAt() {
    return lastRestockAt;
  }

  public void setLastRestockAt(Instant lastRestockAt) {
    this.lastRestockAt = lastRestockAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
