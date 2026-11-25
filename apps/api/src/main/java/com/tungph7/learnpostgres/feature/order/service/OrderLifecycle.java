package com.tungph7.learnpostgres.feature.order.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.repository.OrderItemRepository;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;
import com.tungph7.learnpostgres.feature.order.repository.StatusHistoryRepository;

/**
 * Vòng đời đơn sau khi đặt: chuyển trạng thái, hủy (bù trừ), expire.
 *
 * <p>Mọi lần đổi trạng thái đi qua đúng một chỗ — {@link #moveStatus} — với 2 lớp guard: state
 * machine ở app ({@code OrderStatus.canTransitionTo}) và {@code UPDATE ... WHERE status = :from} ở
 * DB (chống race). Rồi mới tới tác dụng phụ: DELIVERED chốt tồn kho; CANCELLED / EXPIRED nhả hàng
 * đã giữ và hoàn lượt coupon. Tất cả trong cùng một transaction: một bước bù trừ hỏng thì cả lần
 * đổi trạng thái rollback, không có đơn CANCELLED mà hàng vẫn bị giữ.
 *
 * <p>Transaction: {@link #transition} và {@link #cancel} là {@code MANDATORY} — chạy trong
 * transaction do {@link OrderServiceImpl} mở. {@link #expireIfOverdue} và {@link
 * #findOverdueOrderIds} tự mở transaction, vì job gọi chúng từ ngoài mọi transaction (xem {@code
 * OrderServiceImpl.expirePendingOrders}).
 *
 * <p>Nhả / chốt tồn kho luôn đi theo thứ tự {@code productId} — cùng thứ tự với {@code
 * InventoryService.bulkReserve} lúc đặt đơn — để hai đơn chạm cùng các row tồn kho không thể khóa
 * chéo nhau.
 */
@Service
class OrderLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OrderLifecycle.class);

  /** Actor của thao tác hệ thống tự làm: job expire, xác nhận thanh toán. */
  static final String SYSTEM_ACTOR = "system";

  private final OrderRepository orderRepo;
  private final OrderItemRepository itemRepo;
  private final StatusHistoryRepository historyRepo;
  private final InventoryService inventoryService;
  private final CouponService couponService;
  private final Clock clock;
  private final OrderExpirationProperties expiration;

  OrderLifecycle(
      OrderRepository orderRepo,
      OrderItemRepository itemRepo,
      StatusHistoryRepository historyRepo,
      InventoryService inventoryService,
      CouponService couponService,
      Clock clock,
      OrderExpirationProperties expiration) {
    this.orderRepo = orderRepo;
    this.itemRepo = itemRepo;
    this.historyRepo = historyRepo;
    this.inventoryService = inventoryService;
    this.couponService = couponService;
    this.clock = clock;
    this.expiration = expiration;
  }

  // ================================================================
  // Chuyển trạng thái — trong transaction của OrderServiceImpl
  // ================================================================

  /**
   * PENDING → PAID → SHIPPED → DELIVERED, hoặc hủy.
   *
   * <p>{@code CANCELLED} đi đúng luồng hủy của {@link #cancel} — đổi mỗi cột status mà không nhả
   * hàng / hoàn coupon là để lại hàng bị giữ vĩnh viễn. {@code EXPIRED} bị từ chối: chỉ job hết hạn
   * thanh toán đặt trạng thái này, để "EXPIRED" luôn có đúng một nghĩa.
   *
   * @return đơn đọc lại sau khi chuyển
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Order transition(UUID orderId, OrderStatus to, String reason, String actor) {
    Order order = load(orderId);
    switch (to) {
      case CANCELLED -> cancelOrder(order, reason, actor);
      case EXPIRED ->
          throw new ConflictException(
              ErrorCode.ORD_INVALID_STATUS_TRANSITION,
              "Đơn %s: EXPIRED chỉ do job hết hạn thanh toán đặt — muốn dừng đơn thì hủy."
                  .formatted(order.getOrderNumber()));
      default -> advanceOrder(order, to, reason, actor);
    }
    // transitionStatus đã clear persistence context → đọc lại từ DB ra trạng thái mới.
    return load(orderId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cancel(UUID orderId, String reason, String actor) {
    cancelOrder(load(orderId), reason, actor);
  }

  // ================================================================
  // Expire — job gọi, mỗi đơn một transaction riêng
  // ================================================================

  /** Ứng viên: đơn PENDING tạo trước {@code now - pendingTtl}, cũ nhất trước, tối đa batchSize. */
  @Transactional(readOnly = true)
  public List<UUID> findOverdueOrderIds() {
    return orderRepo.findExpirationCandidates(overdueCutoff(), expiration.batchSize());
  }

  /**
   * Expire một đơn nếu nó vẫn PENDING, vẫn quá hạn, và không ai đang giữ nó.
   *
   * <p>Transaction riêng cho từng đơn (REQUIRED, caller không có transaction): đơn này lỗi thì chỉ
   * nó rollback, các đơn khác trong batch không bị kéo theo.
   *
   * @return {@code true} nếu lần gọi này đã chuyển đơn sang EXPIRED; {@code false} nếu đơn đã được
   *     thanh toán / hủy / expire, chưa quá hạn, hoặc đang bị transaction khác khóa (SKIP LOCKED)
   */
  @Transactional
  public boolean expireIfOverdue(UUID orderId) {
    Optional<Order> locked = orderRepo.lockForExpiration(orderId, overdueCutoff());
    if (locked.isEmpty()) {
      return false;
    }
    Order order = locked.get();
    moveStatus(order, OrderStatus.EXPIRED, expiredReason(), SYSTEM_ACTOR);
    releaseReservation(order, SYSTEM_ACTOR);
    revokeCoupon(order);
    return true;
  }

  // ================================================================
  // Helpers
  // ================================================================

  /**
   * CANCELLED + bù trừ.
   *
   * <p>Hủy sau SHIPPED KHÔNG nhả hàng: tồn kho chỉ chốt ở DELIVERED, nên lúc này hàng vẫn đang được
   * giữ (reserved) dù đã rời kho. Nhả ra là cho bán lại hàng không còn trên kệ. Giữ nguyên để luồng
   * trả hàng (ngoài phạm vi F05) nhả khi hàng thật sự về kho.
   */
  private void cancelOrder(Order order, String reason, String actor) {
    OrderStatus from = moveStatus(order, OrderStatus.CANCELLED, reason, actor);
    if (from == OrderStatus.SHIPPED) {
      log.warn(
          "Order {} hủy sau khi SHIPPED — giữ reservation, cần xử lý trả hàng thủ công",
          order.getOrderNumber());
    } else {
      releaseReservation(order, actor);
    }
    revokeCoupon(order);
  }

  /** PAID / SHIPPED / DELIVERED. Chỉ DELIVERED có tác dụng phụ: chốt tồn kho (reserved → sold). */
  private void advanceOrder(Order order, OrderStatus to, String reason, String actor) {
    moveStatus(order, to, reason, actor);
    if (to == OrderStatus.DELIVERED) {
      itemsByProductId(order.getId())
          .forEach(
              item ->
                  inventoryService.commit(
                      item.getProductId(), item.getQuantity(), order.getId(), actor));
    }
  }

  /**
   * Đổi trạng thái có guard + ghi history.
   *
   * <p>Guard 1 — state machine: bước không hợp lệ (vd PENDING → DELIVERED, hủy đơn đã giao) bị chặn
   * trước khi chạm DB. Guard 2 — {@code WHERE status = :from}: giữa lúc đọc đơn và lúc ghi, giao
   * dịch khác đã đổi trạng thái → 0 row → không ghi gì.
   *
   * @return trạng thái trước khi đổi
   */
  private OrderStatus moveStatus(Order order, OrderStatus to, String reason, String actor) {
    OrderStatus from = order.getStatus();
    if (!from.canTransitionTo(to)) {
      throw new ConflictException(
          ErrorCode.ORD_INVALID_STATUS_TRANSITION,
          "Đơn %s đang %s, không chuyển sang %s được.".formatted(order.getOrderNumber(), from, to));
    }
    if (orderRepo.transitionStatus(order.getId(), from.name(), to.name(), reason) == 0) {
      throw new ConflictException(
          ErrorCode.ORD_RACE_CONDITION,
          "Đơn %s vừa được giao dịch khác đổi trạng thái — tải lại đơn rồi thử lại."
              .formatted(order.getOrderNumber()));
    }
    historyRepo.save(new OrderStatusHistory(order.getId(), from, to, reason, actor));
    return from;
  }

  private void releaseReservation(Order order, String actor) {
    itemsByProductId(order.getId())
        .forEach(
            item ->
                inventoryService.release(
                    item.getProductId(), item.getQuantity(), order.getId(), actor));
  }

  /** Cùng thứ tự với {@code InventoryService.bulkReserve}: theo {@code productId}. */
  private List<OrderItem> itemsByProductId(UUID orderId) {
    return itemRepo.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
        .sorted(Comparator.comparing(OrderItem::getProductId))
        .toList();
  }

  /** {@code revoke} của F04 idempotent — đơn đã hoàn lượt thì gọi lại không trừ thêm. */
  private void revokeCoupon(Order order) {
    if (order.getCouponId() != null) {
      couponService.revoke(order.getCouponId(), order.getId());
    }
  }

  private Order load(UUID orderId) {
    return orderRepo.findById(orderId).orElseThrow(() -> orderNotFound(orderId));
  }

  private Instant overdueCutoff() {
    return clock.instant().minus(expiration.pendingTtl());
  }

  private String expiredReason() {
    return "Auto-expired: unpaid after " + expiration.pendingTtl();
  }

  static NotFoundException orderNotFound(UUID orderId) {
    return new NotFoundException(
        ErrorCode.ORD_NOT_FOUND, "Order '%s' không tồn tại.".formatted(orderId));
  }
}
