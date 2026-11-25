package com.tungph7.learnpostgres.feature.order.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy {@link OrderService#expirePendingOrders} theo lịch.
 *
 * <p>Lớp riêng thay vì đặt {@code @Scheduled} thẳng lên service:
 *
 * <ul>
 *   <li>Bật / tắt được bằng cấu hình ({@code app.order.expiration.enabled}). Profile test tắt nó —
 *       job chạy nền giữa chừng sẽ expire đơn mà test đang kiểm.
 *   <li>Service chỉ làm nghiệp vụ; test gọi thẳng {@code expirePendingOrders()} mà không phải chờ
 *       lịch.
 * </ul>
 *
 * <p>{@code fixedDelay}: lần sau bắt đầu {@code interval} SAU KHI lần trước xong — trong một
 * instance, hai lần chạy không bao giờ chồng nhau. Nhiều instance chạy cùng lúc vẫn an toàn nhờ
 * {@code FOR UPDATE SKIP LOCKED} ở {@code OrderRepository.lockForExpiration}.
 */
@Component
@ConditionalOnProperty(prefix = "app.order.expiration", name = "enabled", havingValue = "true")
class OrderExpirationJob {

  private final OrderService orderService;

  OrderExpirationJob(OrderService orderService) {
    this.orderService = orderService;
  }

  @Scheduled(fixedDelayString = "${app.order.expiration.interval}")
  public void run() {
    orderService.expirePendingOrders();
  }
}
