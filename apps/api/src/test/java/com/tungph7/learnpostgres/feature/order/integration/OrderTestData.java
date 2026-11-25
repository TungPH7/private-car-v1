package com.tungph7.learnpostgres.feature.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.feature.order.OrderFixtures;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemRequest;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;

/**
 * Seed + dọn dữ liệu cho integration test của feature order, bằng SQL.
 *
 * <p>Mỗi lớp test một tiền tố riêng ({@code prefix}, vd {@code "order-conc-"}): email customer và
 * slug product bắt đầu bằng nó, code coupon bắt đầu bằng bản viết hoa của nó. {@link #cleanup()} vì
 * vậy xoá theo pattern chứ không theo biến — dọn được cả dữ liệu một lần chạy trước bỏ dở, và không
 * đụng dữ liệu của lớp test khác. Tiền tố chỉ dùng {@code [a-z0-9-]}: {@code _} là ký tự đại diện
 * của {@code LIKE}, và code coupon phải khớp {@code ^[A-Z0-9_-]{3,40}$}.
 *
 * <p>Mọi lần ghi bọc {@link TransactionTemplate}: Hikari đặt {@code auto-commit: false}, và
 * {@code @SpringBootTest} không có transaction bao quanh — ghi qua {@code JdbcTemplate} trần sẽ bị
 * rollback im lặng lúc trả connection về pool.
 *
 * <p>Product seed bằng SQL vì entity {@code Product} (F01) chưa map {@code status} / {@code sku},
 * mà placeOrder chỉ bán product {@code ACTIVE}.
 */
final class OrderTestData {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final String prefix;
  private final String codePrefix;

  OrderTestData(JdbcTemplate jdbc, TransactionTemplate tx, String prefix) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.prefix = prefix;
    this.codePrefix = prefix.toUpperCase(Locale.ROOT);
  }

  // ================================================================
  // Seed
  // ================================================================

  /** Customer ACTIVE kèm một address SHIPPING — đủ để đặt đơn. */
  Buyer buyer() {
    return tx.execute(
        status -> {
          UUID customerId =
              jdbc.queryForObject(
                  """
                  INSERT INTO app.customers (email, first_name, last_name)
                  VALUES (?, 'Order', 'Probe') RETURNING id
                  """,
                  UUID.class,
                  prefix + UUID.randomUUID() + "@example.com");
          return new Buyer(customerId, OrderFixtures.insertShippingAddress(jdbc, customerId));
        });
  }

  /** Address SHIPPING của {@code customerId}, đã xoá mềm ({@code is_deleted = TRUE}). */
  UUID deletedAddress(UUID customerId) {
    return tx.execute(
        status -> {
          UUID addressId = OrderFixtures.insertShippingAddress(jdbc, customerId);
          jdbc.update("UPDATE app.addresses SET is_deleted = TRUE WHERE id = ?", addressId);
          return addressId;
        });
  }

  UUID activeProduct(String price, int onHand) {
    return product(UUID.randomUUID(), price, onHand, "ACTIVE");
  }

  /**
   * Nhận id từ caller cho test cần biết thứ tự {@code productId} — {@code bulkReserve} giữ hàng
   * theo đúng thứ tự đó.
   */
  UUID activeProduct(UUID id, String price, int onHand) {
    return product(id, price, onHand, "ACTIVE");
  }

  /** Product ở {@code status} ({@code ck_products_status}: DRAFT / ACTIVE / ARCHIVED) + tồn kho. */
  UUID product(UUID id, String price, int onHand, String status) {
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              """
              INSERT INTO app.products (id, name, slug, price, sku, status)
              VALUES (?, ?, ?, ?, ?, ?)
              """,
              id,
              "Product " + id,
              prefix + id,
              new BigDecimal(price),
              "SKU-" + id,
              status);
          jdbc.update(
              "INSERT INTO app.product_inventory (product_id, on_hand, reserved, sold)"
                  + " VALUES (?, ?, 0, 0)",
              id,
              onHand);
        });
    return id;
  }

  /** Code chưa dùng — EXCLUDE constraint cấm 2 campaign cùng code chồng thời gian. */
  String couponCode() {
    return codePrefix + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
  }

  /** Coupon PERCENT {@code percent}%, còn hiệu lực quanh thời điểm chạy test (±1 ngày). */
  UUID percentCoupon(String code, int percent, int maxUses) {
    return insertCoupon(
        code, percent, maxUses, "now() - interval '1 day'", "now() + interval '1 day'");
  }

  /** Coupon có thật, đang active, nhưng khoảng hiệu lực đã qua — validate trả CPN_EXPIRED. */
  UUID expiredCoupon(String code) {
    return insertCoupon(code, 10, 100, "now() - interval '10 days'", "now() - interval '1 day'");
  }

  private UUID insertCoupon(String code, int percent, int maxUses, String from, String to) {
    return tx.execute(
        status ->
            jdbc.queryForObject(
                """
                INSERT INTO app.coupons (code, type, value, max_uses, valid_period)
                VALUES (?, 'PERCENT', ?, ?, tstzrange(%s, %s, '[)'))
                RETURNING id
                """
                    .formatted(from, to),
                UUID.class,
                code,
                percent,
                maxUses));
  }

  /** Lùi {@code created_at} của đơn — {@code age} là interval Postgres, vd {@code "2 hours"}. */
  void makeOverdue(UUID orderId, String age) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE app.orders SET created_at = now() - CAST(? AS interval) WHERE id = ?",
                age,
                orderId));
  }

  /** Chỉnh tay {@code reserved} — giả lập tồn kho bị sửa ngoài app. */
  void setReserved(UUID productId, int reserved) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE app.product_inventory SET reserved = ? WHERE product_id = ?",
                reserved,
                productId));
  }

  // ================================================================
  // Đọc
  // ================================================================

  int reservedOf(UUID productId) {
    return count("SELECT reserved FROM app.product_inventory WHERE product_id = ?", productId);
  }

  int usedCountOf(UUID couponId) {
    return count("SELECT used_count FROM app.coupons WHERE id = ?", couponId);
  }

  String statusOf(UUID orderId) {
    return jdbc.queryForObject("SELECT status FROM app.orders WHERE id = ?", String.class, orderId);
  }

  /** {@code movement_type} của các movement gắn với đơn, cũ nhất trước. */
  List<String> movementsOf(UUID orderId) {
    return jdbc.queryForList(
        "SELECT movement_type FROM app.inventory_movements WHERE order_id = ?"
            + " ORDER BY occurred_at, id",
        String.class,
        orderId);
  }

  /** Kết quả {@code count(*)} (hoặc một cột số nguyên) — {@code null} tính là 0. */
  int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  // ================================================================
  // Dọn
  // ================================================================

  /**
   * Xoá mọi row mang tiền tố của lớp test, theo thứ tự FK: redemption → order (CASCADE items +
   * history) → movement → product (CASCADE inventory) → coupon → customer (CASCADE address).
   *
   * <p>Sót một row là cleanup của lớp test khác chết theo: {@code coupon_redemptions}, {@code
   * orders}, {@code inventory_movements}, {@code order_items} đều giữ FK RESTRICT tới customer /
   * product / coupon. Feature sau thêm bảng có FK tới {@code orders} (payment ở F06) thì thêm bước
   * xoá bảng đó ở ĐẦU danh sách này.
   */
  void cleanup() {
    String like = prefix + "%";
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM app.coupon_redemptions WHERE customer_id IN"
                  + " (SELECT id FROM app.customers WHERE email LIKE ?)",
              like);
          jdbc.update(
              "DELETE FROM app.orders WHERE customer_id IN"
                  + " (SELECT id FROM app.customers WHERE email LIKE ?)",
              like);
          jdbc.update(
              "DELETE FROM app.inventory_movements WHERE product_id IN"
                  + " (SELECT id FROM app.products WHERE slug LIKE ?)",
              like);
          jdbc.update("DELETE FROM app.products WHERE slug LIKE ?", like);
          jdbc.update("DELETE FROM app.coupons WHERE code LIKE ?", codePrefix + "%");
          jdbc.update("DELETE FROM app.customers WHERE email LIKE ?", like);
        });
  }

  // ================================================================
  // Người mua
  // ================================================================

  /** Customer ACTIVE + address SHIPPING của chính họ. */
  record Buyer(UUID customerId, UUID addressId) {

    PlaceOrderRequest order(OrderItemRequest... items) {
      return orderWithCoupon(null, items);
    }

    PlaceOrderRequest orderWithCoupon(String couponCode, OrderItemRequest... items) {
      return new PlaceOrderRequest(customerId, List.of(items), addressId, couponCode, null);
    }
  }
}
