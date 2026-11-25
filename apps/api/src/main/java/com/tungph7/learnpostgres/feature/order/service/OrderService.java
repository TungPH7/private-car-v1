package com.tungph7.learnpostgres.feature.order.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;

/**
 * Nghiệp vụ đơn hàng — điểm vào duy nhất của feature order (controller bài 04, F06 payment).
 *
 * <p>Ba nhóm, khác nhau ở cách dùng transaction:
 *
 * <ul>
 *   <li><b>Đặt đơn + chuyển trạng thái</b> — mỗi lời gọi là một transaction, chạm nhiều bảng của
 *       F03 (tồn kho) và F04 (coupon). Bước nào hỏng thì rollback toàn bộ, kể cả phần của feature
 *       khác.
 *   <li><b>Job expire</b> ({@link #expirePendingOrders}) — mỗi ĐƠN một transaction riêng, để một
 *       đơn lỗi không kéo cả batch.
 *   <li><b>Đọc</b> — transaction read-only.
 * </ul>
 */
public interface OrderService {

  // ---- Đặt đơn ----

  /**
   * Đặt đơn: kiểm customer / address / product / coupon, chụp snapshot, giữ hàng, redeem coupon.
   *
   * @return đơn PENDING vừa tạo
   */
  Order placeOrder(PlaceOrderRequest req);

  // ---- Chuyển trạng thái ----

  /**
   * Hủy đơn (PENDING / PAID / SHIPPED → CANCELLED) và bù trừ: nhả hàng đã giữ, hoàn lượt coupon.
   */
  void cancelOrder(UUID orderId, String reason, String actor);

  /**
   * Chuyển trạng thái tổng quát (admin). {@code to = CANCELLED} đi đúng luồng hủy; {@code to =
   * EXPIRED} bị từ chối — chỉ job hết hạn thanh toán được đặt.
   *
   * @return đơn đọc lại sau khi chuyển
   */
  Order transitionStatus(UUID orderId, OrderStatus to, String reason, String actor);

  /** PENDING → PAID. F06 gọi khi xác nhận thanh toán. */
  Order markPaid(UUID orderId);

  /** PAID → SHIPPED. */
  Order markShipped(UUID orderId);

  /** SHIPPED → DELIVERED, chốt tồn kho: hàng đang giữ chuyển thành đã bán. */
  Order markDelivered(UUID orderId);

  // ---- Đọc ----

  Order getById(UUID id);

  /** Toàn bộ đơn trong 1 query: header + customer + coupon + items + lịch sử trạng thái. */
  OrderDetailResponse getDetail(UUID id);

  Order getByOrderNumber(String orderNumber);

  /** Đơn của một customer, mới nhất trước. Customer không tồn tại → 404. */
  Page<Order> listCustomerOrders(UUID customerId, Pageable pageable);

  List<OrderStatusHistory> getStatusHistory(UUID orderId);

  // ---- Job ----

  /**
   * Chuyển các đơn PENDING quá hạn thanh toán sang EXPIRED — nhả hàng, hoàn lượt coupon.
   *
   * @return số đơn lần gọi này đã expire (không tính đơn bỏ qua hoặc lỗi)
   */
  int expirePendingOrders();
}
