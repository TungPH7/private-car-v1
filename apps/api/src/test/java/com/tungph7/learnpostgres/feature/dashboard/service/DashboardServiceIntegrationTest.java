package com.tungph7.learnpostgres.feature.dashboard.service;

import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.item;
import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.vn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.Path;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;

/**
 * {@link DashboardService} trên Postgres thật — những thứ unit test với mock không chứng minh được:
 *
 * <ul>
 *   <li>14 báo cáo chạy trong transaction {@code READ ONLY} của <b>Postgres</b> ({@code SHOW
 *       transaction_read_only} ngay trong lúc báo cáo chạy), refresh thì không.
 *   <li>Refresh gọi từ ngoài mọi transaction — như job — thật sự commit. Hikari {@code auto-commit:
 *       false}: thiếu transaction là refresh bị rollback lặng lẽ khi connection về pool.
 *   <li>Khóa advisory giữa hai transaction thật: instance khác đang giữ → {@code SKIPPED} ngay.
 *   <li>MV ở trạng thái {@code WITH NO DATA} thật → {@code POPULATED} thay vì lỗi {@code 0A000};
 *       đọc MV lúc đó → 503 (lỗi gốc {@code 55000}), connection không bị Hikari đóng.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: mỗi lời gọi service là một transaction riêng,
 * commit thật. Dữ liệu và MV tự dọn — {@link #cleanup()}.
 */
@SpringBootTest
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class DashboardServiceIntegrationTest extends AbstractContainerBaseTest {

  /** Ngày riêng của lớp test này — không bộ dữ liệu nào khác đặt đơn vào đó. */
  private static final LocalDate ORDER_DAY = LocalDate.of(2031, 1, 15);

  private static final String ADVISORY_LOCK =
      "SELECT pg_advisory_xact_lock('app.mv_daily_revenue'::regclass::oid::bigint)";

  @Autowired DashboardService dashboardService;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired DataSource dataSource;

  /** Repository đi qua bean này — spy để chen một câu {@code SHOW} vào trước mỗi câu SQL. */
  @MockitoSpyBean JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    cleanup();
  }

  /**
   * Xoá dữ liệu theo tiền tố (thứ tự FK) rồi refresh MV KHÔNG {@code CONCURRENTLY} — chạy được cả
   * khi test dừng giữa chừng lúc MV đang {@code WITH NO DATA}. MV dùng chung mọi lớp test trong
   * JVM: để lại ngày của lớp này là để lại rác cho lớp khác.
   */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              """
              DELETE FROM app.order_items oi USING app.orders o, app.customers c
              WHERE oi.order_id = o.id AND o.customer_id = c.id AND c.email LIKE 'dash-svc-%'
              """);
          jdbc.update(
              """
              DELETE FROM app.orders o USING app.customers c
              WHERE o.customer_id = c.id AND c.email LIKE 'dash-svc-%'
              """);
          jdbc.update(
              """
              DELETE FROM app.addresses a USING app.customers c
              WHERE a.customer_id = c.id AND c.email LIKE 'dash-svc-%'
              """);
          jdbc.update("DELETE FROM app.customers WHERE email LIKE 'dash-svc-%'");
          jdbc.update("DELETE FROM app.products WHERE sku LIKE 'DASH-SVC-%'");
          jdbc.execute("REFRESH MATERIALIZED VIEW app.mv_daily_revenue");
        });
  }

  /** Một đơn DELIVERED 100.000 lúc 10:00 ngày {@code day} giờ VN — commit. */
  private void seedDeliveredOrder(LocalDate day) {
    tx.executeWithoutResult(
        status -> {
          DashboardFixtures fixtures = new DashboardFixtures(jdbcClient);
          UUID product = fixtures.product("Dash Svc", "DASH-SVC-1", "100000", null);
          UUID customer =
              fixtures.customer(
                  "dash-svc-1@example.com", "Svc", "Test", vn(day.minusDays(30) + "T09:00"));
          fixtures.order(customer, Path.DELIVERED, vn(day + "T10:00"), "0", item(product, 1));
        });
  }

  /** Số đơn của {@code day} trong MV — đọc ở transaction riêng, chỉ thấy thứ đã commit. */
  private long mvOrderCount(LocalDate day) {
    Long count =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(order_count), 0) FROM app.mv_daily_revenue WHERE day = ?",
            Long.class,
            day);
    return count == null ? 0 : count;
  }

  private boolean isPopulated() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT relispopulated FROM pg_class WHERE oid = 'app.mv_daily_revenue'::regclass",
            Boolean.class));
  }

  /**
   * Trước mỗi câu SQL repository gửi qua {@link JdbcClient}, ghi lại {@code transaction_read_only}
   * của chính transaction đó — {@link JdbcTemplate} dùng chung connection đang gắn với transaction.
   */
  private List<String> recordTransactionReadOnly() {
    List<String> seen = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              seen.add(jdbc.queryForObject("SHOW transaction_read_only", String.class));
              return invocation.callRealMethod();
            })
        .when(jdbcClient)
        .sql(anyString());
    return seen;
  }

  // ================================================================
  // READ ONLY
  // ================================================================

  static Stream<Arguments> reports() {
    LocalDate from = ORDER_DAY.withDayOfMonth(1);
    LocalDate to = ORDER_DAY;
    return Stream.of(
        report("dailyRevenue", service -> service.dailyRevenue(from, to)),
        report("weeklyRevenue", service -> service.weeklyRevenue(from, to)),
        report("monthlyRevenue", service -> service.monthlyRevenue(2031)),
        report("monthlyRevenueByCategory", service -> service.monthlyRevenueByCategory(2031)),
        report("topNBestSellers", service -> service.topNBestSellers(10, from, to)),
        report(
            "topNCustomersBySpend",
            service -> service.topNCustomersBySpend(10, BigDecimal.ZERO, from, to)),
        report("orderFunnel", service -> service.orderFunnel(from, to)),
        report(
            "customerCohortRetention",
            service -> service.customerCohortRetention(YearMonth.of(2025, 1), 12)),
        report("inventoryTurnover", service -> service.inventoryTurnover(10, from, to)),
        report("avgOrderValueBySegment", service -> service.avgOrderValueBySegment(from, to)),
        report("monthOverMonthGrowth", service -> service.monthOverMonthGrowth(2031)),
        report("topCategoriesByRevenue", service -> service.topCategoriesByRevenue(10, from, to)),
        report("revenueGroupingSets", service -> service.revenueGroupingSets(from, to)),
        report(
            "customerLifetimeValue",
            // Khách không tồn tại: câu SQL vẫn chạy, rồi service ném 404.
            service ->
                assertThatThrownBy(() -> service.customerLifetimeValue(UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class)));
  }

  private static Arguments report(String name, Consumer<DashboardService> call) {
    return Arguments.of(name, call);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("reports")
  @DisplayName("mọi báo cáo chạy trong transaction READ ONLY của Postgres")
  void everyReportRunsInReadOnlyTransaction(String name, Consumer<DashboardService> call) {
    List<String> readOnly = recordTransactionReadOnly();

    call.accept(dashboardService);

    assertThat(readOnly).isNotEmpty().containsOnly("on");
  }

  @Test
  @DisplayName("refresh chạy trong transaction đọc-ghi")
  void refreshRunsInReadWriteTransaction() {
    List<String> readOnly = recordTransactionReadOnly();

    dashboardService.refreshDailyRevenue();

    assertThat(readOnly).isNotEmpty().containsOnly("off");
  }

  // ================================================================
  // Refresh
  // ================================================================

  @Test
  @DisplayName(
      "refresh gọi từ ngoài mọi transaction (như job) → commit: đơn mới hiện ở dailyRevenue")
  void refreshCommits() {
    seedDeliveredOrder(ORDER_DAY);
    assertThat(mvOrderCount(ORDER_DAY)).as("MV là ảnh chụp — chưa refresh thì chưa có").isZero();

    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);

    assertThat(mvOrderCount(ORDER_DAY)).isEqualTo(1);
    assertThat(dashboardService.dailyRevenue(ORDER_DAY, ORDER_DAY))
        .singleElement()
        .extracting(PeriodRevenueResponse::orderCount, PeriodRevenueResponse::netRevenue)
        .containsExactly(1L, new BigDecimal("100000.00"));
  }

  @Test
  @DisplayName("instance khác đang giữ khóa → SKIPPED ngay, MV không đổi; nhả khóa → REFRESHED")
  void skipsWhileAnotherInstanceHoldsTheLock() throws Exception {
    seedDeliveredOrder(ORDER_DAY);

    try (Connection otherInstance = LockTestSupport.holdRowLock(dataSource, ADVISORY_LOCK)) {
      RefreshOutcome outcome =
          assertTimeoutPreemptively(
              Duration.ofSeconds(LockTestSupport.WAIT_SECONDS),
              dashboardService::refreshDailyRevenue,
              "refresh phải bỏ qua ngay, không đứng chờ khóa của instance khác");

      assertThat(outcome).isEqualTo(RefreshOutcome.SKIPPED);
      assertThat(mvOrderCount(ORDER_DAY)).isZero();
      otherInstance.rollback();
    }

    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);
    assertThat(mvOrderCount(ORDER_DAY)).isEqualTo(1);
  }

  @Test
  @DisplayName("MV bị đưa về WITH NO DATA → POPULATED thay vì lỗi 0A000; lần sau REFRESHED")
  void populatesMaterializedViewLeftWithoutData() {
    seedDeliveredOrder(ORDER_DAY);
    tx.executeWithoutResult(
        status -> jdbc.execute("REFRESH MATERIALIZED VIEW app.mv_daily_revenue WITH NO DATA"));
    assertThat(isPopulated()).isFalse();

    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.POPULATED);

    assertThat(isPopulated()).isTrue();
    assertThat(mvOrderCount(ORDER_DAY)).isEqualTo(1);
    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);
  }

  // ================================================================
  // Đọc MV chưa có dữ liệu
  // ================================================================

  /**
   * Chuỗi lỗi thật khi đọc MV đang {@code WITH NO DATA}: Postgres {@code 55000} → service đổi thành
   * 503. Connection không bị HikariCP đóng ({@code 55000} không nằm trong các mã Hikari coi là
   * connection hỏng) — Hikari đóng thì log WARN "marked as broken".
   */
  @Test
  @DisplayName(
      "MV đang WITH NO DATA → 4 báo cáo đọc MV ném 503 DASH_REVENUE_NOT_READY (lỗi gốc 55000);"
          + " báo cáo khác vẫn chạy")
  void readingUnpopulatedViewIsServiceUnavailable(CapturedOutput output) {
    tx.executeWithoutResult(
        status -> jdbc.execute("REFRESH MATERIALIZED VIEW app.mv_daily_revenue WITH NO DATA"));
    LocalDate from = ORDER_DAY.withDayOfMonth(1);
    List<ThrowingCallable> materializedViewReports =
        List.of(
            () -> dashboardService.dailyRevenue(from, ORDER_DAY),
            () -> dashboardService.weeklyRevenue(from, ORDER_DAY),
            () -> dashboardService.monthlyRevenue(2031),
            () -> dashboardService.monthOverMonthGrowth(2031));

    for (ThrowingCallable report : materializedViewReports) {
      Throwable thrown = catchThrowable(report);

      assertThat(thrown).isInstanceOf(ServiceUnavailableException.class);
      assertThat(((BusinessException) thrown).getCode())
          .isEqualTo(ErrorCode.DASH_REVENUE_NOT_READY);
      assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
          .isInstanceOfSatisfying(
              SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo("55000"));
    }
    assertThat(dashboardService.orderFunnel(from, ORDER_DAY).placed()).isZero();
    assertThat(output).doesNotContain("marked as broken");
  }
}
