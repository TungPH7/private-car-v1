package com.tungph7.learnpostgres.feature.dashboard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dựng dữ liệu cho test báo cáo F07 bằng SQL — category, sản phẩm, khách, đơn ở đúng trạng thái và
 * đúng giờ, refund, sổ tồn kho.
 *
 * <p>Chạy trên {@link JdbcClient} của caller nên đi chung transaction đang mở: trong slice test thì
 * rollback theo test; ở integration test không có transaction bao quanh, caller tự bọc {@code
 * TransactionTemplate} (Hikari {@code auto-commit: false}).
 *
 * <p>Giờ ghi theo giờ cửa hàng ({@link #vn}). Đơn mang các mốc {@code paid_at} / {@code shipped_at}
 * / {@code delivered_at} / {@code cancelled_at} khớp đường đi trạng thái ({@link Path}) — CHECK của
 * V12 chỉ canh {@code paid_at} và {@code cancelled_at}, phần còn lại fixture tự giữ đúng.
 *
 * <p>{@link #seedWorld()} dựng bộ dữ liệu 2025 dùng chung, đáp số tính tay ở {@code
 * F07__dashboard/02-domain-and-repository.md §5.1}:
 *
 * <pre>
 * Category: Dien tu (gốc) ─ Dien thoai (con);  Thoi trang (gốc)
 * Sản phẩm: P1 Phone X 100.000 (Dien thoai), P2 Laptop Y 200.000 (Dien tu),
 *           P3 Ao Z 50.000 (Thoi trang),      P4 Qua tang 30.000 (chưa phân loại)
 * Khách:    C1 05/01/2025, C2 31/01/2025 23:30, C3 01/02/2025 00:30, C4 20/01/2025 (không đơn),
 *           C5 01/03/2024
 *
 *  #  khách  đường đi              đặt lúc (giờ VN)   hàng            giảm     total
 *  1  C1     DELIVERED             10/01/2025 09:00   2×P1, 1×P3     50.000    200.000
 *  2  C1     PAID                  01/02/2025 03:30   1×P2                0    200.000
 *  3  C2     CANCELLED_AFTER_PAID  14/02/2025 12:00   1×P1                0    100.000
 *  4  C2     DELIVERED             03/03/2025 10:00   3×P4, 1×P1          0    190.000
 *  5  C3     DELIVERED             10/02/2025 15:00   2×P2                0    400.000
 *  6  C2     EXPIRED               16/04/2025 10:00   1×P1                0    100.000
 *  7  C1     SHIPPED               15/04/2025 10:00   4×P3                0    200.000
 *  8  C5     DELIVERED             20/04/2024 10:00   1×P1                0    100.000
 *  9  C3     PENDING               30/04/2025 23:59   1×P3                0     50.000
 * 10  C2     DELIVERED             01/04/2025 00:15   30×P2               0  6.000.000
 * 11  C1     DELIVERED             10/06/2025 10:00   2×P3                0    100.000
 * </pre>
 *
 * <p>Đơn 2, 10 và khách C3 nằm ở 00:00–07:00 giờ VN: theo UTC chúng thuộc ngày / tháng trước — câu
 * nào cắt thời gian theo {@code TimeZone} của session thì lệch đúng ở đây.
 */
public final class DashboardFixtures {

  /** Giờ cửa hàng (Asia/Ho_Chi_Minh không có DST). */
  public static final ZoneOffset VN = ZoneOffset.ofHours(7);

  private final JdbcClient jdbc;
  private final Map<UUID, BigDecimal> priceByProduct = new HashMap<>();
  private final Map<UUID, UUID> addressByCustomer = new HashMap<>();

  public DashboardFixtures(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Đường đi trạng thái của đơn — quyết định status và các mốc thời gian. */
  public enum Path {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    /** Hủy khi còn PENDING — không có {@code paid_at}. */
    CANCELLED,
    /** PAID rồi mới hủy — status CANCELLED, vẫn giữ {@code paid_at}. */
    CANCELLED_AFTER_PAID,
    EXPIRED
  }

  /** Một dòng hàng: {@code quantity} × sản phẩm, giá lấy từ lúc tạo sản phẩm. */
  public record Item(UUID productId, int quantity) {}

  /** Id của bộ dữ liệu {@link #seedWorld()}; {@code orders} theo số thứ tự của bảng, từ 1. */
  public record World(
      UUID electronics,
      UUID phones,
      UUID fashion,
      UUID phoneX,
      UUID laptopY,
      UUID shirtZ,
      UUID gift,
      UUID c1,
      UUID c2,
      UUID c3,
      UUID c4,
      UUID c5,
      List<UUID> orders) {

    /** Đơn số {@code number} của bảng (1–11). */
    public UUID order(int number) {
      return orders.get(number - 1);
    }
  }

  /** {@code "2025-02-01T03:30"} theo giờ VN. */
  public static OffsetDateTime vn(String localDateTime) {
    return LocalDateTime.parse(localDateTime).atOffset(VN);
  }

  public static Item item(UUID productId, int quantity) {
    return new Item(productId, quantity);
  }

  /** Bộ dữ liệu 2025 ở Javadoc của class. */
  public World seedWorld() {
    UUID electronics = category("Dien tu", null);
    UUID phones = category("Dien thoai", electronics);
    UUID fashion = category("Thoi trang", null);
    UUID phoneX = product("Phone X", "DASH-P1", "100000", phones);
    UUID laptopY = product("Laptop Y", "DASH-P2", "200000", electronics);
    UUID shirtZ = product("Ao Z", "DASH-P3", "50000", fashion);
    UUID gift = product("Qua tang", "DASH-P4", "30000", null);
    UUID c1 = customer("dash-c1@example.com", "An", "Nguyen", vn("2025-01-05T10:00"));
    UUID c2 = customer("dash-c2@example.com", "Binh", "Tran", vn("2025-01-31T23:30"));
    UUID c3 = customer("dash-c3@example.com", "Chi", "Le", vn("2025-02-01T00:30"));
    UUID c4 = customer("dash-c4@example.com", "Dung", "Pham", vn("2025-01-20T09:00"));
    UUID c5 = customer("dash-c5@example.com", "Em", "Vo", vn("2024-03-01T09:00"));

    List<UUID> orders =
        List.of(
            order(
                c1,
                Path.DELIVERED,
                vn("2025-01-10T09:00"),
                "50000",
                item(phoneX, 2),
                item(shirtZ, 1)),
            order(c1, Path.PAID, vn("2025-02-01T03:30"), "0", item(laptopY, 1)),
            order(c2, Path.CANCELLED_AFTER_PAID, vn("2025-02-14T12:00"), "0", item(phoneX, 1)),
            order(c2, Path.DELIVERED, vn("2025-03-03T10:00"), "0", item(gift, 3), item(phoneX, 1)),
            order(c3, Path.DELIVERED, vn("2025-02-10T15:00"), "0", item(laptopY, 2)),
            order(c2, Path.EXPIRED, vn("2025-04-16T10:00"), "0", item(phoneX, 1)),
            order(c1, Path.SHIPPED, vn("2025-04-15T10:00"), "0", item(shirtZ, 4)),
            order(c5, Path.DELIVERED, vn("2024-04-20T10:00"), "0", item(phoneX, 1)),
            order(c3, Path.PENDING, vn("2025-04-30T23:59"), "0", item(shirtZ, 1)),
            order(c2, Path.DELIVERED, vn("2025-04-01T00:15"), "0", item(laptopY, 30)),
            order(c1, Path.DELIVERED, vn("2025-06-10T10:00"), "0", item(shirtZ, 2)));

    return new World(
        electronics, phones, fashion, phoneX, laptopY, shirtZ, gift, c1, c2, c3, c4, c5, orders);
  }

  /**
   * Tồn kho hiện tại + sổ tồn kho quanh tháng 4/2025 cho 4 sản phẩm của {@link #seedWorld()}. Đáp
   * số vòng quay tháng 4 (đầu kỳ / cuối kỳ dựng lại từ sổ):
   *
   * <pre>
   * Sản phẩm   đầu kỳ  bán  cuối kỳ  vòng quay
   * Phone X       100   30       70       0.35   COMMIT 03:00 ngày 01/05 giờ VN thuộc tháng 5
   * Ao Z           50    0       50       0.00
   * Laptop Y        0   10        0       null   nhập 10 rồi bán hết trong kỳ: tồn bình quân 0
   * Qua tang        0    0        0       null   không có sổ
   * </pre>
   */
  public void seedInventoryLog(World world) {
    inventory(world.phoneX(), 65);
    movement(world.phoneX(), "STOCK_IN", 100, 0, 100, vn("2025-03-01T09:00"));
    movement(world.phoneX(), "COMMIT", -10, 10, 90, vn("2025-04-10T09:00"));
    movement(world.phoneX(), "COMMIT", -20, 20, 70, vn("2025-04-20T09:00"));
    movement(world.phoneX(), "COMMIT", -5, 5, 65, vn("2025-05-01T03:00"));
    inventory(world.laptopY(), 0);
    movement(world.laptopY(), "STOCK_IN", 10, 0, 10, vn("2025-04-02T09:00"));
    movement(world.laptopY(), "COMMIT", -10, 10, 0, vn("2025-04-25T09:00"));
    inventory(world.shirtZ(), 45);
    movement(world.shirtZ(), "STOCK_IN", 50, 0, 50, vn("2025-03-15T09:00"));
    movement(world.shirtZ(), "COMMIT", -5, 5, 45, vn("2025-05-10T09:00"));
    inventory(world.gift(), 0);
  }

  public UUID category(String name, UUID parentId) {
    return jdbc.sql(
            """
            INSERT INTO app.categories (name, slug, parent_id)
            VALUES (:name, :slug, :parentId)
            RETURNING id
            """)
        .param("name", name)
        .param("slug", "dash-" + name.toLowerCase(Locale.ROOT).replace(' ', '-'))
        .param("parentId", parentId)
        .query(UUID.class)
        .single();
  }

  /** Sản phẩm ACTIVE; slug = sku viết thường. */
  public UUID product(String name, String sku, String price, UUID categoryId) {
    UUID id =
        jdbc.sql(
                """
                INSERT INTO app.products (name, slug, sku, price, status, category_id)
                VALUES (:name, :slug, :sku, :price, 'ACTIVE', :categoryId)
                RETURNING id
                """)
            .param("name", name)
            .param("slug", sku.toLowerCase(Locale.ROOT))
            .param("sku", sku)
            .param("price", new BigDecimal(price))
            .param("categoryId", categoryId)
            .query(UUID.class)
            .single();
    priceByProduct.put(id, new BigDecimal(price));
    return id;
  }

  /** Khách + một địa chỉ SHIPPING ({@code orders.shipping_address_id} là NOT NULL). */
  public UUID customer(String email, String firstName, String lastName, OffsetDateTime createdAt) {
    UUID id =
        jdbc.sql(
                """
                INSERT INTO app.customers (email, first_name, last_name, created_at)
                VALUES (:email, :firstName, :lastName, :createdAt)
                RETURNING id
                """)
            .param("email", email)
            .param("firstName", firstName)
            .param("lastName", lastName)
            .param("createdAt", createdAt)
            .query(UUID.class)
            .single();
    UUID addressId =
        jdbc.sql(
                """
                INSERT INTO app.addresses (customer_id, type, full_name, phone, line1, city)
                VALUES (:customerId, 'SHIPPING', 'Dash Fixture', '+84901234567', '1 Le Loi', 'HCM')
                RETURNING id
                """)
            .param("customerId", id)
            .query(UUID.class)
            .single();
    addressByCustomer.put(id, addressId);
    return id;
  }

  /**
   * Đơn của {@code customerId}: subtotal = tổng giá × số lượng, phí ship 0, total = subtotal −
   * {@code discount}. Mốc thời gian theo {@code path}: thanh toán sau 5 phút, giao cho vận chuyển
   * sau 1 ngày, giao xong sau 2 ngày, hủy / hết hạn sau 30 phút.
   */
  public UUID order(
      UUID customerId, Path path, OffsetDateTime createdAt, String discount, Item... items) {
    BigDecimal subtotal =
        Arrays.stream(items)
            .map(i -> priceByProduct.get(i.productId()).multiply(BigDecimal.valueOf(i.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discountAmount = new BigDecimal(discount);
    boolean paid =
        switch (path) {
          case PAID, SHIPPED, DELIVERED, CANCELLED_AFTER_PAID -> true;
          default -> false;
        };
    boolean shipped = path == Path.SHIPPED || path == Path.DELIVERED;
    boolean ended =
        path == Path.CANCELLED || path == Path.CANCELLED_AFTER_PAID || path == Path.EXPIRED;
    String status = path == Path.CANCELLED_AFTER_PAID ? "CANCELLED" : path.name();

    UUID orderId =
        jdbc.sql(
                """
                INSERT INTO app.orders (
                    customer_id, shipping_address_id, shipping_recipient, shipping_phone,
                    shipping_address_line1, shipping_city, shipping_country, order_number,
                    subtotal, discount_amount, shipping_fee, total, status, created_at,
                    paid_at, shipped_at, delivered_at, cancelled_at)
                VALUES (
                    :customerId, :addressId, 'Dash Fixture', '+84901234567',
                    '1 Le Loi', 'HCM', 'VN', 'ORD-DASH-' || nextval('app.orders_number_seq'),
                    :subtotal, :discount, 0, :total, :status, :createdAt,
                    :paidAt, :shippedAt, :deliveredAt, :cancelledAt)
                RETURNING id
                """)
            .param("customerId", customerId)
            .param("addressId", addressByCustomer.get(customerId))
            .param("subtotal", subtotal)
            .param("discount", discountAmount)
            .param("total", subtotal.subtract(discountAmount))
            .param("status", status)
            .param("createdAt", createdAt)
            .param("paidAt", paid ? createdAt.plusMinutes(5) : null)
            .param("shippedAt", shipped ? createdAt.plusDays(1) : null)
            .param("deliveredAt", path == Path.DELIVERED ? createdAt.plusDays(2) : null)
            .param("cancelledAt", ended ? createdAt.plusMinutes(30) : null)
            .query(UUID.class)
            .single();

    for (Item item : items) {
      jdbc.sql(
              """
              INSERT INTO app.order_items (
                  order_id, product_id, product_name, product_sku, quantity, unit_price)
              SELECT :orderId, p.id, p.name, p.sku, :quantity, p.price
              FROM app.products p
              WHERE p.id = :productId
              """)
          .param("orderId", orderId)
          .param("productId", item.productId())
          .param("quantity", item.quantity())
          .update();
    }
    return orderId;
  }

  /**
   * Refund COMPLETED {@code amount} cho đơn <b>đã thanh toán</b> ({@code paid_at} có giá trị): lần
   * đầu tạo payment đã thu đủ {@code total}, các lần sau dùng lại payment đó — trigger V15 kiểm
   * tổng refund ≤ số đã thu. Status của payment giữ CAPTURED: F07 chỉ đọc refund COMPLETED, không
   * đọc status payment.
   */
  public void completedRefund(UUID orderId, String amount) {
    UUID paymentId =
        jdbc.sql("SELECT id FROM app.payments WHERE order_id = :orderId")
            .param("orderId", orderId)
            .query(UUID.class)
            .optional()
            .orElseGet(() -> capturedPayment(orderId));
    jdbc.sql(
            """
            INSERT INTO app.payment_refunds (payment_id, idempotency_key, amount, reason, actor,
                                             status, refund_reference, completed_at)
            VALUES (:paymentId, 'DASH-REF-' || gen_random_uuid(), :amount, 'dashboard fixture',
                    'dash', 'COMPLETED', 're_dash_' || gen_random_uuid(), now())
            """)
        .param("paymentId", paymentId)
        .param("amount", new BigDecimal(amount))
        .update();
  }

  private UUID capturedPayment(UUID orderId) {
    return jdbc.sql(
            """
            INSERT INTO app.payments (order_id, idempotency_key, amount, gateway, status,
                                      payment_reference, captured_at)
            SELECT o.id, 'DASH-PAY-' || o.id, o.total, 'STRIPE', 'CAPTURED',
                   'pi_dash_' || o.id, o.paid_at
            FROM app.orders o
            WHERE o.id = :orderId
            RETURNING id
            """)
        .param("orderId", orderId)
        .query(UUID.class)
        .single();
  }

  /** Dòng {@code product_inventory} với tồn kho hiện tại {@code onHand}. */
  public void inventory(UUID productId, int onHand) {
    jdbc.sql(
            """
            INSERT INTO app.product_inventory (product_id, on_hand)
            VALUES (:productId, :onHand)
            """)
        .param("productId", productId)
        .param("onHand", onHand)
        .update();
  }

  /** Một dòng sổ tồn kho ở thời điểm {@code occurredAt}. */
  public void movement(
      UUID productId,
      String type,
      int deltaOnHand,
      int deltaSold,
      int onHandAfter,
      OffsetDateTime occurredAt) {
    jdbc.sql(
            """
            INSERT INTO app.inventory_movements (product_id, movement_type, delta_on_hand,
                                                 delta_sold, on_hand_after, reserved_after,
                                                 actor, occurred_at)
            VALUES (:productId, :type, :deltaOnHand, :deltaSold, :onHandAfter, 0,
                    'dash', :occurredAt)
            """)
        .param("productId", productId)
        .param("type", type)
        .param("deltaOnHand", deltaOnHand)
        .param("deltaSold", deltaSold)
        .param("onHandAfter", onHandAfter)
        .param("occurredAt", occurredAt)
        .update();
  }
}
