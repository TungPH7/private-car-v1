package com.tungph7.learnpostgres.feature.order.domain;

import java.util.Set;

/**
 * Trạng thái đơn hàng — mapping cột {@code status VARCHAR(16)}, miền giá trị khớp CHECK {@code
 * ck_orders_status} (V12).
 *
 * <p>Bảng transition hợp lệ encode ngay ở enum (khớp state machine ở {@code 00-README §4}):
 *
 * <pre>
 *   PENDING   → PAID, CANCELLED, EXPIRED
 *   PAID      → SHIPPED, CANCELLED
 *   SHIPPED   → DELIVERED, CANCELLED
 *   DELIVERED, CANCELLED, EXPIRED → (terminal)
 * </pre>
 *
 * <p>Đây là lớp guard ở app. DB KHÔNG kiểm transition (V12/V14 chỉ kiểm miền giá trị); lớp chặn
 * race là {@code UPDATE ... WHERE status = :from} ở {@code OrderRepository.transitionStatus}.
 */
public enum OrderStatus {
  PENDING,
  PAID,
  SHIPPED,
  DELIVERED,
  CANCELLED,
  EXPIRED;

  /** Các trạng thái được phép chuyển tới từ trạng thái này. Rỗng = terminal. */
  public Set<OrderStatus> allowedNext() {
    return switch (this) {
      case PENDING -> Set.of(PAID, CANCELLED, EXPIRED);
      case PAID -> Set.of(SHIPPED, CANCELLED);
      case SHIPPED -> Set.of(DELIVERED, CANCELLED);
      case DELIVERED, CANCELLED, EXPIRED -> Set.of();
    };
  }

  public boolean canTransitionTo(OrderStatus next) {
    return allowedNext().contains(next);
  }

  public boolean isTerminal() {
    return allowedNext().isEmpty();
  }
}
