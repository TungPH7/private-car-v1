package com.tungph7.learnpostgres.feature.order.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;

/**
 * Append-only log của {@code app.order_status_history}.
 *
 * <p>CHỈ có insert (kế thừa {@code save}) và read — không khai báo method update/delete nào, giống
 * {@code MovementRepository} của F03.
 */
@Repository
public interface StatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

  /** Lịch sử của 1 đơn theo thời gian — chạy trên index {@code idx_osh_order_changed} (V14). */
  List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
