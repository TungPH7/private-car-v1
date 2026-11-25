package com.tungph7.learnpostgres.feature.payment.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.feature.order.OrderFixtures;

/**
 * Seed + dọn dữ liệu cho integration test của feature payment, bằng SQL — cùng khuôn {@code
 * OrderTestData} của F05 (lớp đó package-private trong {@code feature.order.integration}).
 *
 * <p>Payment chỉ cần một order PENDING có tổng lớn hơn 0: không items, không tồn kho. Đủ cho cả
 * {@code markPaid} (PENDING → PAID không đụng tồn kho) lẫn job expire (không có hàng để nhả). Order
 * dựng bằng {@link OrderFixtures} (tổng 0) rồi đặt lại tiền — {@code subtotal = total} giữ đúng
 * {@code ck_orders_total_match}.
 *
 * <p>Mọi lần ghi bọc {@link TransactionTemplate}: Hikari đặt {@code auto-commit: false}, và
 * {@code @SpringBootTest} không có transaction bao quanh. Dọn theo tiền tố email của customer, nên
 * dọn được cả dữ liệu một lần chạy trước bỏ dở.
 *
 * <p>Mỗi lớp test một tiền tố riêng — {@code payment-it-} (bài 03), {@code payment-http-}, {@code
 * payment-conc-} (bài 05) — nên lớp này không bao giờ xoá dữ liệu của lớp khác. Tiền tố chỉ dùng
 * {@code [a-z0-9-]}: {@code _} là ký tự đại diện của {@code LIKE}.
 */
final class PaymentTestData {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final String prefix;

  PaymentTestData(JdbcTemplate jdbc, TransactionTemplate tx, String prefix) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.prefix = prefix;
  }

  // ================================================================
  // Seed
  // ================================================================

  /** Order PENDING tổng {@code total} của một customer mới. */
  UUID pendingOrder(String total) {
    return tx.execute(
        status -> {
          UUID customerId =
              jdbc.queryForObject(
                  """
                  INSERT INTO app.customers (email, first_name, last_name)
                  VALUES (?, 'Payment', 'Probe') RETURNING id
                  """,
                  UUID.class,
                  prefix + UUID.randomUUID() + "@example.com");
          UUID orderId =
              OrderFixtures.insertOrder(
                  jdbc, OrderFixtures.insertShippingAddress(jdbc, customerId));
          BigDecimal money = new BigDecimal(total);
          jdbc.update(
              "UPDATE app.orders SET subtotal = ?, total = ? WHERE id = ?", money, money, orderId);
          return orderId;
        });
  }

  /** Lùi {@code created_at} của order — {@code age} là interval Postgres, vd {@code "2 hours"}. */
  void makeOverdue(UUID orderId, String age) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE app.orders SET created_at = now() - CAST(? AS interval) WHERE id = ?",
                age,
                orderId));
  }

  /**
   * Refund PENDING → FAILED, giả lập gateway từ chối hoàn tiền. API chưa có đường này ({@code
   * 04-dto-mapper-controller.md §9}) nên đi bằng SQL. V15 cho phép: trigger kiểm refund có {@code
   * WHEN (NEW.status <> 'FAILED')}, và {@code completed_at} vẫn NULL như CHECK đòi.
   *
   * @return số row đã đổi — 0 nếu refund không còn PENDING
   */
  int failRefund(UUID refundId) {
    Integer rows =
        tx.execute(
            status ->
                jdbc.update(
                    "UPDATE app.payment_refunds SET status = 'FAILED'"
                        + " WHERE id = ? AND status = 'PENDING'",
                    refundId));
    return rows == null ? 0 : rows;
  }

  // ================================================================
  // Đọc
  // ================================================================

  String orderStatus(UUID orderId) {
    return jdbc.queryForObject("SELECT status FROM app.orders WHERE id = ?", String.class, orderId);
  }

  String paymentStatus(UUID paymentId) {
    return jdbc.queryForObject(
        "SELECT status FROM app.payments WHERE id = ?", String.class, paymentId);
  }

  /** Số dòng lịch sử của order chuyển sang {@code toStatus}. */
  int historyRows(UUID orderId, String toStatus) {
    return count(
        "SELECT count(*) FROM app.order_status_history WHERE order_id = ? AND to_status = ?",
        orderId,
        toStatus);
  }

  /**
   * {@code gateway_response} dạng text, đúng như {@code jsonb} đã lưu: key sắp lại, có khoảng trắng
   * sau dấu {@code :} và {@code ,} — còn số giữ nguyên từng chữ số đã nhận.
   */
  String gatewayResponse(UUID paymentId) {
    return jdbc.queryForObject(
        "SELECT gateway_response::text FROM app.payments WHERE id = ?", String.class, paymentId);
  }

  /** Số payment của mọi order mang tiền tố của lớp test. */
  int paymentCount() {
    return count(
        "SELECT count(*) FROM app.payments p"
            + " JOIN app.orders o ON o.id = p.order_id"
            + " JOIN app.customers c ON c.id = o.customer_id"
            + " WHERE c.email LIKE ?",
        prefix + "%");
  }

  int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  // ================================================================
  // Dọn
  // ================================================================

  /**
   * Xoá mọi row mang tiền tố, theo thứ tự FK (đều RESTRICT): refund → payment → order (CASCADE
   * items + history) → customer (CASCADE address).
   */
  void cleanup() {
    String like = prefix + "%";
    String ordersOfPrefix =
        "SELECT o.id FROM app.orders o JOIN app.customers c ON c.id = o.customer_id"
            + " WHERE c.email LIKE ?";
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM app.payment_refunds WHERE payment_id IN"
                  + " (SELECT id FROM app.payments WHERE order_id IN ("
                  + ordersOfPrefix
                  + "))",
              like);
          jdbc.update("DELETE FROM app.payments WHERE order_id IN (" + ordersOfPrefix + ")", like);
          jdbc.update(
              "DELETE FROM app.orders WHERE customer_id IN"
                  + " (SELECT id FROM app.customers WHERE email LIKE ?)",
              like);
          jdbc.update("DELETE FROM app.customers WHERE email LIKE ?", like);
        });
  }
}
