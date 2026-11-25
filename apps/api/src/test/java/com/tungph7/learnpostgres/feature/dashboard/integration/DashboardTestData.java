package com.tungph7.learnpostgres.feature.dashboard.integration;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.Item;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.Path;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.World;

/**
 * Seed + dọn dữ liệu cho integration test của dashboard: bộ dữ liệu 2025 của {@link
 * DashboardFixtures#seedWorld()} (đáp số tính tay ở {@code
 * F07__dashboard/02-domain-and-repository.md §5.1}), COMMIT thật — request HTTP chạy trong
 * transaction của chính nó, chỉ thấy thứ đã commit. Cùng khuôn {@code PaymentTestData} (F06).
 *
 * <p>Mọi lần ghi bọc {@link TransactionTemplate}: Hikari đặt {@code auto-commit: false}, và {@code
 * SpringBootTest} không có transaction bao quanh — ghi ngoài transaction thì bị rollback lặng lẽ
 * khi connection về pool (QnA Q26).
 *
 * <p>Một {@link DashboardFixtures} cho cả một test: {@code order(...)} tra giá sản phẩm và địa chỉ
 * của khách trong bộ nhớ của chính instance đã dựng chúng — gọi sau {@link #seedWorld()}.
 *
 * <p>Dọn theo đúng các tên {@code seedWorld()} đặt — khách {@code dash-c_@example.com}, sản phẩm
 * {@code DASH-P_}, ba category {@code dash-...} ({@code _} của {@code LIKE} khớp đúng một ký tự) —
 * nên dọn được cả dữ liệu một lần chạy trước bỏ dở, và không chạm dữ liệu {@code dash-svc-} của
 * {@code DashboardServiceIntegrationTest}. Đơn, payment, refund dọn theo khách; sổ tồn kho theo sản
 * phẩm. Test thêm dữ liệu thì dùng khách và sản phẩm của {@code seedWorld()}.
 */
final class DashboardTestData {

  private static final String CUSTOMERS = "dash-c_@example.com";
  private static final String PRODUCTS = "DASH-P_";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final DashboardFixtures fixtures;

  DashboardTestData(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.fixtures = new DashboardFixtures(jdbc);
  }

  // ================================================================
  // Seed
  // ================================================================

  World seedWorld() {
    return tx.execute(status -> fixtures.seedWorld());
  }

  /** Tồn kho + sổ tồn kho quanh tháng 4/2025 — đáp số ở {@link DashboardFixtures}. */
  void seedInventoryLog(World world) {
    tx.executeWithoutResult(status -> fixtures.seedInventoryLog(world));
  }

  UUID order(UUID customerId, Path path, OffsetDateTime createdAt, String discount, Item... items) {
    return tx.execute(status -> fixtures.order(customerId, path, createdAt, discount, items));
  }

  void completedRefund(UUID orderId, String amount) {
    tx.executeWithoutResult(status -> fixtures.completedRefund(orderId, amount));
  }

  /** Đưa MV về trạng thái chưa có dữ liệu — như người vận hành chạy {@code ... WITH NO DATA}. */
  void unpopulateRevenueView() {
    tx.executeWithoutResult(
        status -> jdbc.sql("REFRESH MATERIALIZED VIEW app.mv_daily_revenue WITH NO DATA").update());
  }

  // ================================================================
  // Đọc
  // ================================================================

  /** Địa chỉ giao hàng {@code seedWorld()} tạo cho khách — mỗi khách đúng một địa chỉ. */
  UUID shippingAddress(UUID customerId) {
    return jdbc.sql("SELECT id FROM app.addresses WHERE customer_id = :customerId")
        .param("customerId", customerId)
        .query(UUID.class)
        .single();
  }

  // ================================================================
  // Dọn
  // ================================================================

  /**
   * Xoá mọi row của bộ dữ liệu theo thứ tự khóa ngoại, rồi làm mới MV:
   *
   * <ul>
   *   <li>refund → payment → đơn (CASCADE dòng hàng và lịch sử trạng thái);
   *   <li>sổ tồn kho → sản phẩm (CASCADE tồn kho) → category con → category gốc;
   *   <li>khách (CASCADE địa chỉ).
   * </ul>
   *
   * <p>Refresh KHÔNG {@code CONCURRENTLY}: chạy được cả khi test để MV ở {@code WITH NO DATA}. MV
   * dùng chung mọi lớp test trong JVM — để lại các ngày của bộ dữ liệu là để lại rác cho lớp khác.
   */
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.sql(
                  """
                  DELETE FROM app.payment_refunds r
                  USING app.payments p, app.orders o, app.customers c
                  WHERE r.payment_id = p.id AND p.order_id = o.id AND o.customer_id = c.id
                    AND c.email LIKE :customers
                  """)
              .param("customers", CUSTOMERS)
              .update();
          jdbc.sql(
                  """
                  DELETE FROM app.payments p USING app.orders o, app.customers c
                  WHERE p.order_id = o.id AND o.customer_id = c.id AND c.email LIKE :customers
                  """)
              .param("customers", CUSTOMERS)
              .update();
          jdbc.sql(
                  """
                  DELETE FROM app.orders o USING app.customers c
                  WHERE o.customer_id = c.id AND c.email LIKE :customers
                  """)
              .param("customers", CUSTOMERS)
              .update();
          jdbc.sql(
                  """
                  DELETE FROM app.inventory_movements m USING app.products p
                  WHERE m.product_id = p.id AND p.sku LIKE :products
                  """)
              .param("products", PRODUCTS)
              .update();
          jdbc.sql("DELETE FROM app.products WHERE sku LIKE :products")
              .param("products", PRODUCTS)
              .update();
          jdbc.sql("DELETE FROM app.categories WHERE slug = 'dash-dien-thoai'").update();
          jdbc.sql("DELETE FROM app.categories WHERE slug IN ('dash-dien-tu', 'dash-thoi-trang')")
              .update();
          jdbc.sql("DELETE FROM app.customers WHERE email LIKE :customers")
              .param("customers", CUSTOMERS)
              .update();
          jdbc.sql("REFRESH MATERIALIZED VIEW app.mv_daily_revenue").update();
        });
  }
}
