package com.tungph7.learnpostgres.feature.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.ConcurrencyTestSupport;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemRequest;
import com.tungph7.learnpostgres.feature.order.integration.OrderTestData.Buyer;
import com.tungph7.learnpostgres.feature.order.service.OrderService;

/**
 * AC-20..24 — những gì chỉ lộ ra khi nhiều giao dịch chạy chồng nhau thật.
 *
 * <p>Hai cách tạo chồng lấn, chọn theo thứ đang đo:
 *
 * <ul>
 *   <li><b>Cổng khóa</b> ({@link LockTestSupport}) cho AC-20, 22, 23 — kết luận phụ thuộc vào việc
 *       2 giao dịch GẶP NHAU ở đúng một câu ghi. Một giao dịch khác khóa row trước, đợi cả hai đứng
 *       chờ khóa đó rồi mới nhả: chồng lấn chắc chắn xảy ra, không phụ thuộc may rủi.
 *   <li><b>Starting gate</b> ({@link ConcurrencyTestSupport}) cho AC-21, 24 — kết quả đúng phải
 *       giống nhau với MỌI cách xen kẽ (5/5, mỗi đơn đúng 1 lần), nên thả nhiều thread cùng lúc là
 *       đủ.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi lời gọi service phải là một transaction
 * riêng, commit thật — không thì không còn tranh chấp nào để đo. Dữ liệu vì vậy phải tự dọn, xem
 * {@link #cleanup()}.
 *
 * <p>Hikari profile test chỉ có 5 connection: số thread lớn hơn là số thread ĐỢI nhau ở cửa pool,
 * không phải số transaction chạy song song thật.
 */
@SpringBootTest
@Tag("integration")
class OrderConcurrencyIntegrationTest extends AbstractContainerBaseTest {

  /** AC-21: 10 customer tranh 5 lượt của một coupon. */
  private static final int COUPON_CUSTOMERS = 10;

  private static final int COUPON_MAX_USES = 5;

  /** AC-24: số đơn quá hạn chờ job, và số lần chạy job cùng lúc. */
  private static final int OVERDUE_ORDERS = 20;

  private static final int EXPIRE_RUNS = 100;

  private static final String INVENTORY_ROW_LOCK =
      "SELECT 1 FROM app.product_inventory WHERE product_id = ? FOR UPDATE";

  @Autowired OrderService orderService;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;

  private OrderTestData data;

  @BeforeEach
  void setUp() {
    data = new OrderTestData(jdbc, tx, "order-conc-");
    cleanup();
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
  // AC-20 — món hàng cuối cùng
  // ================================================================

  /**
   * AC-20. Hai customer cùng đặt món cuối cùng, gặp nhau ở câu giữ hàng.
   *
   * <p>Bên đến sau đợi khóa row tồn kho của bên đến trước; bên kia commit thì READ COMMITTED đánh
   * giá lại {@code on_hand - reserved >= 1} trên row MỚI → 0 row → {@code INV_INSUFFICIENT_STOCK}.
   * Ở REPEATABLE READ bên thua nhận {@code 40001} (thành {@code CONCURRENT_UPDATE_CONFLICT}) — sai
   * mã AC đòi, và client sẽ retry một đơn không bao giờ thành.
   *
   * <p>Cổng khóa chỉ nhả khi {@code pg_stat_activity} thấy CẢ HAI đơn đang chờ khóa, nên lần chạy
   * nào hai đơn cũng gặp nhau ở câu đó. Starting gate thì dựa vào thời gian: đơn này commit xong
   * trước khi đơn kia bắt đầu đọc là REPEATABLE READ cũng ra đúng mã, và test không còn phân biệt
   * được hai isolation.
   */
  @Test
  @DisplayName("AC-20: 2 customer tranh món cuối cùng → 1 đơn, bên kia INV_INSUFFICIENT_STOCK")
  void lastUnitGoesToExactlyOneCustomer() throws Exception {
    UUID product = data.activeProduct("100.00", 1);
    Buyer alice = data.buyer();
    Buyer bob = data.buyer();

    List<Outcome<Order>> outcomes =
        raceThroughLock(
            () -> orderService.placeOrder(alice.order(new OrderItemRequest(product, 1))),
            () -> orderService.placeOrder(bob.order(new OrderItemRequest(product, 1))),
            INVENTORY_ROW_LOCK,
            product);

    assertOneWinner(outcomes, ErrorCode.INV_INSUFFICIENT_STOCK);

    assertThat(
            jdbc.queryForMap(
                "SELECT on_hand, reserved, sold FROM app.product_inventory WHERE product_id = ?",
                product))
        .containsEntry("on_hand", 1)
        .containsEntry("reserved", 1)
        .containsEntry("sold", 0L);
    // Bên thua không để lại gì: không đơn, không movement.
    assertThat(
            data.count(
                "SELECT count(*) FROM app.orders WHERE customer_id IN (?, ?)",
                alice.customerId(),
                bob.customerId()))
        .isOne();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.inventory_movements WHERE product_id = ?", product))
        .isOne();
  }

  // ================================================================
  // AC-21 — coupon hết lượt giữa chừng
  // ================================================================

  /**
   * AC-21. Khác test counter của F04 ({@code CouponConcurrencyIntegrationTest}): ở đây redeem là
   * bước CUỐI của placeOrder, sau khi đơn đã ghi và hàng đã giữ. Bên thua ở bước đó phải rollback
   * cả đơn — {@code reserved} phải là 5 chứ không phải 10.
   *
   * <p>Bên thua trượt ở 1 trong 2 chỗ, cùng một mã: {@code validate} đọc thấy hết lượt (chưa ghi
   * gì), hoặc {@code redeem} — UPDATE atomic ra 0 row sau khi đã giữ hàng. Kết quả đúng không phụ
   * thuộc cách xen kẽ, nên dùng starting gate thay vì cổng khóa.
   */
  @Test
  @DisplayName("AC-21: 10 customer, coupon max_uses = 5 → 5 đơn, 5 CPN_EXHAUSTED, không giữ thừa")
  void couponLimitRollsBackTheWholeOrder() {
    UUID product = data.activeProduct("100.00", 100);
    String code = data.couponCode();
    UUID coupon = data.percentCoupon(code, 10, COUPON_MAX_USES);
    List<Buyer> buyers = Stream.generate(data::buyer).limit(COUPON_CUSTOMERS).toList();
    AtomicInteger next = new AtomicInteger();

    List<Outcome<Order>> outcomes =
        ConcurrencyTestSupport.runConcurrently(
            COUPON_CUSTOMERS,
            () -> {
              Buyer buyer = buyers.get(next.getAndIncrement());
              return Outcome.of(
                  () ->
                      orderService.placeOrder(
                          buyer.orderWithCoupon(code, new OrderItemRequest(product, 1))));
            });

    assertThat(outcomes).doesNotContainNull();
    assertOnlyFailure(outcomes, ErrorCode.CPN_EXHAUSTED);
    assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(COUPON_MAX_USES);

    assertThat(data.usedCountOf(coupon)).isEqualTo(COUPON_MAX_USES);
    assertThat(
            data.count("SELECT count(*) FROM app.coupon_redemptions WHERE coupon_id = ?", coupon))
        .isEqualTo(COUPON_MAX_USES);
    assertThat(data.count("SELECT count(*) FROM app.orders WHERE coupon_id = ?", coupon))
        .isEqualTo(COUPON_MAX_USES);
    // 5 đơn thua ở bước redeem đã giữ hàng trước đó — rollback phải trả lại.
    assertThat(data.reservedOf(product)).isEqualTo(COUPON_MAX_USES);
    assertThat(
            data.count(
                "SELECT count(*) FROM app.inventory_movements WHERE product_id = ?", product))
        .isEqualTo(COUPON_MAX_USES);
  }

  // ================================================================
  // AC-22 — thứ tự dòng hàng ngược nhau
  // ================================================================

  /**
   * AC-22. Hai đơn cùng 2 product, liệt kê ngược nhau: {@code [P1, P2]} và {@code [P2, P1]}.
   *
   * <p>Cổng khóa giữ CẢ HAI row tồn kho, đợi cả 2 đơn đứng chờ, rồi nhả. Nếu mỗi đơn giữ hàng theo
   * thứ tự trong request, lúc nhả đơn thứ nhất đang cầm P1 và xin P2, đơn thứ hai cầm P2 và xin P1
   * — vòng chờ, Postgres giết một bên sau {@code deadlock_timeout} ({@code 40P01}). {@code
   * bulkReserve} sắp theo productId nên cả hai cùng xin row nhỏ hơn trước: bên đến sau chỉ đợi,
   * không có vòng.
   *
   * <p>Khác stress test kiểu "20 giao dịch xáo thứ tự rồi đếm lỗi" ({@code
   * InventoryConcurrencyIntegrationTest.bulkReserveCrossOrder} của F03): bỏ sort thì cách đó cũng
   * đỏ, nhưng nhờ xác suất các giao dịch đan vào nhau, và chỉ báo {@code expected: 0 but was: 19} —
   * không nói lỗi gì. Cổng khóa dựng đúng thế đan của AC — 2 đơn, thứ tự ngược — và nếu sai thì báo
   * đúng một {@code 40P01 deadlock detected}.
   */
  @Test
  @DisplayName("AC-22: [P1, P2] và [P2, P1] chồng nhau → cả 2 thành công, không deadlock")
  void reversedItemOrderDoesNotDeadlock() throws Exception {
    UUID p1 = data.activeProduct("10.00", 10);
    UUID p2 = data.activeProduct("20.00", 10);
    Buyer buyer = data.buyer();

    List<Outcome<Order>> outcomes =
        raceThroughLock(
            () ->
                orderService.placeOrder(
                    buyer.order(new OrderItemRequest(p1, 1), new OrderItemRequest(p2, 1))),
            () ->
                orderService.placeOrder(
                    buyer.order(new OrderItemRequest(p2, 1), new OrderItemRequest(p1, 1))),
            "SELECT 1 FROM app.product_inventory WHERE product_id IN (?, ?) FOR UPDATE",
            p1,
            p2);

    assertThat(outcomes)
        .as("Cả 2 đơn phải thành công: %s", describe(outcomes))
        .allMatch(Outcome::succeeded);
    assertThat(data.reservedOf(p1)).isEqualTo(2);
    assertThat(data.reservedOf(p2)).isEqualTo(2);
  }

  // ================================================================
  // AC-23 — hai lần chuyển trạng thái cùng lúc
  // ================================================================

  /**
   * AC-23. Khách hủy đúng lúc kho bấm giao — cùng một đơn PAID.
   *
   * <p>Cả hai cùng đọc đơn PAID, cùng qua state machine ở app (PAID → CANCELLED và PAID → SHIPPED
   * đều hợp lệ), rồi gặp nhau ở {@code UPDATE ... WHERE status = 'PAID'}. Chỉ guard ở DB phân xử
   * được: bên sau đợi khóa row đơn, bên trước commit thì Postgres đánh giá lại WHERE trên row mới →
   * 0 row → {@code ORD_RACE_CONDITION}, và giao dịch thua rollback trước khi kịp bù trừ gì.
   *
   * <p>Bên nào thắng là việc của scheduler; test chỉ đòi dữ liệu KHỚP bên thắng. Bỏ guard {@code
   * status = :from} thì cả hai cùng ghi — có thể ra đơn SHIPPED mà hàng đã nhả và coupon đã hoàn.
   *
   * <p>AC ghi "placeOrder + admin transition": một lần chuyển trạng thái không thể chạy chồng với
   * chính lần đặt ra đơn đó — đơn chưa commit thì giao dịch khác chưa thấy nó (READ COMMITTED). Chỗ
   * duy nhất đặt đơn gặp chuyển trạng thái là các row tồn kho / coupon dùng chung, đã có AC-20..22.
   */
  @Test
  @DisplayName("AC-23: hủy và giao cùng lúc một đơn PAID → 1 bên thắng, bên kia ORD_RACE_CONDITION")
  void cancelAndShipRaceHasOneWinner() throws Exception {
    UUID product = data.activeProduct("50.00", 10);
    String code = data.couponCode();
    UUID coupon = data.percentCoupon(code, 10, 100);
    Buyer buyer = data.buyer();
    UUID orderId =
        orderService
            .placeOrder(buyer.orderWithCoupon(code, new OrderItemRequest(product, 2)))
            .getId();
    orderService.markPaid(orderId);

    List<Outcome<OrderStatus>> outcomes =
        raceThroughLock(
            () -> {
              orderService.cancelOrder(orderId, "Khach doi y", buyer.customerId().toString());
              return OrderStatus.CANCELLED;
            },
            () -> orderService.markShipped(orderId).getStatus(),
            "SELECT 1 FROM app.orders WHERE id = ? FOR UPDATE",
            orderId);

    assertOneWinner(outcomes, ErrorCode.ORD_RACE_CONDITION);

    OrderStatus winner =
        outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
    boolean cancelled = winner == OrderStatus.CANCELLED;
    assertThat(data.statusOf(orderId)).isEqualTo(winner.name());
    assertThat(
            jdbc.queryForList(
                "SELECT to_status FROM app.order_status_history"
                    + " WHERE order_id = ? AND from_status = 'PAID'",
                String.class,
                orderId))
        .containsExactly(winner.name());
    // Bù trừ chạy đúng một lần nếu hủy thắng, không chạy nếu giao thắng.
    assertThat(data.reservedOf(product)).isEqualTo(cancelled ? 0 : 2);
    assertThat(data.usedCountOf(coupon)).isEqualTo(cancelled ? 0 : 1);
    assertThat(data.movementsOf(orderId))
        .containsExactlyElementsOf(cancelled ? List.of("RESERVE", "RELEASE") : List.of("RESERVE"));
  }

  // ================================================================
  // AC-24 — nhiều lần chạy job cùng lúc
  // ================================================================

  /**
   * AC-24. 100 lần chạy job cùng lúc trên cùng 20 đơn quá hạn — như nhiều instance cùng tới lịch.
   *
   * <p>Mọi lần chạy đọc cùng một danh sách ứng viên (câu đọc không khóa), rồi tranh nhau từng đơn.
   * Đơn đang bị lần chạy khác giữ thì {@code SKIP LOCKED} bỏ qua; đơn đã EXPIRED thì câu khóa kiểm
   * lại {@code status = 'PENDING'} và không trả gì. Tổng số đơn các lần chạy báo expire phải đúng
   * bằng 20, và mỗi đơn chỉ được bù trừ một lần.
   *
   * <p>Assert cuối (không dòng ERROR nào của job) là vì "đúng 1 lần" có nhiều lớp giữ: bỏ {@code
   * status = 'PENDING'} khỏi câu khóa thì state machine ở app vẫn chặn lần expire thứ hai — số liệu
   * vẫn đúng, nhưng mỗi lần chặn là một exception bị job ghi log ERROR (một lần chạy test đo được
   * 1.511 dòng). Đơn đã có lần chạy khác xử lý là chuyện bình thường của job, không phải lỗi cần
   * người vận hành nhìn. Lọc theo cả "ERROR" lẫn tên lớp để khớp cả log JSON lẫn log dạng text.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("AC-24: 100 lần chạy job cùng lúc trên 20 đơn quá hạn → mỗi đơn EXPIRED đúng 1 lần")
  void parallelExpireRunsExpireEachOrderOnce(CapturedOutput output) {
    UUID product = data.activeProduct("10.00", OVERDUE_ORDERS);
    Buyer buyer = data.buyer();
    List<UUID> orders =
        IntStream.range(0, OVERDUE_ORDERS)
            .mapToObj(
                i -> orderService.placeOrder(buyer.order(new OrderItemRequest(product, 1))).getId())
            .toList();
    orders.forEach(orderId -> data.makeOverdue(orderId, "1 hour"));

    List<Integer> expiredPerRun =
        ConcurrencyTestSupport.runConcurrently(EXPIRE_RUNS, orderService::expirePendingOrders);

    assertThat(expiredPerRun).as("Có lần chạy job ném ra ngoài").doesNotContainNull();
    assertThat(expiredPerRun.stream().mapToInt(Integer::intValue).sum()).isEqualTo(OVERDUE_ORDERS);
    assertThat(
            data.count(
                "SELECT count(*) FROM app.orders WHERE customer_id = ? AND status = 'EXPIRED'",
                buyer.customerId()))
        .isEqualTo(OVERDUE_ORDERS);
    // Mỗi đơn đúng 1 dòng lịch sử PENDING → EXPIRED và đúng 1 lần nhả hàng.
    assertThat(
            jdbc.queryForList(
                """
                SELECT o.id FROM app.orders o
                WHERE o.customer_id = ?
                  AND ((SELECT count(*) FROM app.order_status_history h
                        WHERE h.order_id = o.id AND h.to_status = 'EXPIRED') <> 1
                    OR (SELECT count(*) FROM app.inventory_movements m
                        WHERE m.order_id = o.id AND m.movement_type = 'RELEASE') <> 1)
                """,
                UUID.class,
                buyer.customerId()))
        .as("Đơn bị expire / nhả hàng khác 1 lần")
        .isEmpty();
    assertThat(data.reservedOf(product)).isZero();
    List<String> jobErrors =
        output
            .getAll()
            .lines()
            .filter(line -> line.contains("ERROR") && line.contains("OrderServiceImpl"))
            .toList();
    assertThat(jobErrors.size())
        .as(
            "Job ghi lỗi cho đơn mà lần chạy khác đã xử lý — dòng đầu: %s",
            jobErrors.isEmpty() ? "" : jobErrors.get(0))
        .isZero();
  }

  // ================================================================
  // Helpers
  // ================================================================

  /**
   * Hai lời gọi chạy song song qua cổng khóa: {@code lockSql} khóa row trước, đợi CẢ HAI đứng chờ
   * khóa đó, rồi mới nhả. Lúc nhả, cả hai đã qua hết các câu đọc và cùng đứng ở câu ghi đầu tiên
   * chạm row bị khóa.
   */
  private <T> List<Outcome<T>> raceThroughLock(
      Supplier<T> first, Supplier<T> second, String lockSql, Object... lockParams)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate = LockTestSupport.holdRowLock(dataSource, lockSql, lockParams)) {
      Future<Outcome<T>> a = pool.submit(() -> Outcome.of(first));
      Future<Outcome<T>> b = pool.submit(() -> Outcome.of(second));
      LockTestSupport.awaitLockWaiters(jdbc, 2);
      gate.commit();
      return List.of(
          a.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS),
          b.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  /** Đúng 1 lời gọi thành công; lời gọi còn lại thua bằng đúng {@code loserCode}. */
  private static void assertOneWinner(List<? extends Outcome<?>> outcomes, ErrorCode loserCode) {
    assertThat(outcomes).filteredOn(Outcome::succeeded).as("Số lời gọi thành công").hasSize(1);
    assertThat(outcomes)
        .filteredOn(outcome -> !outcome.succeeded())
        .singleElement()
        .satisfies(
            loser ->
                assertThat(loser.code())
                    .as("Bên thua: %s", chain(loser.error()))
                    .isEqualTo(loserCode));
  }

  /**
   * Mọi lời gọi hoặc thành công, hoặc thua đúng bằng {@code expected}.
   *
   * <p>Đếm gộp mọi exception thành "thua" là cái bẫy của test concurrency: một lỗi hạ tầng (hết
   * connection trong pool, timeout, deadlock) sẽ bị tính là "hết lượt" và con số vẫn có thể khớp.
   */
  private static void assertOnlyFailure(List<? extends Outcome<?>> outcomes, ErrorCode expected) {
    assertThat(outcomes)
        .as("Có lời gọi hỏng vì lý do khác %s: %s", expected, describe(outcomes))
        .allMatch(outcome -> outcome.succeeded() || outcome.code() == expected);
  }

  private static String describe(List<? extends Outcome<?>> outcomes) {
    return outcomes.stream()
        .filter(outcome -> !outcome.succeeded())
        .map(outcome -> chain(outcome.error()))
        .toList()
        .toString();
  }

  /** Chuỗi cause của một exception — 40P01 / 40001 nằm ở cause sâu nhất, không ở lớp ngoài. */
  private static String chain(Throwable error) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = error; t != null && sb.length() < 2000; t = t.getCause()) {
      sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  /**
   * Kết quả của một lời gọi service: giá trị trả về, hoặc exception nó ném.
   *
   * @param value {@code null} khi lời gọi ném
   * @param error {@code null} khi lời gọi thành công
   */
  private record Outcome<T>(T value, RuntimeException error) {

    static <T> Outcome<T> of(Supplier<T> call) {
      try {
        return new Outcome<>(call.get(), null);
      } catch (RuntimeException ex) {
        return new Outcome<>(null, ex);
      }
    }

    boolean succeeded() {
      return error == null;
    }

    /** Mã nghiệp vụ; {@code null} nếu thành công hoặc lỗi không phải {@link BusinessException}. */
    ErrorCode code() {
      return error instanceof BusinessException business ? business.getCode() : null;
    }
  }
}
