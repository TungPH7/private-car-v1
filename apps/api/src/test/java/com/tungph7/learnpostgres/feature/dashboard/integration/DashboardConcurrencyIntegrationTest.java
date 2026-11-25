package com.tungph7.learnpostgres.feature.dashboard.integration;

import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.item;
import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.vn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.Path;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.World;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.service.DashboardService;
import com.tungph7.learnpostgres.feature.dashboard.service.RefreshOutcome;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-16 — dashboard không chặn ghi, và việc làm mới MV không chặn dashboard.
 *
 * <ul>
 *   <li><b>Báo cáo đang chạy không chặn đặt đơn.</b> Transaction của một báo cáo giữ {@code
 *       AccessShareLock} trên mọi bảng nó đọc tới lúc kết thúc — mức khóa yếu nhất, chỉ xung đột
 *       với {@code ACCESS EXCLUSIVE}; đọc row không khóa row nào (MVCC). Đặt đơn (F05) ghi vào
 *       chính các bảng đó mà không phải chờ. READ ONLY không phải thứ bảo đảm điều này: nó chặn
 *       {@code FOR UPDATE} / {@code FOR SHARE} nhưng vẫn cho {@code LOCK TABLE} ở mọi mức.
 *   <li><b>Refresh đang chạy không chặn báo cáo.</b> {@code REFRESH ... CONCURRENTLY} giữ {@code
 *       ExclusiveLock} trên MV — chặn ghi vào MV và refresh khác, không chặn SELECT: báo cáo đọc
 *       bản trước refresh, ngay. Không {@code CONCURRENTLY} thì là {@code AccessExclusiveLock}:
 *       SELECT đứng chờ tới hết refresh.
 * </ul>
 *
 * <p>"Đang chạy" dựng bằng cách giữ transaction mở, không bằng starting gate: trên vài chục row,
 * báo cáo và refresh xong trong vài mili giây — muốn biết chúng có chặn ai không thì phải giữ chúng
 * ở đúng trạng thái đang chạy rồi mới gửi request kia. Báo cáo: gọi service trong một transaction
 * READ ONLY của test, chưa đóng. Refresh: cổng khóa ({@link LockTestSupport#holdTableLock}).
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi request là một transaction riêng, commit
 * thật. Dữ liệu tự dọn — {@link DashboardTestData}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class DashboardConcurrencyIntegrationTest extends AbstractContainerBaseTest {

  private static final Duration WAIT = Duration.ofSeconds(LockTestSupport.WAIT_SECONDS);

  /**
   * Cổng của test refresh. {@code REFRESH} lấy khóa trên MV trước, rồi mới đọc các bảng gốc — chặn
   * ở {@code orders} (MV nào của dashboard cũng phải đọc) thì refresh dừng đúng lúc đang chạy dở:
   * đã giữ khóa MV, đang chờ đọc.
   */
  private static final String ORDERS_TABLE_LOCK = "LOCK TABLE app.orders IN ACCESS EXCLUSIVE MODE";

  private static final TypeReference<List<PeriodRevenueResponse>> PERIODS =
      new TypeReference<>() {};

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired DashboardService dashboardService;
  @Autowired JdbcClient jdbcClient;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;

  private DashboardTestData data;
  private World world;

  @BeforeEach
  void setUp() {
    data = new DashboardTestData(jdbcClient, tx);
    cleanup();
    world = data.seedWorld();
    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);
  }

  /**
   * BẮT BUỘC dọn: dữ liệu đã commit, và MV dùng chung mọi lớp test. Chỉ {@code @AfterEach} — {@link
   * #setUp()} đã tự gọi ở dòng đầu (hai method {@code @BeforeEach} không có thứ tự đảm bảo).
   */
  @AfterEach
  void cleanup() {
    data.cleanup();
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }

  /** GET {@code /revenue/daily} của một ngày → 200, body đọc thành list. */
  private List<PeriodRevenueResponse> dailyRevenue(String day) throws Exception {
    String json =
        mockMvc
            .perform(get("/api/v1/dashboard/revenue/daily").param("from", day).param("to", day))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readValue(json, PERIODS);
  }

  /** POST {@code /api/v1/orders} — đặt 1 sản phẩm, đúng đường của F05 (giữ hàng, ghi lịch sử). */
  private MockHttpServletResponse placeOrder(UUID customerId, UUID addressId, UUID productId)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"customerId":"%s","shippingAddressId":"%s",
                     "items":[{"productId":"%s","quantity":1}]}
                    """
                        .formatted(customerId, addressId, productId)))
        .andReturn()
        .getResponse();
  }

  /** Các mức khóa mà CHÍNH transaction đang mở của caller giữ trên {@code table}. */
  private List<String> locksHeldOn(String table) {
    return jdbc.queryForList(
        "SELECT mode FROM pg_locks WHERE relation = ?::regclass AND pid = pg_backend_pid()"
            + " ORDER BY mode",
        String.class,
        table);
  }

  /** Số transaction KHÁC đang giữ (đã được cấp) một khóa trên MV. */
  private int materializedViewLockHolders() {
    Integer holders =
        jdbc.queryForObject(
            "SELECT count(DISTINCT pid) FROM pg_locks"
                + " WHERE relation = 'app.mv_daily_revenue'::regclass AND granted"
                + " AND pid <> pg_backend_pid()",
            Integer.class);
    return holders == null ? 0 : holders;
  }

  // ================================================================
  // Báo cáo không chặn ghi
  // ================================================================

  /**
   * Báo cáo top sản phẩm đọc {@code orders}, {@code order_items}, {@code products}; đặt đơn ghi vào
   * cả ba ({@code INSERT} đơn và dòng hàng, khóa {@code FOR KEY SHARE} sản phẩm khi kiểm khóa
   * ngoại) — trong lúc transaction của báo cáo vẫn mở. Không có gì phải chờ: {@code
   * AccessShareLock} không xung đột với {@code RowExclusiveLock} của {@code INSERT}.
   */
  @Test
  @DisplayName(
      "AC-16: báo cáo đang chạy (transaction READ ONLY chưa đóng) — POST /orders đặt đơn mới xong"
          + " ngay, không chờ báo cáo")
  void runningReportDoesNotBlockPlacingOrders() throws Exception {
    data.seedInventoryLog(world); // Phone X còn 65 cái — đặt đơn giữ được hàng
    UUID addressId = data.shippingAddress(world.c4());
    TransactionTemplate report = new TransactionTemplate(tx.getTransactionManager());
    report.setReadOnly(true);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      report.executeWithoutResult(
          status -> {
            assertThat(dashboardService.topNBestSellers(10, date("2025-01-01"), date("2025-12-31")))
                .hasSize(4);

            Future<MockHttpServletResponse> placing =
                pool.submit(() -> placeOrder(world.c4(), addressId, world.phoneX()));

            assertThat(placing)
                .succeedsWithin(WAIT)
                .satisfies(
                    response ->
                        assertThat(response.getStatus())
                            .as(response.getContentAsString())
                            .isEqualTo(201));
            // suốt lúc đó báo cáo vẫn mở: transaction READ ONLY, chỉ giữ AccessShareLock
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                .isEqualTo("on");
            assertThat(locksHeldOn("app.orders")).containsExactly("AccessShareLock");
          });
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            jdbc.queryForList(
                "SELECT status FROM app.orders WHERE customer_id = ?", String.class, world.c4()))
        .containsExactly("PENDING");
  }

  // ================================================================
  // Refresh không chặn báo cáo
  // ================================================================

  /**
   * Refresh đứng chờ ở cổng khóa sau khi đã giữ khóa MV; báo cáo gửi đúng lúc đó. Đơn mới của ngày
   * 20/05 đã commit nhưng refresh chưa xong — báo cáo trả bản trước refresh (0 đơn), không chờ.
   */
  @Test
  @DisplayName(
      "AC-16: REFRESH ... CONCURRENTLY đang chạy (đã giữ khóa MV) — /revenue/daily vẫn trả ngay,"
          + " số trước refresh; refresh xong mới thấy đơn mới")
  void runningRefreshDoesNotBlockReports() throws Exception {
    data.order(world.c4(), Path.DELIVERED, vn("2025-05-20T08:00"), "0", item(world.shirtZ(), 1));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection gate = LockTestSupport.holdTableLock(dataSource, ORDERS_TABLE_LOCK)) {
      Future<RefreshOutcome> refresh = pool.submit(() -> dashboardService.refreshDailyRevenue());
      LockTestSupport.awaitLockWaiters(jdbc, 1);
      assertThat(materializedViewLockHolders())
          .as("refresh đang chạy dở: đã giữ khóa trên MV, đứng chờ ở cổng")
          .isOne();

      Future<List<PeriodRevenueResponse>> reading = pool.submit(() -> dailyRevenue("2025-05-20"));

      assertThat(reading)
          .succeedsWithin(WAIT)
          .asInstanceOf(list(PeriodRevenueResponse.class))
          .extracting(PeriodRevenueResponse::orderCount)
          .containsExactly(0L);

      gate.rollback();
      assertThat(refresh).succeedsWithin(WAIT).isEqualTo(RefreshOutcome.REFRESHED);
    } finally {
      pool.shutdownNow();
    }

    assertThat(dailyRevenue("2025-05-20"))
        .extracting(PeriodRevenueResponse::orderCount, PeriodRevenueResponse::netRevenue)
        .containsExactly(tuple(1L, new BigDecimal("50000.00")));
  }
}
