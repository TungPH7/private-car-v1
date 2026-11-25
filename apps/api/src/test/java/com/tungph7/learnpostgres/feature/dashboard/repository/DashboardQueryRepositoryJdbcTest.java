package com.tungph7.learnpostgres.feature.dashboard.repository;

import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.item;
import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.vn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.jdbc.RecordRowMapper;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.Path;
import com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.World;
import com.tungph7.learnpostgres.feature.dashboard.dto.CategoryRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.CohortRetentionResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.CustomerLifetimeValueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.InventoryTurnoverResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderFunnelResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderSegmentResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.RevenueGroupingSetResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.RevenueGrowthResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopCategoryResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopCustomerResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopProductResponse;

/**
 * Slice test cho {@link DashboardQueryRepository} trên Postgres thật — mỗi báo cáo so với đáp số
 * tính tay trên bộ dữ liệu {@link DashboardFixtures#seedWorld()}.
 *
 * <p>So sánh nguyên record: {@code BigDecimal.equals} so cả scale, nên test cũng giữ lời hứa "mọi
 * số tiền có 2 chữ số thập phân". Mọi thứ chạy trong MỘT transaction, rollback cuối test — kể cả
 * {@code REFRESH MATERIALIZED VIEW}, và {@code set_config('TimeZone', ..., true)} của test múi giờ.
 *
 * <p>JVM của máy dev ở Asia/Bangkok (+07, cùng giờ VN) nên session mặc định che mất lỗi cắt ngày
 * theo session. {@link #sameAnswersInAnySessionTimeZone} đặt hẳn {@code TimeZone} khác cho session.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DashboardQueryRepository.class)
class DashboardQueryRepositoryJdbcTest extends AbstractContainerBaseTest {

  private static final BigDecimal ZERO = money("0.00");

  @Autowired DashboardQueryRepository repository;
  @Autowired JdbcClient jdbc;

  private DashboardFixtures fixtures;
  private World world;

  @BeforeEach
  void seed() {
    fixtures = new DashboardFixtures(jdbc);
    world = fixtures.seedWorld();
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }

  private static PeriodRevenueResponse emptyPeriod(String start) {
    return new PeriodRevenueResponse(date(start), 0, 0, ZERO, ZERO, ZERO, ZERO, ZERO, null);
  }

  private static PeriodRevenueResponse period(
      String start,
      long orders,
      long completed,
      String gross,
      String discount,
      String net,
      String avg) {
    return new PeriodRevenueResponse(
        date(start),
        orders,
        completed,
        money(gross),
        money(discount),
        ZERO,
        money(net),
        ZERO,
        avg == null ? null : money(avg));
  }

  private void setSessionTimeZone(String zone) {
    jdbc.sql("SELECT set_config('TimeZone', :zone, true)")
        .param("zone", zone)
        .query(String.class)
        .single();
  }

  private BigDecimal netRevenueOf(List<PeriodRevenueResponse> rows) {
    return rows.stream()
        .map(PeriodRevenueResponse::netRevenue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  // -----------------------------------------------------------------
  // 1–3. Doanh thu theo ngày / tuần / tháng
  // -----------------------------------------------------------------

  @Test
  @DisplayName("dailyRevenue — mỗi ngày một row, ngày không có đơn = 0, AOV null")
  void dailyRevenueFillsEveryDay() {
    repository.refreshDailyRevenue();

    assertThat(repository.dailyRevenue(date("2025-03-30"), date("2025-04-02")))
        .containsExactly(
            emptyPeriod("2025-03-30"),
            emptyPeriod("2025-03-31"),
            period("2025-04-01", 1, 1, "6000000.00", "0.00", "6000000.00", "6000000.00"),
            emptyPeriod("2025-04-02"));
  }

  @Test
  @DisplayName("AC-01: dailyRevenue 30 ngày có ngày trống — đủ 30 row, đơn không được tính = 0")
  void dailyRevenueThirtyDaysWithGaps() {
    repository.refreshDailyRevenue();

    List<PeriodRevenueResponse> april =
        repository.dailyRevenue(date("2025-04-01"), date("2025-04-30"));

    assertThat(april).hasSize(30);
    assertThat(april)
        .filteredOn(r -> r.orderCount() > 0)
        .extracting(
            PeriodRevenueResponse::periodStart,
            PeriodRevenueResponse::orderCount,
            PeriodRevenueResponse::completedCount,
            PeriodRevenueResponse::netRevenue)
        .containsExactly(
            tuple(date("2025-04-01"), 1L, 1L, money("6000000.00")),
            tuple(date("2025-04-15"), 1L, 1L, money("200000.00")),
            tuple(date("2025-04-16"), 1L, 0L, ZERO), // EXPIRED
            tuple(date("2025-04-30"), 1L, 0L, ZERO)); // PENDING 23:59
    assertThat(april)
        .filteredOn(r -> r.orderCount() == 0)
        .hasSize(26)
        .allSatisfy(r -> assertThat(r).isEqualTo(emptyPeriod(r.periodStart().toString())));
  }

  @Test
  @DisplayName("dailyRevenue — from sau to: list rỗng, không phải một ngày")
  void dailyRevenueEmptyWhenFromAfterTo() {
    repository.refreshDailyRevenue();

    assertThat(repository.dailyRevenue(date("2025-04-02"), date("2025-03-30"))).isEmpty();
  }

  @Test
  @DisplayName(
      "dailyRevenue — session ở múi giờ đổi giờ lúc nửa đêm (Havana, 09/03/2025) vẫn đủ ngày")
  void dailyRevenueKeepsEveryDayAcrossMidnightDstChange() {
    setSessionTimeZone("America/Havana");
    repository.refreshDailyRevenue();

    // generate_series trên timestamptz: 00:00 ngày 09/03 không tồn tại ở Havana → chuỗi lệch sang
    // 01:00 và vượt mốc cuối (00:00 ngày 10/03) — mất ngày cuối
    assertThat(repository.dailyRevenue(date("2025-03-08"), date("2025-03-10")))
        .extracting(PeriodRevenueResponse::periodStart)
        .containsExactly(date("2025-03-08"), date("2025-03-09"), date("2025-03-10"));
  }

  @Test
  @DisplayName("weeklyRevenue — tuần bắt đầu thứ Hai; tuần ở hai đầu chỉ cộng ngày trong khoảng")
  void weeklyRevenueBucketsByIsoWeek() {
    repository.refreshDailyRevenue();

    assertThat(repository.weeklyRevenue(date("2025-03-30"), date("2025-04-16")))
        .containsExactly(
            emptyPeriod("2025-03-24"), // chỉ có Chủ nhật 30/03
            period("2025-03-31", 1, 1, "6000000.00", "0.00", "6000000.00", "6000000.00"),
            emptyPeriod("2025-04-07"),
            period("2025-04-14", 2, 1, "200000.00", "0.00", "200000.00", "200000.00"));
  }

  @Test
  @DisplayName("monthlyRevenue — 12 tháng; AC-10 giảm giá, AC-11 bỏ đơn hủy / hết hạn / chờ")
  void monthlyRevenueHasTwelveMonths() {
    repository.refreshDailyRevenue();

    assertThat(repository.monthlyRevenue(2025))
        .containsExactly(
            period("2025-01-01", 1, 1, "250000.00", "50000.00", "200000.00", "200000.00"),
            period("2025-02-01", 3, 2, "600000.00", "0.00", "600000.00", "300000.00"),
            period("2025-03-01", 1, 1, "190000.00", "0.00", "190000.00", "190000.00"),
            period("2025-04-01", 4, 2, "6200000.00", "0.00", "6200000.00", "3100000.00"),
            emptyPeriod("2025-05-01"),
            period("2025-06-01", 1, 1, "100000.00", "0.00", "100000.00", "100000.00"),
            emptyPeriod("2025-07-01"),
            emptyPeriod("2025-08-01"),
            emptyPeriod("2025-09-01"),
            emptyPeriod("2025-10-01"),
            emptyPeriod("2025-11-01"),
            emptyPeriod("2025-12-01"));
  }

  @Test
  @DisplayName("Ngày, tuần, tháng cộng ra cùng một tổng — bằng tổng total của v_completed_orders")
  void periodsAddUpToTheSameTotal() {
    repository.refreshDailyRevenue();
    BigDecimal viewTotal =
        jdbc.sql(
                """
                SELECT sum(total) FROM app.v_completed_orders
                WHERE created_at >= '2025-01-01 00:00+07' AND created_at < '2026-01-01 00:00+07'
                """)
            .query(BigDecimal.class)
            .single();

    BigDecimal daily =
        netRevenueOf(repository.dailyRevenue(date("2025-01-01"), date("2025-12-31")));
    BigDecimal weekly =
        netRevenueOf(repository.weeklyRevenue(date("2025-01-01"), date("2025-12-31")));
    BigDecimal monthly = netRevenueOf(repository.monthlyRevenue(2025));

    assertThat(viewTotal).isEqualByComparingTo("7290000");
    assertThat(List.of(daily, weekly, monthly)).allSatisfy(t -> assertThat(t).isEqualTo(viewTotal));
  }

  @Test
  @DisplayName(
      "AC-18: refund hiện ở ngày đặt đơn; 2 refund không nhân doanh thu; đơn hủy không bị trừ")
  void refundsShowOnOrderDay() {
    fixtures.completedRefund(world.order(1), "100000");
    fixtures.completedRefund(world.order(1), "50000");
    fixtures.completedRefund(world.order(3), "100000"); // PAID rồi hủy — không phải doanh thu
    repository.refreshDailyRevenue();

    List<PeriodRevenueResponse> months = repository.monthlyRevenue(2025);

    assertThat(months.get(0).netRevenue()).isEqualTo(money("200000.00"));
    assertThat(months.get(0).refundedAmount()).isEqualTo(money("150000.00"));
    assertThat(months.get(1).netRevenue()).isEqualTo(money("600000.00"));
    assertThat(months.get(1).refundedAmount()).isEqualTo(ZERO);
  }

  @Test
  @DisplayName("refreshDailyRevenue — MV là ảnh chụp: đơn mới chỉ hiện sau khi refresh")
  void materializedViewChangesOnlyOnRefresh() {
    repository.refreshDailyRevenue();
    fixtures.order(
        world.c4(), Path.DELIVERED, vn("2025-05-20T08:00"), "0", item(world.shirtZ(), 1));
    List<PeriodRevenueResponse> may20 = List.of(emptyPeriod("2025-05-20"));

    assertThat(repository.dailyRevenue(date("2025-05-20"), date("2025-05-20"))).isEqualTo(may20);

    repository.refreshDailyRevenue();

    assertThat(repository.dailyRevenue(date("2025-05-20"), date("2025-05-20")))
        .containsExactly(period("2025-05-20", 1, 1, "50000.00", "0.00", "50000.00", "50000.00"));
  }

  // -----------------------------------------------------------------
  // 4. Theo tháng × category
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "monthlyRevenueByCategory — category trực tiếp, sản phẩm chưa phân loại = category null")
  void monthlyRevenueByCategory() {
    assertThat(repository.monthlyRevenueByCategory(2025))
        .containsExactly(
            new CategoryRevenueResponse(
                date("2025-01-01"), world.phones(), "Dien thoai", 1, 2, money("200000.00")),
            new CategoryRevenueResponse(
                date("2025-01-01"), world.fashion(), "Thoi trang", 1, 1, money("50000.00")),
            new CategoryRevenueResponse(
                date("2025-02-01"), world.electronics(), "Dien tu", 2, 3, money("600000.00")),
            new CategoryRevenueResponse(
                date("2025-03-01"), world.phones(), "Dien thoai", 1, 1, money("100000.00")),
            new CategoryRevenueResponse(date("2025-03-01"), null, null, 1, 3, money("90000.00")),
            new CategoryRevenueResponse(
                date("2025-04-01"), world.electronics(), "Dien tu", 1, 30, money("6000000.00")),
            new CategoryRevenueResponse(
                date("2025-04-01"), world.fashion(), "Thoi trang", 1, 4, money("200000.00")),
            new CategoryRevenueResponse(
                date("2025-06-01"), world.fashion(), "Thoi trang", 1, 2, money("100000.00")));
  }

  @Test
  @DisplayName("AC-02: tổng theo category = SUM(line_total) của đơn được tính trong năm")
  void categoryTotalsMatchLineTotals() {
    BigDecimal lineTotals =
        jdbc.sql(
                """
SELECT sum(oi.line_total)
FROM app.order_items oi
JOIN app.orders o ON o.id = oi.order_id
WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
  AND o.created_at >= '2025-01-01 00:00+07' AND o.created_at < '2026-01-01 00:00+07'
""")
            .query(BigDecimal.class)
            .single();

    BigDecimal byCategory =
        repository.monthlyRevenueByCategory(2025).stream()
            .map(CategoryRevenueResponse::grossRevenue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(byCategory).isEqualTo(lineTotals).isEqualByComparingTo("7340000");
  }

  // -----------------------------------------------------------------
  // 5–6. Top N
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-03: topNBestSellers — theo số lượng, hòa thì theo doanh số")
  void topNBestSellers() {
    assertThat(repository.topNBestSellers(10, date("2025-01-01"), date("2025-12-31")))
        .containsExactly(
            new TopProductResponse(
                world.laptopY(), "Laptop Y", "DASH-P2", 33, money("6600000.00"), 3),
            new TopProductResponse(world.shirtZ(), "Ao Z", "DASH-P3", 7, money("350000.00"), 3),
            new TopProductResponse(world.phoneX(), "Phone X", "DASH-P1", 3, money("300000.00"), 2),
            new TopProductResponse(world.gift(), "Qua tang", "DASH-P4", 3, money("90000.00"), 1));
    assertThat(repository.topNBestSellers(2, date("2025-01-01"), date("2025-12-31")))
        .extracting(TopProductResponse::productId)
        .containsExactly(world.laptopY(), world.shirtZ());
  }

  @Test
  @DisplayName("AC-08: khoảng không có đơn — top N rỗng")
  void topNEmptyForPeriodWithoutOrders() {
    LocalDate from = date("2020-01-01");
    LocalDate to = date("2020-01-31");

    assertThat(repository.topNBestSellers(10, from, to)).isEmpty();
    assertThat(repository.topNCustomersBySpend(10, BigDecimal.ZERO, from, to)).isEmpty();
    assertThat(repository.topCategoriesByRevenue(10, from, to)).isEmpty();
  }

  @Test
  @DisplayName("topNCustomersBySpend — HAVING lọc theo tổng của khách, không theo từng đơn")
  void topNCustomersBySpend() {
    TopCustomerResponse binh =
        new TopCustomerResponse(
            world.c2(),
            "dash-c2@example.com",
            "Binh Tran",
            2,
            money("6190000.00"),
            money("3095000.00"),
            vn("2025-04-01T00:15").toInstant());
    TopCustomerResponse an =
        new TopCustomerResponse(
            world.c1(),
            "dash-c1@example.com",
            "An Nguyen",
            4,
            money("700000.00"),
            money("175000.00"),
            vn("2025-06-10T10:00").toInstant());
    LocalDate from = date("2025-01-01");
    LocalDate to = date("2025-12-31");

    // C3 tiêu 400.000 < ngưỡng; C1 không có đơn nào >= 500.000 nhưng tổng 700.000 — vẫn vào
    assertThat(repository.topNCustomersBySpend(10, money("500000"), from, to))
        .containsExactly(binh, an);
    assertThat(repository.topNCustomersBySpend(10, BigDecimal.ZERO, from, to))
        .extracting(TopCustomerResponse::customerId)
        .containsExactly(world.c2(), world.c1(), world.c3());
    assertThat(repository.topNCustomersBySpend(1, BigDecimal.ZERO, from, to)).containsExactly(binh);
  }

  /**
   * Vì sao service chặn {@code minTotalSpent} theo số chữ số (bài 04): pgjdbc gửi {@code
   * BigDecimal} dạng nhị phân, ghi {@code weight} — số nhóm 4 chữ số trước dấu phẩy — bằng 16 bit.
   * {@code 1E+131071} (weight 32767) tới Postgres nguyên vẹn; {@code 1E+131072} (weight 32768)
   * tràn, Postgres nhận một số cực nhỏ rồi cắt theo 0 chữ số lẻ — thành 0. Không lỗi nào báo.
   */
  @Test
  @DisplayName("ngưỡng từ 1E+131072 tới Postgres thành 0 — không ai đạt hóa ra mọi khách (pgjdbc)")
  void hugeThresholdReachesPostgresAsZero() {
    LocalDate from = date("2025-01-01");
    LocalDate to = date("2025-12-31");

    assertThat(repository.topNCustomersBySpend(10, new BigDecimal("1E+131071"), from, to))
        .isEmpty();
    assertThat(repository.topNCustomersBySpend(10, new BigDecimal("1E+131072"), from, to))
        .extracting(TopCustomerResponse::customerId)
        .containsExactly(world.c2(), world.c1(), world.c3());
  }

  // -----------------------------------------------------------------
  // 7. Phễu
  // -----------------------------------------------------------------

  @Test
  @DisplayName("orderFunnel — đơn PAID rồi hủy vẫn tính ở bước paid")
  void orderFunnelCountsStagesReached() {
    assertThat(repository.orderFunnel(date("2025-02-01"), date("2025-02-28")))
        .isEqualTo(
            new OrderFunnelResponse(
                3,
                3,
                1,
                1,
                0,
                1,
                1,
                0,
                money("100.00"),
                money("33.33"),
                money("100.00"),
                money("33.33"),
                ZERO));
  }

  @Test
  @DisplayName(
      "AC-04: orderFunnel cả năm — placed = COUNT(*) orders; paid − hủy-sau-paid = đơn được tính")
  void orderFunnelCrossChecks() {
    OrderFunnelResponse year = repository.orderFunnel(date("2025-01-01"), date("2025-12-31"));
    long orders =
        jdbc.sql(
                """
                SELECT count(*) FROM app.orders
                WHERE created_at >= '2025-01-01 00:00+07' AND created_at < '2026-01-01 00:00+07'
                """)
            .query(Long.class)
            .single();
    repository.refreshDailyRevenue();
    long completed =
        repository.monthlyRevenue(2025).stream()
            .mapToLong(PeriodRevenueResponse::completedCount)
            .sum();

    assertThat(year)
        .isEqualTo(
            new OrderFunnelResponse(
                10,
                8,
                6,
                5,
                1,
                1,
                1,
                1,
                money("80.00"),
                money("75.00"),
                money("83.33"),
                money("10.00"),
                money("10.00")));
    assertThat(year.placed()).isEqualTo(orders);
    assertThat(year.paid() - year.cancelledAfterPaid()).isEqualTo(completed);
  }

  @Test
  @DisplayName("AC-08: orderFunnel khoảng không có đơn — một row toàn 0, tỷ lệ null")
  void orderFunnelEmptyPeriod() {
    assertThat(repository.orderFunnel(date("2020-01-01"), date("2020-01-31")))
        .isEqualTo(new OrderFunnelResponse(0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null));
  }

  // -----------------------------------------------------------------
  // 8. Cohort
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-05: cohort 2025-01 — 3 khách (C3 đăng ký 00:30 ngày 01/02 không thuộc), tháng 0 khách vẫn"
          + " có row")
  void cohortRetention() {
    long registeredInJanuary =
        jdbc.sql(
                """
                SELECT count(*) FROM app.customers
                WHERE created_at >= '2025-01-01 00:00+07' AND created_at < '2025-02-01 00:00+07'
                """)
            .query(Long.class)
            .single();

    assertThat(repository.customerCohortRetention(YearMonth.of(2025, 1), 6))
        .containsExactly(
            new CohortRetentionResponse(0, date("2025-01-01"), 3, 1, money("33.33")),
            new CohortRetentionResponse(1, date("2025-02-01"), 3, 1, money("33.33")),
            new CohortRetentionResponse(2, date("2025-03-01"), 3, 1, money("33.33")),
            new CohortRetentionResponse(3, date("2025-04-01"), 3, 2, money("66.67")),
            new CohortRetentionResponse(4, date("2025-05-01"), 3, 0, ZERO),
            new CohortRetentionResponse(5, date("2025-06-01"), 3, 1, money("33.33")));
    assertThat(registeredInJanuary).isEqualTo(3);
  }

  @Test
  @DisplayName("cohort 2025-02 chỉ có C3; cohort rỗng — retention null")
  void cohortOfOtherMonths() {
    assertThat(repository.customerCohortRetention(YearMonth.of(2025, 2), 3))
        .containsExactly(
            new CohortRetentionResponse(0, date("2025-02-01"), 1, 1, money("100.00")),
            new CohortRetentionResponse(1, date("2025-03-01"), 1, 0, ZERO),
            new CohortRetentionResponse(2, date("2025-04-01"), 1, 0, ZERO));
    assertThat(repository.customerCohortRetention(YearMonth.of(2019, 1), 2))
        .containsExactly(
            new CohortRetentionResponse(0, date("2019-01-01"), 0, 0, null),
            new CohortRetentionResponse(1, date("2019-02-01"), 0, 0, null));
  }

  // -----------------------------------------------------------------
  // 9. Tồn kho
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "inventoryTurnover — tồn kho đầu / cuối kỳ dựng lại từ sổ, commit 03:00 ngày 01/05 thuộc"
          + " tháng 5")
  void inventoryTurnoverFromMovementLog() {
    fixtures.seedInventoryLog(world);
    Set<UUID> fixtureProducts =
        Set.of(world.phoneX(), world.laptopY(), world.shirtZ(), world.gift());

    assertThat(repository.inventoryTurnover(1000, date("2025-04-01"), date("2025-04-30")))
        .filteredOn(r -> fixtureProducts.contains(r.productId()))
        .containsExactly(
            new InventoryTurnoverResponse(
                world.phoneX(), "Phone X", "DASH-P1", 30, 100, 70, money("0.35")),
            new InventoryTurnoverResponse(world.shirtZ(), "Ao Z", "DASH-P3", 0, 50, 50, ZERO),
            // nhập 10 rồi bán hết trong kỳ: hai đầu kỳ đều 0 — tồn bình quân 0, tỷ lệ không xác
            // định
            new InventoryTurnoverResponse(world.laptopY(), "Laptop Y", "DASH-P2", 10, 0, 0, null),
            new InventoryTurnoverResponse(world.gift(), "Qua tang", "DASH-P4", 0, 0, 0, null));
  }

  // -----------------------------------------------------------------
  // 10. Phân khúc
  // -----------------------------------------------------------------

  @Test
  @DisplayName("avgOrderValueBySegment — chỉ phân khúc có đơn, theo thứ tự giá trị")
  void avgOrderValueBySegment() {
    assertThat(repository.avgOrderValueBySegment(date("2025-01-01"), date("2025-12-31")))
        .containsExactly(
            new OrderSegmentResponse(
                "SMALL",
                6,
                money("215000.00"),
                money("100000.00"),
                money("400000.00"),
                money("1290000.00"),
                money("200000.00"),
                money("350000.00")),
            new OrderSegmentResponse(
                "VIP",
                1,
                money("6000000.00"),
                money("6000000.00"),
                money("6000000.00"),
                money("6000000.00"),
                money("6000000.00"),
                money("6000000.00")));
  }

  @Test
  @DisplayName("percentile_cont nội suy bằng double — kết quả vẫn là numeric 2 chữ số")
  void percentilesAreRoundedMoney() {
    UUID cent = fixtures.product("Cent", "DASH-CENT", "100000.01", null);
    fixtures.order(
        world.c4(), Path.DELIVERED, vn("2025-08-05T10:00"), "0", item(world.phoneX(), 1));
    fixtures.order(world.c4(), Path.DELIVERED, vn("2025-08-06T10:00"), "0", item(cent, 1));

    OrderSegmentResponse small =
        repository.avgOrderValueBySegment(date("2025-08-01"), date("2025-08-31")).getFirst();

    // trung vị của 100.000,00 và 100.000,01 là 100.000,005 — double trả nguyên 3 chữ số lẻ
    assertThat(small.median()).isEqualTo(money("100000.01"));
    assertThat(small.p95()).isEqualTo(money("100000.01"));
    assertThat(small.avgValue()).isEqualTo(money("100000.01"));
  }

  // -----------------------------------------------------------------
  // 11. Tăng trưởng
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-06: monthOverMonthGrowth — tháng 1 so với 12/2024, YoY thấy năm trước, LAG không nhảy qua"
          + " tháng trống")
  void monthOverMonthGrowth() {
    repository.refreshDailyRevenue();

    List<RevenueGrowthResponse> rows = repository.monthOverMonthGrowth(2025);

    assertThat(rows).hasSize(12);
    assertThat(rows.subList(0, 7))
        .containsExactly(
            growth("2025-01-01", "200000.00", "0.00", "200000.00", null, "0.00", null),
            growth("2025-02-01", "600000.00", "200000.00", "400000.00", "200.00", "0.00", null),
            growth("2025-03-01", "190000.00", "600000.00", "-410000.00", "-68.33", "0.00", null),
            growth(
                "2025-04-01",
                "6200000.00",
                "190000.00",
                "6010000.00",
                "3163.16",
                "100000.00",
                "6100.00"),
            growth("2025-05-01", "0.00", "6200000.00", "-6200000.00", "-100.00", "0.00", null),
            growth("2025-06-01", "100000.00", "0.00", "100000.00", null, "0.00", null),
            growth("2025-07-01", "0.00", "100000.00", "-100000.00", "-100.00", "0.00", null));
    assertThat(rows.subList(7, 12))
        .extracting(
            RevenueGrowthResponse::revenue,
            RevenueGrowthResponse::prevMonthRevenue,
            RevenueGrowthResponse::pctChange)
        .containsOnly(tuple(ZERO, ZERO, null));
  }

  private static RevenueGrowthResponse growth(
      String month,
      String revenue,
      String prev,
      String abs,
      String pct,
      String lastYear,
      String yoy) {
    return new RevenueGrowthResponse(
        date(month),
        money(revenue),
        money(prev),
        money(abs),
        pct == null ? null : money(pct),
        money(lastYear),
        yoy == null ? null : money(yoy));
  }

  // -----------------------------------------------------------------
  // 12. Top category
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "topCategoriesByRevenue — cộng Dien thoai vào Dien tu; sản phẩm chưa phân loại không có")
  void topCategoriesRollUpToRoots() {
    TopCategoryResponse electronics =
        new TopCategoryResponse(1, world.electronics(), "Dien tu", 5, 36, money("6900000.00"));

    assertThat(repository.topCategoriesByRevenue(10, date("2025-01-01"), date("2025-12-31")))
        .containsExactly(
            electronics,
            new TopCategoryResponse(2, world.fashion(), "Thoi trang", 3, 7, money("350000.00")));
    assertThat(repository.topCategoriesByRevenue(1, date("2025-01-01"), date("2025-12-31")))
        .containsExactly(electronics);
  }

  @Test
  @DisplayName("topCategoriesByRevenue — RANK: bằng doanh số thì cùng hạng, hạng sau nhảy cóc")
  void topCategoriesRankTies() {
    UUID books = fixtures.category("Sach", null);
    UUID toys = fixtures.category("Do choi", null);
    UUID novel = fixtures.product("Tieu thuyet", "DASH-BOOK", "100000", books);
    UUID robot = fixtures.product("Robot", "DASH-TOY", "100000", toys);
    fixtures.order(world.c4(), Path.DELIVERED, vn("2025-09-01T10:00"), "0", item(novel, 1));
    fixtures.order(world.c4(), Path.DELIVERED, vn("2025-09-02T10:00"), "0", item(robot, 1));
    fixtures.order(
        world.c4(), Path.DELIVERED, vn("2025-09-03T10:00"), "0", item(world.shirtZ(), 1));

    List<TopCategoryResponse> september =
        repository.topCategoriesByRevenue(10, date("2025-09-01"), date("2025-09-30"));

    assertThat(september.subList(0, 2))
        .extracting(TopCategoryResponse::revenueRank, TopCategoryResponse::categoryName)
        .containsExactlyInAnyOrder(tuple(1L, "Sach"), tuple(1L, "Do choi"));
    assertThat(september.get(2))
        .isEqualTo(
            new TopCategoryResponse(3, world.fashion(), "Thoi trang", 1, 1, money("50000.00")));
  }

  // -----------------------------------------------------------------
  // 13. GROUPING SETS
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-07: revenueGroupingSets — tổng cộng = tổng các row chi tiết; null category ≠ row tổng")
  void revenueGroupingSets() {
    List<RevenueGroupingSetResponse> rows =
        repository.revenueGroupingSets(date("2025-01-01"), date("2025-12-31"));

    assertThat(rows)
        .extracting(RevenueGroupingSetResponse::groupingLevel)
        .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3);
    BigDecimal total = rows.getLast().revenue();
    for (int level = 0; level <= 2; level++) {
      int lvl = level;
      assertThat(
              rows.stream()
                  .filter(r -> r.groupingLevel() == lvl)
                  .map(RevenueGroupingSetResponse::revenue)
                  .reduce(BigDecimal.ZERO, BigDecimal::add))
          .as("tổng các row cấp %d", lvl)
          .isEqualTo(total);
    }
    assertThat(rows)
        .contains(
            // sản phẩm chưa phân loại: row CHI TIẾT (cấp 0) và row tổng của "category null" (cấp 2)
            new RevenueGroupingSetResponse(date("2025-03-01"), null, null, money("90000.00"), 1, 0),
            new RevenueGroupingSetResponse(null, null, null, money("90000.00"), 1, 2),
            // đơn 1 có hàng ở 2 category: 2 row chi tiết, nhưng 1 đơn trong tổng tháng 1
            new RevenueGroupingSetResponse(
                date("2025-01-01"), null, null, money("250000.00"), 1, 1),
            new RevenueGroupingSetResponse(null, null, null, money("7340000.00"), 7, 3));
    assertThat(rows)
        .filteredOn(r -> r.groupingLevel() == 0 && date("2025-01-01").equals(r.month()))
        .extracting(RevenueGroupingSetResponse::orderCount)
        .containsExactly(1L, 1L);
  }

  /**
   * Tập gộp rỗng {@code ()} sinh row tổng cộng cả khi không có dòng hàng nào — {@code SUM} của tập
   * rỗng là {@code NULL}. Doanh số phải là {@code 0.00} như mọi tổng tiền khác (AC-08: "0 (doanh
   * thu)"), không phải {@code null} ("không xác định").
   */
  @Test
  @DisplayName(
      "AC-08: GROUPING SETS khoảng không có đơn — chỉ row tổng cộng, doanh số 0.00 (không null)")
  void revenueGroupingSetsOfEmptyPeriod() {
    assertThat(repository.revenueGroupingSets(date("2020-01-01"), date("2020-01-31")))
        .containsExactly(new RevenueGroupingSetResponse(null, null, null, ZERO, 0, 3));
  }

  // -----------------------------------------------------------------
  // 14. LTV
  // -----------------------------------------------------------------

  @Test
  @DisplayName("customerLifetimeValue — mọi đơn được tính của khách, từ trước tới nay")
  void customerLifetimeValue() {
    assertThat(repository.customerLifetimeValue(world.c1()))
        .contains(
            new CustomerLifetimeValueResponse(
                world.c1(),
                "dash-c1@example.com",
                4,
                money("700000.00"),
                money("175000.00"),
                vn("2025-01-10T09:00").toInstant(),
                vn("2025-06-10T10:00").toInstant()));
  }

  @Test
  @DisplayName("AC-09: khách chưa có đơn — LTV 0, vẫn có row; khách không tồn tại — rỗng")
  void customerLifetimeValueWithoutOrders() {
    assertThat(repository.customerLifetimeValue(world.c4()))
        .contains(
            new CustomerLifetimeValueResponse(
                world.c4(), "dash-c4@example.com", 0, ZERO, null, null, null));
    assertThat(repository.customerLifetimeValue(UUID.randomUUID())).isEmpty();
  }

  // -----------------------------------------------------------------
  // Hạ tầng
  // -----------------------------------------------------------------

  @Test
  @DisplayName("businessTimezone — hàm của V16")
  void businessTimezone() {
    assertThat(repository.businessTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
  }

  record MappingProbe(
      long orderCount, Long maybeCount, BigDecimal netRevenue, Instant lastOrderAt) {}

  /**
   * {@link RecordRowMapper} (bài 06) thay {@code query(Class)} mà giữ năm luật bài 02 §2 đã đo với
   * mapper mặc định — trên pgjdbc thật, qua bước dịch lỗi của Spring.
   */
  @Test
  @DisplayName(
      "RecordRowMapper giữ luật của query(Class): NULL vào long lỗi, NULL vào Long / BigDecimal là"
          + " null, alias gõ sai lỗi ngay, cột thừa bỏ qua, timestamptz → Instant")
  void recordRowMapperKeepsTheMappingRules() {
    assertThat(
            jdbc.sql(
                    """
                    SELECT 3::bigint AS order_count, NULL::bigint AS maybe_count,
                           NULL::numeric AS net_revenue, 'x' AS extra,
                           timestamptz '2025-02-01 03:30+07' AS last_order_at
                    """)
                .query(RecordRowMapper.of(MappingProbe.class))
                .single())
        .isEqualTo(new MappingProbe(3, null, null, Instant.parse("2025-01-31T20:30:00Z")));
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        SELECT NULL::bigint AS order_count, 1::bigint AS maybe_count,
                               0.00 AS net_revenue, now() AS last_order_at
                        """)
                    .query(RecordRowMapper.of(MappingProbe.class))
                    .single())
        .isInstanceOf(ConversionFailedException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        SELECT 3::bigint AS order_count, 1::bigint AS maybe_count,
                               0.00 AS net_revenu, now() AS last_order_at
                        """)
                    .query(RecordRowMapper.of(MappingProbe.class))
                    .single())
        .isInstanceOf(BadSqlGrammarException.class)
        .cause()
        .hasMessageContaining("net_revenue");
  }

  /**
   * AC-17: mọi câu cắt thời gian cho cùng đáp số dù session ở múi giờ nào. Mỗi assertion chạm đúng
   * một điểm biên của fixture — đơn 2 (03:30 ngày 01/02), đơn 10 (00:15 ngày 01/04), khách C3
   * (00:30 ngày 01/02), commit 03:00 ngày 01/05 — chỗ cắt theo UTC (lệch về trước) hoặc theo
   * Kiritimati +14 (lệch về sau) sẽ ra số khác. MV cũng refresh trong chính session đó.
   */
  @ParameterizedTest(name = "TimeZone = {0}")
  @ValueSource(strings = {"UTC", "America/New_York", "Pacific/Kiritimati", "Asia/Ho_Chi_Minh"})
  @DisplayName("AC-17: cùng đáp số ở mọi TimeZone của session")
  void sameAnswersInAnySessionTimeZone(String zone) {
    fixtures.seedInventoryLog(world);
    setSessionTimeZone(zone);
    repository.refreshDailyRevenue();
    LocalDate febFrom = date("2025-02-01");
    LocalDate febTo = date("2025-02-28");

    List<PeriodRevenueResponse> months = repository.monthlyRevenue(2025);
    assertThat(months.get(1).netRevenue()).isEqualTo(money("600000.00"));
    assertThat(months.get(3).netRevenue()).isEqualTo(money("6200000.00"));
    assertThat(months.get(3).orderCount()).isEqualTo(4); // đơn 9 lúc 23:59 ngày 30/04 vẫn tháng 4
    assertThat(repository.dailyRevenue(date("2025-03-31"), date("2025-04-01")))
        .extracting(PeriodRevenueResponse::netRevenue)
        .containsExactly(ZERO, money("6000000.00"));
    assertThat(repository.weeklyRevenue(date("2025-04-01"), date("2025-04-06")))
        .extracting(PeriodRevenueResponse::periodStart, PeriodRevenueResponse::netRevenue)
        .containsExactly(tuple(date("2025-03-31"), money("6000000.00")));
    assertThat(repository.monthlyRevenueByCategory(2025))
        .filteredOn(r -> date("2025-02-01").equals(r.month()))
        .extracting(CategoryRevenueResponse::grossRevenue)
        .containsExactly(money("600000.00"));
    assertThat(repository.topNBestSellers(10, febFrom, febTo))
        .extracting(TopProductResponse::unitsSold)
        .containsExactly(3L);
    assertThat(repository.topNCustomersBySpend(10, BigDecimal.ZERO, febFrom, febTo))
        .extracting(TopCustomerResponse::customerId)
        .containsExactly(world.c3(), world.c1());
    assertThat(repository.orderFunnel(febFrom, febTo).placed()).isEqualTo(3);
    assertThat(repository.customerCohortRetention(YearMonth.of(2025, 1), 4))
        .extracting(CohortRetentionResponse::cohortSize, CohortRetentionResponse::activeCustomers)
        .containsExactly(tuple(3L, 1L), tuple(3L, 1L), tuple(3L, 1L), tuple(3L, 2L));
    assertThat(repository.inventoryTurnover(1000, date("2025-04-01"), date("2025-04-30")))
        .filteredOn(r -> r.productId().equals(world.phoneX()))
        .extracting(InventoryTurnoverResponse::unitsSold, InventoryTurnoverResponse::onHandEnd)
        .containsExactly(tuple(30L, 70L));
    assertThat(repository.avgOrderValueBySegment(date("2025-04-01"), date("2025-04-30")))
        .extracting(OrderSegmentResponse::segment)
        .containsExactly("SMALL", "VIP");
    assertThat(repository.monthOverMonthGrowth(2025).get(3))
        .extracting(RevenueGrowthResponse::revenue, RevenueGrowthResponse::yoyPct)
        .containsExactly(money("6200000.00"), money("6100.00"));
    assertThat(repository.topCategoriesByRevenue(10, febFrom, febTo))
        .extracting(TopCategoryResponse::revenue)
        .containsExactly(money("600000.00"));
    assertThat(repository.revenueGroupingSets(febFrom, febTo).getLast().revenue())
        .isEqualTo(money("600000.00"));
  }
}
