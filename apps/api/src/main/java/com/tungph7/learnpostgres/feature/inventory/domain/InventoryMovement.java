package com.tungph7.learnpostgres.feature.inventory.domain;

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
 * InventoryMovement — append-only audit log.
 *
 * <p>Property:
 *
 * <ul>
 *   <li>KHÔNG có setter — object immutable sau khi tạo.
 *   <li>KHÔNG có method "edit" trong service.
 *   <li>DB cũng nên REVOKE UPDATE,DELETE ở production (xem 01-migration §4).
 * </ul>
 *
 * <p>Tạo instance qua static factory theo từng {@link MovementType} (thay vì constructor all-args).
 * Lý do: constructor 10 tham số — trong đó 5 số nguyên liền nhau — rất dễ truyền nhầm thứ tự, và
 * cũng vượt ngưỡng checkstyle ParameterNumber (max 8). Factory nhúng luôn quy tắc dấu của delta nên
 * gọi sai kiểu {@code deltaSold} cho RESERVE là không thể.
 */
@Entity
@Table(name = "inventory_movements", schema = "app")
public class InventoryMovement {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Enumerated(EnumType.STRING)
  @Column(name = "movement_type", nullable = false, updatable = false, length = 24)
  private MovementType movementType;

  @Column(name = "delta_on_hand", nullable = false, updatable = false)
  private int deltaOnHand;

  @Column(name = "delta_reserved", nullable = false, updatable = false)
  private int deltaReserved;

  @Column(name = "delta_sold", nullable = false, updatable = false)
  private int deltaSold;

  @Column(name = "on_hand_after", nullable = false, updatable = false)
  private int onHandAfter;

  @Column(name = "reserved_after", nullable = false, updatable = false)
  private int reservedAfter;

  @Column(name = "reason", length = 255, updatable = false)
  private String reason;

  @Column(name = "order_id", updatable = false)
  private UUID orderId;

  @Column(name = "actor", nullable = false, updatable = false, length = 64)
  private String actor;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected InventoryMovement() {
    // for JPA
  }

  // -----------------------------------------------------------------
  // Static factories — mỗi MovementType có quy tắc delta riêng
  // -----------------------------------------------------------------

  /** Nhập kho: on_hand += qty. */
  public static InventoryMovement stockIn(
      UUID productId, int qty, int onHandAfter, int reservedAfter, String reason, String actor) {
    InventoryMovement m = base(productId, MovementType.STOCK_IN, onHandAfter, reservedAfter, actor);
    m.deltaOnHand = qty;
    m.reason = reason;
    return m;
  }

  /** Xuất kho thủ công (hỏng, thất thoát): on_hand -= qty. */
  public static InventoryMovement stockOut(
      UUID productId, int qty, int onHandAfter, int reservedAfter, String reason, String actor) {
    InventoryMovement m =
        base(productId, MovementType.STOCK_OUT, onHandAfter, reservedAfter, actor);
    m.deltaOnHand = -qty;
    m.reason = reason;
    return m;
  }

  /** Giữ hàng cho đơn pending: reserved += qty. */
  public static InventoryMovement reserve(
      UUID productId,
      int qty,
      int onHandAfter,
      int reservedAfter,
      String reason,
      UUID orderId,
      String actor) {
    InventoryMovement m = base(productId, MovementType.RESERVE, onHandAfter, reservedAfter, actor);
    m.deltaReserved = qty;
    m.reason = reason;
    m.orderId = orderId;
    return m;
  }

  /** Giải phóng giữ hàng (huỷ đơn pending): reserved -= qty. */
  public static InventoryMovement release(
      UUID productId,
      int qty,
      int onHandAfter,
      int reservedAfter,
      String reason,
      UUID orderId,
      String actor) {
    InventoryMovement m = base(productId, MovementType.RELEASE, onHandAfter, reservedAfter, actor);
    m.deltaReserved = -qty;
    m.reason = reason;
    m.orderId = orderId;
    return m;
  }

  /** Giao hàng xong: on_hand -= qty, reserved -= qty, sold += qty. */
  public static InventoryMovement commit(
      UUID productId,
      int qty,
      int onHandAfter,
      int reservedAfter,
      String reason,
      UUID orderId,
      String actor) {
    InventoryMovement m = base(productId, MovementType.COMMIT, onHandAfter, reservedAfter, actor);
    m.deltaOnHand = -qty;
    m.deltaReserved = -qty;
    m.deltaSold = qty;
    m.reason = reason;
    m.orderId = orderId;
    return m;
  }

  /** Chỉnh tay (kiểm kê lệch): delta tuỳ ý, có thể âm hoặc dương. */
  public static InventoryMovement adjustment(
      UUID productId,
      int deltaOnHand,
      int deltaReserved,
      int onHandAfter,
      int reservedAfter,
      String reason,
      String actor) {
    InventoryMovement m =
        base(productId, MovementType.ADJUSTMENT, onHandAfter, reservedAfter, actor);
    m.deltaOnHand = deltaOnHand;
    m.deltaReserved = deltaReserved;
    m.reason = reason;
    return m;
  }

  private static InventoryMovement base(
      UUID productId, MovementType type, int onHandAfter, int reservedAfter, String actor) {
    InventoryMovement m = new InventoryMovement();
    m.productId = productId;
    m.movementType = type;
    m.onHandAfter = onHandAfter;
    m.reservedAfter = reservedAfter;
    m.actor = actor;
    return m;
  }

  @PrePersist
  protected void onPrePersist() {
    if (id == null) {
      id = UuidV7.generate();
    }
    if (occurredAt == null) {
      occurredAt = Instant.now();
    }
  }

  // -----------------------------------------------------------------
  // Getters only — không setter vì append-only
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public UUID getProductId() {
    return productId;
  }

  public MovementType getMovementType() {
    return movementType;
  }

  public int getDeltaOnHand() {
    return deltaOnHand;
  }

  public int getDeltaReserved() {
    return deltaReserved;
  }

  public int getDeltaSold() {
    return deltaSold;
  }

  public int getOnHandAfter() {
    return onHandAfter;
  }

  public int getReservedAfter() {
    return reservedAfter;
  }

  public String getReason() {
    return reason;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getActor() {
    return actor;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
