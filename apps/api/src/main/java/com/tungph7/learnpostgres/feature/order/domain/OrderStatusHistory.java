package com.tungph7.learnpostgres.feature.order.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.tungph7.learnpostgres.common.domain.UuidV7;

/**
 * Log một lần đổi trạng thái đơn — {@code app.order_status_history} (V14). Append-only.
 *
 * <p>Không setter, không {@code BaseEntity} (V14 không có cột audit) — cùng pattern với {@code
 * InventoryMovement} của F03.
 *
 * <p>{@code fromStatus = null} nghĩa là row khởi tạo ({@code null → PENDING}) lúc đặt đơn.
 */
@Entity
@Table(name = "order_status_history", schema = "app")
public class OrderStatusHistory {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", updatable = false, length = 16)
  private OrderStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, updatable = false, length = 16)
  private OrderStatus toStatus;

  @Column(name = "reason", updatable = false, length = 255)
  private String reason;

  @Column(name = "actor", nullable = false, updatable = false, length = 64)
  private String actor;

  @Column(name = "changed_at", nullable = false, updatable = false)
  private Instant changedAt;

  protected OrderStatusHistory() {
    // for JPA
  }

  /**
   * Ghi nhận một lần chuyển {@code fromStatus → toStatus}.
   *
   * @param fromStatus {@code null} cho row khởi tạo lúc đặt đơn
   * @param actor customerId / {@code system} / {@code ops} — không được blank ({@code
   *     ck_osh_actor_non_blank})
   */
  public OrderStatusHistory(
      UUID orderId, OrderStatus fromStatus, OrderStatus toStatus, String reason, String actor) {
    this.orderId = orderId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.reason = reason;
    this.actor = actor;
  }

  @PrePersist
  protected void onPrePersist() {
    if (id == null) {
      id = UuidV7.generate();
    }
    if (changedAt == null) {
      changedAt = Instant.now();
    }
  }

  // -----------------------------------------------------------------
  // Getters only — append-only
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public OrderStatus getFromStatus() {
    return fromStatus;
  }

  public OrderStatus getToStatus() {
    return toStatus;
  }

  public String getReason() {
    return reason;
  }

  public String getActor() {
    return actor;
  }

  public Instant getChangedAt() {
    return changedAt;
  }
}
