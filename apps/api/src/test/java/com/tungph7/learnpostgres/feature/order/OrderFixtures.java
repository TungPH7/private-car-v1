package com.tungph7.learnpostgres.feature.order;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seed {@code app.orders} bằng SQL cho test cần một order THẬT chỉ để thoả FK.
 *
 * <p>Từ V12, {@code coupon_redemptions.order_id} có FK tới {@code app.orders} — test redeem không
 * còn dùng {@code UUID.randomUUID()} làm order_id được nữa. Class này cố ý không đi qua entity
 * {@code Order}: nó chỉ INSERT đủ cột NOT NULL để có một row hợp lệ, không kiểm tra gì về order.
 *
 * <p>Chạy trên {@link JdbcTemplate} của caller nên đi chung transaction đang mở: trong {@code
 * DataJpaTest} thì rollback theo test; ở integration test không có transaction bao quanh, caller
 * phải tự bọc {@code TransactionTemplate} (Hikari {@code auto-commit: false}).
 *
 * <p>Dọn dẹp ở integration test: xoá order TRƯỚC customer. {@code orders.customer_id} và {@code
 * orders.shipping_address_id} là RESTRICT, nên CASCADE từ customer xuống address sẽ bị chặn nếu còn
 * order.
 */
public final class OrderFixtures {

  private OrderFixtures() {}

  /** Address SHIPPING của {@code customerId} — {@code orders.shipping_address_id} là NOT NULL. */
  public static UUID insertShippingAddress(JdbcTemplate jdbc, UUID customerId) {
    return jdbc.queryForObject(
        """
        INSERT INTO app.addresses (customer_id, type, full_name, phone, line1, city)
        VALUES (?, 'SHIPPING', 'Order Fixture', '+84901234567', '1 Fixture St', 'HCM')
        RETURNING id
        """,
        UUID.class,
        customerId);
  }

  /** Một order PENDING giao tới {@code shippingAddressId}. */
  public static UUID insertOrder(JdbcTemplate jdbc, UUID shippingAddressId) {
    return insertOrders(jdbc, shippingAddressId, 1).get(0);
  }

  /**
   * {@code count} order PENDING giao tới {@code shippingAddressId}, trong một câu lệnh.
   *
   * <p>customer_id và các cột shipping snapshot lấy từ chính address đó — giống cách placeOrder
   * chụp snapshot. Tiền để 0 ({@code total = subtotal - discount + shipping} vẫn đúng);
   * order_number lấy từ {@code app.orders_number_seq} nên không bao giờ trùng giữa các lần chạy.
   */
  public static List<UUID> insertOrders(JdbcTemplate jdbc, UUID shippingAddressId, int count) {
    return jdbc.queryForList(
        """
        INSERT INTO app.orders (
            customer_id, shipping_address_id,
            shipping_recipient, shipping_phone, shipping_address_line1, shipping_city,
            shipping_country, order_number, subtotal, total)
        SELECT a.customer_id, a.id,
               a.full_name, a.phone, a.line1, a.city,
               a.country_code, 'ORD-TEST-' || nextval('app.orders_number_seq'), 0, 0
        FROM app.addresses a
        CROSS JOIN generate_series(1, ?)
        WHERE a.id = ?
        RETURNING id
        """,
        UUID.class,
        count,
        shippingAddressId);
  }
}
