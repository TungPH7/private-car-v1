package com.tungph7.learnpostgres.feature.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemRequest;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.order.integration.OrderTestData.Buyer;
import com.tungph7.learnpostgres.feature.order.service.OrderService;

/**
 * {@link OrderService} trên Postgres thật — những thứ unit test với mock không chứng minh được:
 *
 * <ul>
 *   <li>placeOrder là MỘT transaction thật: bước giữa hỏng thì phần F03 đã ghi cũng biến mất.
 *   <li>READ COMMITTED: hai đơn cùng product chạy chồng nhau đều qua — REPEATABLE READ thì một đơn
 *       nhận {@code 40001}.
 *   <li>Bù trừ khi hủy / expire chạy thật trên tồn kho và coupon.
 *   <li>Job expire: đơn lỗi không kéo cả batch; đơn đang bị khóa thì bỏ qua, không đứng chờ.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi lời gọi service phải là một transaction
 * riêng, commit thật — cùng lý do với {@code CouponConcurrencyIntegrationTest}. Dữ liệu vì vậy phải
 * tự dọn, xem {@link #cleanup()}.
 *
 * <p>Job expire nền đã tắt ở profile test ({@code app.order.expiration.enabled: false}); test gọi
 * thẳng {@code expirePendingOrders()}.
 *
 * <p>Test concurrency của AC-20..24 ở {@link OrderConcurrencyIntegrationTest}; luồng qua HTTP ở
 * {@link OrderControllerIntegrationTest} (bài 05).
 */
@SpringBootTest
@Tag("integration")
class OrderServiceIntegrationTest extends AbstractContainerBaseTest {

  @Autowired OrderService orderService;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;

  private OrderTestData data;
  private UUID customerId;
  private UUID addressId;

  @BeforeEach
  void setUp() {
    data = new OrderTestData(jdbc, tx, "order-it-");
    cleanup();
    Buyer buyer = data.buyer();
    customerId = buyer.customerId();
    addressId = buyer.addressId();
  }

  /**
   * BẮT BUỘC dọn — thứ tự FK và lý do ở {@link OrderTestData#cleanup()}.
   *
   * <p>Chỉ {@code @AfterEach}: {@link #setUp()} đã tự gọi ở dòng đầu. Gắn thêm {@code @BeforeEach}
   * là thành hai method {@code @BeforeEach} không có thứ tự đảm bảo giữa chúng (bẫy của F04).
   */
  @AfterEach
  void cleanup() {
    data.cleanup();
  }

  // ================================================================
  // placeOrder
  // ================================================================

  @Test
  @DisplayName("placeOrder: order + items + history + giữ hàng + redeem, tất cả trong một lần gọi")
  void placeOrderWritesEveryTable() {
    UUID keyboard = data.activeProduct("100.00", 10);
    UUID mouse = data.activeProduct("12.50", 10);
    String code = data.couponCode();
    UUID couponId = data.percentCoupon(code, 10, 100);

    Order order =
        orderService.placeOrder(
            new PlaceOrderRequest(
                customerId,
                List.of(new OrderItemRequest(keyboard, 2), new OrderItemRequest(mouse, 1)),
                addressId,
                code,
                "Giao giờ hành chính"));

    assertThat(order.getOrderNumber()).matches("ORD-\\d{6}-\\d{6,}");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getSubtotal()).isEqualByComparingTo("212.50");
    assertThat(order.getDiscountAmount()).isEqualByComparingTo("21.25");
    assertThat(order.getTotal()).isEqualByComparingTo("191.25");
    assertThat(data.statusOf(order.getId())).isEqualTo("PENDING");

    // line_total do DB tính (GENERATED STORED)
    assertThat(
            jdbc.queryForList(
                "SELECT line_total FROM app.order_items WHERE order_id = ? ORDER BY line_total",
                BigDecimal.class,
                order.getId()))
        .containsExactly(new BigDecimal("12.50"), new BigDecimal("200.00"));
    assertThat(
            data.count(
                "SELECT count(*) FROM app.order_status_history WHERE order_id = ?"
                    + " AND from_status IS NULL AND to_status = 'PENDING' AND actor = ?",
                order.getId(),
                customerId.toString()))
        .isOne();

    assertThat(data.reservedOf(keyboard)).isEqualTo(2);
    assertThat(data.reservedOf(mouse)).isEqualTo(1);
    assertThat(
            data.count(
                "SELECT count(*) FROM app.inventory_movements"
                    + " WHERE order_id = ? AND movement_type = 'RESERVE'",
                order.getId()))
        .isEqualTo(2);

    assertThat(data.usedCountOf(couponId)).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT discount_amount FROM app.coupon_redemptions WHERE order_id = ?",
                BigDecimal.class,
                order.getId()))
        .isEqualByComparingTo("21.25");
  }

  /**
   * AC-17. Product nhiều hàng có productId nhỏ hơn nên được giữ TRƯỚC (bulkReserve sắp theo
   * productId) — tới product thiếu hàng thì phần đã giữ phải biến mất cùng transaction.
   */
  @Test
  @DisplayName("AC-17: một dòng thiếu hàng → rollback cả đơn, kể cả dòng đã giữ hàng trước đó")
  void failedStepRollsBackEverything() {
    List<UUID> sorted = Stream.generate(UUID::randomUUID).limit(2).sorted().toList();
    UUID plenty = data.activeProduct(sorted.get(0), "10.00", 10);
    UUID scarce = data.activeProduct(sorted.get(1), "10.00", 1);

    Throwable thrown =
        catchThrowable(
            () ->
                orderService.placeOrder(
                    new PlaceOrderRequest(
                        customerId,
                        List.of(new OrderItemRequest(scarce, 5), new OrderItemRequest(plenty, 2)),
                        addressId,
                        null,
                        null)));

    assertThat(thrown).isInstanceOf(ConflictException.class);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.INV_INSUFFICIENT_STOCK);
    assertThat(data.count("SELECT count(*) FROM app.orders WHERE customer_id = ?", customerId))
        .isZero();
    assertThat(data.reservedOf(plenty)).isZero();
    assertThat(data.reservedOf(scarce)).isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.inventory_movements WHERE product_id IN (?, ?)",
                plenty,
                scarce))
        .isZero();
  }

  /**
   * Chốt lựa chọn READ COMMITTED của placeOrder.
   *
   * <p>Một giao dịch "chen ngang" khóa row tồn kho ({@code FOR UPDATE}, không sửa gì) rồi mới thả 2
   * đơn. Đợi tới khi CẢ HAI đã chạy qua các câu đọc đầu tiên (snapshot đã chụp) và đang đứng chờ
   * khóa, mới nhả. Đơn thứ nhất ghi xong; đơn thứ hai UPDATE một row vừa bị giao dịch khác sửa:
   *
   * <ul>
   *   <li>READ COMMITTED — đánh giá lại {@code on_hand - reserved >= qty} trên row mới → còn hàng →
   *       ghi tiếp. Cả 2 đơn thành công.
   *   <li>REPEATABLE READ — {@code 40001 could not serialize access due to concurrent update}, dù
   *       kho còn 8 cái. Đổi isolation của placeOrder sang REPEATABLE READ là test này đỏ.
   * </ul>
   */
  @Test
  @DisplayName("2 đơn chồng nhau trên cùng product (kho đủ) → cả 2 thành công, không 40001")
  void overlappingOrdersOnSameProductBothSucceed() throws Exception {
    UUID product = data.activeProduct("10.00", 10);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection blocker =
        LockTestSupport.holdRowLock(
            dataSource,
            "SELECT 1 FROM app.product_inventory WHERE product_id = ? FOR UPDATE",
            product)) {
      Future<Order> first = pool.submit(() -> orderService.placeOrder(oneItem(product, 1)));
      Future<Order> second = pool.submit(() -> orderService.placeOrder(oneItem(product, 1)));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      blocker.commit();

      assertThat(first.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS).getStatus())
          .isEqualTo(OrderStatus.PENDING);
      assertThat(second.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS).getStatus())
          .isEqualTo(OrderStatus.PENDING);
    } finally {
      pool.shutdownNow();
    }
    assertThat(data.reservedOf(product)).isEqualTo(2);
  }

  // ================================================================
  // Chuyển trạng thái + bù trừ
  // ================================================================

  @Test
  @DisplayName("PENDING → PAID → SHIPPED → DELIVERED: trả đơn đọc lại, DELIVERED chốt tồn kho")
  void lifecycleToDelivered() {
    UUID product = data.activeProduct("100.00", 10);
    Order placed = orderService.placeOrder(oneItem(product, 3));

    Order paid = orderService.markPaid(placed.getId());
    Order shipped = orderService.markShipped(placed.getId());
    Order delivered = orderService.markDelivered(placed.getId());

    assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(paid.getPaidAt()).isNotNull();
    assertThat(shipped.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    assertThat(shipped.getShippedAt()).isNotNull();
    assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    assertThat(delivered.getDeliveredAt()).isNotNull();
    assertThat(
            jdbc.queryForMap(
                "SELECT on_hand, reserved, sold FROM app.product_inventory WHERE product_id = ?",
                product))
        .containsEntry("on_hand", 7)
        .containsEntry("reserved", 0)
        .containsEntry("sold", 3L); // sold là BIGINT (V8)

    // Detail: JSON thật từ Postgres, parse bằng ObjectMapper thật của app.
    OrderDetailResponse detail = orderService.getDetail(placed.getId());
    assertThat(detail.status()).isEqualTo("DELIVERED");
    assertThat(detail.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.lineTotal()).isEqualTo(new BigDecimal("300.00"));
              assertThat(item.productSku()).isEqualTo("SKU-" + product);
            });
    assertThat(detail.history())
        .extracting(StatusHistoryResponse::fromStatus, StatusHistoryResponse::toStatus)
        .containsExactly(
            tuple(null, "PENDING"),
            tuple("PENDING", "PAID"),
            tuple("PAID", "SHIPPED"),
            tuple("SHIPPED", "DELIVERED"));
    assertThat(detail.history()).allSatisfy(h -> assertThat(h.changedAt()).isNotNull());
  }

  @Test
  @DisplayName("cancelOrder: CANCELLED + nhả hàng + hoàn lượt coupon, cùng một transaction")
  void cancelCompensatesStockAndCoupon() {
    UUID product = data.activeProduct("50.00", 5);
    String code = data.couponCode();
    UUID couponId = data.percentCoupon(code, 10, 100);
    Order order =
        orderService.placeOrder(
            new PlaceOrderRequest(
                customerId, List.of(new OrderItemRequest(product, 2)), addressId, code, null));
    assertThat(data.reservedOf(product)).isEqualTo(2);
    assertThat(data.usedCountOf(couponId)).isOne();

    orderService.cancelOrder(order.getId(), "Khách đổi ý", "customer");

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, cancel_reason, cancelled_at FROM app.orders WHERE id = ?",
            order.getId());
    assertThat(row)
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancel_reason", "Khách đổi ý");
    assertThat(row.get("cancelled_at")).isNotNull();
    assertThat(data.reservedOf(product)).isZero();
    assertThat(data.usedCountOf(couponId)).isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.coupon_redemptions WHERE order_id = ?", order.getId()))
        .isZero();
    assertThat(data.movementsOf(order.getId())).containsExactly("RESERVE", "RELEASE");
  }

  // ================================================================
  // Job expire
  // ================================================================

  /**
   * Đơn lỗi đứng ĐẦU batch (cũ nhất). Cả batch trong một transaction thì nó kéo đơn thứ hai
   * rollback theo — và lần chạy sau lại đứng đầu, lại lỗi: job kẹt vĩnh viễn.
   */
  @Test
  @DisplayName("expire: đơn lỗi không kéo cả batch — đơn khác vẫn EXPIRED, đơn lỗi thử lại lần sau")
  void failingOrderDoesNotBlockTheBatch() {
    UUID fragile = data.activeProduct("10.00", 5);
    UUID healthy = data.activeProduct("10.00", 5);
    Order poisoned = orderService.placeOrder(oneItem(fragile, 1));
    Order normal = orderService.placeOrder(oneItem(healthy, 1));
    data.makeOverdue(poisoned.getId(), "3 hours");
    data.makeOverdue(normal.getId(), "2 hours");
    // Tồn kho bị chỉnh tay: reserved về 0 → release 1 cái của đơn này ném INV_RELEASE_INVALID.
    data.setReserved(fragile, 0);

    assertThat(orderService.expirePendingOrders()).isEqualTo(1);

    assertThat(data.statusOf(poisoned.getId())).isEqualTo("PENDING");
    Map<String, Object> expired =
        jdbc.queryForMap(
            "SELECT status, cancel_reason, cancelled_at FROM app.orders WHERE id = ?",
            normal.getId());
    assertThat(expired)
        .containsEntry("status", "EXPIRED")
        .containsEntry("cancel_reason", "Auto-expired: unpaid after PT30M");
    assertThat(expired.get("cancelled_at")).isNotNull();
    assertThat(data.reservedOf(healthy)).isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.order_status_history WHERE order_id = ?"
                    + " AND from_status = 'PENDING' AND to_status = 'EXPIRED' AND actor = 'system'",
                normal.getId()))
        .isOne();

    // Sửa tồn kho → lần chạy sau expire nốt đơn còn lại.
    data.setReserved(fragile, 1);
    assertThat(orderService.expirePendingOrders()).isEqualTo(1);
    assertThat(data.statusOf(poisoned.getId())).isEqualTo("EXPIRED");
  }

  /**
   * Một giao dịch khác (vd khách đang thanh toán) giữ khóa đơn cũ nhất. Có SKIP LOCKED thì job bỏ
   * qua nó và xử lý tiếp; không có thì job đứng chờ tới khi giao dịch kia xong — ở đây là không bao
   * giờ, vì test chỉ nhả khóa sau khi job trả kết quả, nên {@code get} hết giờ và test đỏ thay vì
   * treo.
   */
  @Test
  @DisplayName(
      "expire: đơn đang bị giao dịch khác khóa → bỏ qua ngay, không đứng chờ (SKIP LOCKED)")
  void lockedOrderIsSkippedNotAwaited() throws Exception {
    UUID product = data.activeProduct("10.00", 5);
    Order busy = orderService.placeOrder(oneItem(product, 1));
    Order idle = orderService.placeOrder(oneItem(product, 1));
    data.makeOverdue(busy.getId(), "2 hours");
    data.makeOverdue(idle.getId(), "1 hour");

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection payment =
        LockTestSupport.holdRowLock(
            dataSource, "SELECT 1 FROM app.orders WHERE id = ? FOR UPDATE", busy.getId())) {
      Future<Integer> run = pool.submit(orderService::expirePendingOrders);

      assertThat(run.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(data.statusOf(busy.getId())).isEqualTo("PENDING");
      assertThat(data.statusOf(idle.getId())).isEqualTo("EXPIRED");
      payment.rollback();
    } finally {
      pool.shutdownNow();
    }

    assertThat(orderService.expirePendingOrders()).isEqualTo(1);
    assertThat(data.statusOf(busy.getId())).isEqualTo("EXPIRED");
    assertThat(data.reservedOf(product)).isZero();
  }

  // ================================================================
  // Helpers
  // ================================================================

  private PlaceOrderRequest oneItem(UUID productId, int quantity) {
    return new PlaceOrderRequest(
        customerId, List.of(new OrderItemRequest(productId, quantity)), addressId, null, null);
  }
}
