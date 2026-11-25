package com.tungph7.learnpostgres.feature.dashboard.integration;

import static com.tungph7.learnpostgres.feature.dashboard.DashboardFixtures.vn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.assertj.core.groups.Tuple;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerProfileResponse;
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
import com.tungph7.learnpostgres.feature.dashboard.service.DashboardService;
import com.tungph7.learnpostgres.feature.dashboard.service.RefreshOutcome;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dashboard đi hết HTTP → controller → service → Postgres, không mock gì — mỗi AC qua đúng endpoint
 * của nó, so với đáp số tính tay của bộ dữ liệu 2025 ({@link DashboardTestData}).
 *
 * <p>Các lớp test đã có mỗi lớp thiếu một nửa: {@code DashboardControllerWebMvcTest} có HTTP nhưng
 * service là mock; {@code DashboardQueryRepositoryJdbcTest} và {@code
 * DashboardServiceIntegrationTest} có Postgres nhưng không qua HTTP. Ở đây kiểm những gì chỉ đúng
 * khi ghép cả hai:
 *
 * <ul>
 *   <li>Số liệu thật ra JSON: tiền giữ 2 chữ số lẻ, field {@code null} có mặt, thời điểm ISO-8601
 *       UTC; giá trị mặc định của tham số ({@code n}, {@code minTotalSpent}, {@code months}) tới
 *       tận câu SQL.
 *   <li>Cross-check của AC: con số của báo cáo = câu SQL viết tay trên chính các bảng.
 *   <li>Lỗi sinh ra từ thành phần thật, qua handler thật: service chặn đầu vào (400), khách không
 *       tồn tại (404), MV chưa có dữ liệu — lỗi gốc {@code 55000} của Postgres (503).
 *   <li>Chéo feature: LTV (F07) và profile của khách (F02) ra cùng số.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional} trên class: request HTTP chỉ thấy thứ đã commit. Trước mỗi
 * test: dọn, seed (commit), rồi refresh MV qua service — job tắt trong profile test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class DashboardControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String DASHBOARD = "/api/v1/dashboard";
  private static final String LTV = "/api/v1/customers/{customerId}/ltv";
  private static final String PROFILE = "/api/v1/customers/{id}/profile";
  private static final String YEAR_FROM = "2025-01-01";
  private static final String YEAR_TO = "2025-12-31";
  private static final BigDecimal ZERO = money("0.00");

  private static final TypeReference<List<PeriodRevenueResponse>> PERIODS =
      new TypeReference<>() {};
  private static final TypeReference<List<RevenueGrowthResponse>> GROWTH = new TypeReference<>() {};
  private static final TypeReference<List<CategoryRevenueResponse>> CATEGORY_CELLS =
      new TypeReference<>() {};
  private static final TypeReference<List<TopCategoryResponse>> TOP_CATEGORIES =
      new TypeReference<>() {};
  private static final TypeReference<List<TopProductResponse>> TOP_PRODUCTS =
      new TypeReference<>() {};
  private static final TypeReference<List<TopCustomerResponse>> TOP_CUSTOMERS =
      new TypeReference<>() {};
  private static final TypeReference<List<CohortRetentionResponse>> COHORT =
      new TypeReference<>() {};
  private static final TypeReference<List<RevenueGroupingSetResponse>> GROUPING_SETS =
      new TypeReference<>() {};
  private static final TypeReference<List<OrderSegmentResponse>> SEGMENTS =
      new TypeReference<>() {};
  private static final TypeReference<List<InventoryTurnoverResponse>> TURNOVER =
      new TypeReference<>() {};

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired DashboardService dashboardService;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private DashboardTestData data;
  private World world;

  @BeforeEach
  void setUp() {
    data = new DashboardTestData(jdbc, tx);
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

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static LocalDate date(String value) {
    return LocalDate.parse(value);
  }

  private static PeriodRevenueResponse emptyPeriod(LocalDate start) {
    return new PeriodRevenueResponse(start, 0, 0, ZERO, ZERO, ZERO, ZERO, ZERO, null);
  }

  /** GET {@code /api/v1/dashboard + path} với khoảng {@code [from, to]}. */
  private static MockHttpServletRequestBuilder range(String path, String from, String to) {
    return get(DASHBOARD + path).param("from", from).param("to", to);
  }

  private static MockHttpServletRequestBuilder year(String path, int year) {
    return get(DASHBOARD + path).param("year", String.valueOf(year));
  }

  /** Request → 200, body nguyên văn. */
  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private <T> T read(MockHttpServletRequestBuilder request, TypeReference<T> type)
      throws Exception {
    return objectMapper.readValue(body(request), type);
  }

  private <T> T read(MockHttpServletRequestBuilder request, Class<T> type) throws Exception {
    return objectMapper.readValue(body(request), type);
  }

  /** {@code SUM(line_total)} của mọi đơn được tính đặt trong năm 2025 giờ VN — viết tay. */
  private BigDecimal lineTotalsOf2025() {
    return jdbc.sql(
            """
            SELECT sum(oi.line_total)
            FROM app.order_items oi
            JOIN app.orders o ON o.id = oi.order_id
            WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
              AND o.created_at >= '2025-01-01 00:00+07' AND o.created_at < '2026-01-01 00:00+07'
            """)
        .query(BigDecimal.class)
        .single();
  }

  // ================================================================
  // Doanh thu theo kỳ — đọc MV
  // ================================================================

  @Test
  @DisplayName(
      "AC-01: /revenue/daily tháng 4 → 30 row, 26 ngày trống = 0; AC-17: đơn 00:15 và 03:30 giờ VN"
          + " nằm ở ngày VN")
  void dailyRevenueFillsGapsOnBusinessDays() throws Exception {
    List<PeriodRevenueResponse> april =
        read(range("/revenue/daily", "2025-04-01", "2025-04-30"), PERIODS);

    assertThat(april).hasSize(30);
    assertThat(april)
        .filteredOn(r -> r.orderCount() > 0)
        .extracting(
            PeriodRevenueResponse::periodStart,
            PeriodRevenueResponse::orderCount,
            PeriodRevenueResponse::completedCount,
            PeriodRevenueResponse::netRevenue,
            PeriodRevenueResponse::avgOrderValue)
        .containsExactly(
            tuple(date("2025-04-01"), 1L, 1L, money("6000000.00"), money("6000000.00")),
            tuple(date("2025-04-15"), 1L, 1L, money("200000.00"), money("200000.00")),
            tuple(date("2025-04-16"), 1L, 0L, ZERO, null), // EXPIRED
            tuple(date("2025-04-30"), 1L, 0L, ZERO, null)); // PENDING 23:59
    assertThat(april)
        .filteredOn(r -> r.orderCount() == 0)
        .hasSize(26)
        .allSatisfy(r -> assertThat(r).isEqualTo(emptyPeriod(r.periodStart())));

    // đơn 2 đặt 03:30 ngày 01/02 giờ VN — theo UTC là 20:30 ngày 31/01
    assertThat(read(range("/revenue/daily", "2025-01-31", "2025-02-01"), PERIODS))
        .extracting(
            PeriodRevenueResponse::periodStart,
            PeriodRevenueResponse::orderCount,
            PeriodRevenueResponse::netRevenue)
        .containsExactly(
            tuple(date("2025-01-31"), 0L, ZERO), tuple(date("2025-02-01"), 1L, money("200000.00")));
  }

  @Test
  @DisplayName(
      "AC-10, AC-11: /revenue/monthly — giảm giá trừ đúng, đơn hủy (kể cả sau khi PAID) / hết hạn /"
          + " chờ không có trong doanh thu; /revenue/weekly chỉ cộng ngày trong khoảng")
  void monthlyAndWeeklyRevenueCountOnlyCompletedOrders() throws Exception {
    List<PeriodRevenueResponse> months = read(year("/revenue/monthly", 2025), PERIODS);

    assertThat(months).hasSize(12);
    assertThat(months)
        .filteredOn(r -> r.orderCount() > 0)
        .extracting(
            PeriodRevenueResponse::periodStart,
            PeriodRevenueResponse::orderCount,
            PeriodRevenueResponse::completedCount,
            PeriodRevenueResponse::grossSubtotal,
            PeriodRevenueResponse::totalDiscount,
            PeriodRevenueResponse::netRevenue)
        .containsExactly(
            tuple(
                date("2025-01-01"),
                1L,
                1L,
                money("250000.00"),
                money("50000.00"),
                money("200000.00")),
            // đặt 3 đơn: PAID, DELIVERED và một đơn PAID rồi hủy — chỉ 2 đơn đầu là doanh thu
            tuple(date("2025-02-01"), 3L, 2L, money("600000.00"), ZERO, money("600000.00")),
            tuple(date("2025-03-01"), 1L, 1L, money("190000.00"), ZERO, money("190000.00")),
            // đặt 4 đơn: DELIVERED, SHIPPED, EXPIRED, PENDING
            tuple(date("2025-04-01"), 4L, 2L, money("6200000.00"), ZERO, money("6200000.00")),
            tuple(date("2025-06-01"), 1L, 1L, money("100000.00"), ZERO, money("100000.00")));
    assertThat(months)
        .filteredOn(r -> r.orderCount() == 0)
        .hasSize(7)
        .allSatisfy(r -> assertThat(r).isEqualTo(emptyPeriod(r.periodStart())));

    // tuần thứ Hai – Chủ nhật; tuần đầu chỉ có Chủ nhật 30/03 nằm trong khoảng
    assertThat(read(range("/revenue/weekly", "2025-03-30", "2025-04-16"), PERIODS))
        .extracting(
            PeriodRevenueResponse::periodStart,
            PeriodRevenueResponse::orderCount,
            PeriodRevenueResponse::netRevenue)
        .containsExactly(
            tuple(date("2025-03-24"), 0L, ZERO),
            tuple(date("2025-03-31"), 1L, money("6000000.00")),
            tuple(date("2025-04-07"), 0L, ZERO),
            tuple(date("2025-04-14"), 2L, money("200000.00")));
  }

  @Test
  @DisplayName(
      "AC-06: /growth — 12 tháng; tháng 1 so với 12/2024, YoY so với cùng tháng 2024; % null khi"
          + " tháng đem ra so không có doanh thu")
  void growthComparesWithPreviousMonthAndYear() throws Exception {
    List<RevenueGrowthResponse> rows = read(year("/growth", 2025), GROWTH);

    assertThat(rows).hasSize(12);
    assertThat(List.of(rows.get(0), rows.get(3), rows.get(5)))
        .extracting(
            RevenueGrowthResponse::month,
            RevenueGrowthResponse::revenue,
            RevenueGrowthResponse::prevMonthRevenue,
            RevenueGrowthResponse::pctChange,
            RevenueGrowthResponse::sameMonthLastYear,
            RevenueGrowthResponse::yoyPct)
        .containsExactly(
            tuple(date("2025-01-01"), money("200000.00"), ZERO, null, ZERO, null),
            tuple(
                date("2025-04-01"),
                money("6200000.00"),
                money("190000.00"),
                money("3163.16"),
                money("100000.00"), // đơn 8, 20/04/2024
                money("6100.00")),
            tuple(date("2025-06-01"), money("100000.00"), ZERO, null, ZERO, null));
  }

  @Test
  @DisplayName(
      "AC-18: refund ở tháng đặt đơn; 2 refund không nhân doanh thu; refund của đơn PAID rồi hủy"
          + " không có ở đâu")
  void refundsShowOnTheOrderMonth() throws Exception {
    data.completedRefund(world.order(1), "100000");
    data.completedRefund(world.order(1), "50000");
    data.completedRefund(world.order(3), "100000"); // PAID rồi hủy — không phải doanh thu
    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);

    assertThat(read(year("/revenue/monthly", 2025), PERIODS).subList(0, 2))
        .extracting(PeriodRevenueResponse::netRevenue, PeriodRevenueResponse::refundedAmount)
        .containsExactly(
            tuple(money("200000.00"), money("150000.00")), tuple(money("600000.00"), ZERO));
  }

  // ================================================================
  // Theo category, top N
  // ================================================================

  @Test
  @DisplayName(
      "AC-02: /revenue/by-category — tổng = SUM(line_total) của đơn được tính (câu SQL viết tay);"
          + " /top/categories cộng category con vào category gốc")
  void categoryRevenueCrossChecksLineTotals() throws Exception {
    List<CategoryRevenueResponse> cells = read(year("/revenue/by-category", 2025), CATEGORY_CELLS);

    assertThat(cells).hasSize(8);
    assertThat(
            cells.stream()
                .map(CategoryRevenueResponse::grossRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualTo(lineTotalsOf2025())
        .isEqualByComparingTo("7340000");
    // Qua tang chưa phân loại: một ô có category null
    assertThat(cells)
        .contains(
            new CategoryRevenueResponse(date("2025-03-01"), null, null, 1, 3, money("90000.00")));

    assertThat(read(range("/top/categories", YEAR_FROM, YEAR_TO), TOP_CATEGORIES))
        .containsExactly(
            new TopCategoryResponse(1, world.electronics(), "Dien tu", 5, 36, money("6900000.00")),
            new TopCategoryResponse(2, world.fashion(), "Thoi trang", 3, 7, money("350000.00")));
  }

  @Test
  @DisplayName(
      "AC-03: /top/products khớp GROUP BY viết tay; n mặc định 10, ?n=2 còn 2 sản phẩm đầu; hòa số"
          + " lượng thì doanh số cao hơn trước")
  void topProductsCrossCheckManualGroupBy() throws Exception {
    List<TopProductResponse> products =
        read(range("/top/products", YEAR_FROM, YEAR_TO), TOP_PRODUCTS);
    List<Tuple> manual =
        jdbc.sql(
                """
                SELECT oi.product_id, sum(oi.quantity), sum(oi.line_total)
                FROM app.order_items oi
                JOIN app.orders o ON o.id = oi.order_id
                WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
                  AND o.created_at >= '2025-01-01 00:00+07'
                  AND o.created_at < '2026-01-01 00:00+07'
                GROUP BY oi.product_id
                """)
            .query(
                (rs, rowNum) ->
                    tuple(rs.getObject(1, UUID.class), rs.getLong(2), rs.getBigDecimal(3)))
            .list();

    assertThat(products)
        .extracting(
            TopProductResponse::productId,
            TopProductResponse::unitsSold,
            TopProductResponse::revenue)
        .containsExactlyInAnyOrderElementsOf(manual);
    assertThat(products)
        .containsExactly(
            new TopProductResponse(
                world.laptopY(), "Laptop Y", "DASH-P2", 33, money("6600000.00"), 3),
            new TopProductResponse(world.shirtZ(), "Ao Z", "DASH-P3", 7, money("350000.00"), 3),
            new TopProductResponse(world.phoneX(), "Phone X", "DASH-P1", 3, money("300000.00"), 2),
            new TopProductResponse(world.gift(), "Qua tang", "DASH-P4", 3, money("90000.00"), 1));
    assertThat(read(range("/top/products", YEAR_FROM, YEAR_TO).param("n", "2"), TOP_PRODUCTS))
        .extracting(TopProductResponse::productId)
        .containsExactly(world.laptopY(), world.shirtZ());
  }

  @Test
  @DisplayName(
      "/top/customers — ngưỡng lọc theo tổng của khách (HAVING), không theo từng đơn;"
          + " minTotalSpent mặc định 0 = mọi khách có đơn")
  void topCustomersFilterTotalsWithHaving() throws Exception {
    // C1 không có đơn nào từ 500.000 nhưng tổng 700.000 — vẫn vào; C3 tổng 400.000 — không
    assertThat(
            read(
                range("/top/customers", YEAR_FROM, YEAR_TO).param("minTotalSpent", "500000"),
                TOP_CUSTOMERS))
        .containsExactly(
            new TopCustomerResponse(
                world.c2(),
                "dash-c2@example.com",
                "Binh Tran",
                2,
                money("6190000.00"),
                money("3095000.00"),
                vn("2025-04-01T00:15").toInstant()),
            new TopCustomerResponse(
                world.c1(),
                "dash-c1@example.com",
                "An Nguyen",
                4,
                money("700000.00"),
                money("175000.00"),
                vn("2025-06-10T10:00").toInstant()));
    assertThat(read(range("/top/customers", YEAR_FROM, YEAR_TO), TOP_CUSTOMERS))
        .extracting(TopCustomerResponse::customerId)
        .containsExactly(world.c2(), world.c1(), world.c3());
  }

  // ================================================================
  // Phễu, cohort, GROUPING SETS
  // ================================================================

  @Test
  @DisplayName(
      "AC-04: /funnel cả năm — placed = COUNT(*) đơn đặt trong năm (câu SQL viết tay); đơn PAID rồi"
          + " hủy vẫn ở bước paid")
  void funnelPlacedCrossChecksOrderCount() throws Exception {
    OrderFunnelResponse funnel =
        read(range("/funnel", YEAR_FROM, YEAR_TO), OrderFunnelResponse.class);
    long placed =
        jdbc.sql(
                """
                SELECT count(*) FROM app.orders
                WHERE created_at >= '2025-01-01 00:00+07' AND created_at < '2026-01-01 00:00+07'
                """)
            .query(Long.class)
            .single();

    assertThat(funnel.placed()).isEqualTo(placed);
    assertThat(funnel)
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
  }

  @Test
  @DisplayName(
      "AC-05: /cohort?cohortMonth=2025-01 — cohortSize = số khách đăng ký tháng 1 giờ VN (câu SQL"
          + " viết tay), months mặc định 12; cohort ở tương lai → []")
  void cohortSizeCrossChecksRegistrations() throws Exception {
    List<CohortRetentionResponse> rows =
        read(get(DASHBOARD + "/cohort").param("cohortMonth", "2025-01"), COHORT);
    long registered =
        jdbc.sql(
                """
                SELECT count(*) FROM app.customers
                WHERE created_at >= '2025-01-01 00:00+07' AND created_at < '2025-02-01 00:00+07'
                """)
            .query(Long.class)
            .single();

    // C2 đăng ký 23:30 ngày 31/01 — trong cohort; C3 00:30 ngày 01/02 — không
    assertThat(registered).isEqualTo(3);
    assertThat(rows).hasSize(12).allSatisfy(r -> assertThat(r.cohortSize()).isEqualTo(registered));
    assertThat(rows.subList(0, 6))
        .extracting(CohortRetentionResponse::activeCustomers, CohortRetentionResponse::retentionPct)
        .containsExactly(
            tuple(1L, money("33.33")),
            tuple(1L, money("33.33")),
            tuple(1L, money("33.33")),
            tuple(2L, money("66.67")),
            tuple(0L, ZERO), // tháng 5: không ai mua — vẫn có row
            tuple(1L, money("33.33")));
    assertThat(rows.subList(6, 12))
        .extracting(CohortRetentionResponse::activeCustomers, CohortRetentionResponse::retentionPct)
        .containsOnly(tuple(0L, ZERO));

    // Service cắt theo tháng hiện tại của cửa hàng (Clock thật): tháng chưa tới không có row
    assertThat(read(get(DASHBOARD + "/cohort").param("cohortMonth", "2099-12"), COHORT)).isEmpty();
  }

  @Test
  @DisplayName(
      "AC-07: /revenue/grouping-sets — 18 row theo cấp; tổng cộng = tổng mỗi cấp = SUM(line_total)")
  void groupingSetsGrandTotalMatchesEveryLevel() throws Exception {
    List<RevenueGroupingSetResponse> rows =
        read(range("/revenue/grouping-sets", YEAR_FROM, YEAR_TO), GROUPING_SETS);

    assertThat(rows)
        .extracting(RevenueGroupingSetResponse::groupingLevel)
        .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3);
    assertThat(rows.getLast())
        .isEqualTo(new RevenueGroupingSetResponse(null, null, null, money("7340000.00"), 7, 3));
    assertThat(rows.getLast().revenue()).isEqualTo(lineTotalsOf2025());
    for (int level = 0; level <= 2; level++) {
      int lvl = level;
      assertThat(
              rows.stream()
                  .filter(r -> r.groupingLevel() == lvl)
                  .map(RevenueGroupingSetResponse::revenue)
                  .reduce(BigDecimal.ZERO, BigDecimal::add))
          .as("tổng các row cấp %d", lvl)
          .isEqualTo(rows.getLast().revenue());
    }
  }

  @Test
  @DisplayName(
      "AC-08: khoảng không có đơn — phễu một object toàn 0, tỷ lệ null; top N / phân khúc []; doanh"
          + " thu 0.00; GROUPING SETS chỉ row tổng cộng 0.00")
  void emptyPeriodIsZeroNotMissing() throws Exception {
    String from = "2020-01-01";
    String to = "2020-01-31";

    assertThat(read(range("/funnel", from, to), OrderFunnelResponse.class))
        .isEqualTo(new OrderFunnelResponse(0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null));
    for (String path : List.of("/top/products", "/top/customers", "/top/categories", "/aov")) {
      assertThat(body(range(path, from, to))).as(path).isEqualTo("[]");
    }
    assertThat(read(range("/revenue/daily", from, to), PERIODS))
        .hasSize(31)
        .allSatisfy(r -> assertThat(r).isEqualTo(emptyPeriod(r.periodStart())));
    assertThat(body(range("/revenue/grouping-sets", from, to)))
        .isEqualTo(
            "[{\"month\":null,\"categoryId\":null,\"categoryName\":null,\"revenue\":0.00,"
                + "\"orderCount\":0,\"groupingLevel\":3}]");
  }

  // ================================================================
  // LTV, phân khúc, tồn kho
  // ================================================================

  @Test
  @DisplayName(
      "AC-09: /customers/{id}/ltv — khách chưa có đơn: 0 đơn, 0.00; khách có đơn: cùng số với"
          + " /profile (F02); khách không tồn tại → 404 CUS_NOT_FOUND")
  void lifetimeValueMatchesCustomerProfile() throws Exception {
    CustomerLifetimeValueResponse ltv =
        read(get(LTV, world.c1()), CustomerLifetimeValueResponse.class);

    assertThat(ltv)
        .isEqualTo(
            new CustomerLifetimeValueResponse(
                world.c1(),
                "dash-c1@example.com",
                4,
                money("700000.00"),
                money("175000.00"),
                vn("2025-01-10T09:00").toInstant(),
                vn("2025-06-10T10:00").toInstant()));
    assertThat(read(get(PROFILE, world.c1()), CustomerProfileResponse.class))
        .extracting(
            CustomerProfileResponse::ordersCount,
            CustomerProfileResponse::totalSpent,
            CustomerProfileResponse::lastOrderAt)
        .containsExactly(ltv.totalOrders(), ltv.totalSpent(), ltv.lastOrderAt());

    assertThat(read(get(LTV, world.c4()), CustomerLifetimeValueResponse.class))
        .isEqualTo(
            new CustomerLifetimeValueResponse(
                world.c4(), "dash-c4@example.com", 0, ZERO, null, null, null));
    mockMvc
        .perform(get(LTV, UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("CUS_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "/aov — phân khúc theo thứ tự giá trị, trung vị / p95 làm tròn 2 chữ số; /inventory/turnover"
          + " tháng 4 dựng từ sổ tồn kho")
  void segmentsAndInventoryTurnover() throws Exception {
    data.seedInventoryLog(world);

    assertThat(read(range("/aov", YEAR_FROM, YEAR_TO), SEGMENTS))
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
    // n = 1: sản phẩm của các lớp test khác không có sổ trong tháng 4/2025 — vòng quay 0 hoặc null
    assertThat(
            read(
                range("/inventory/turnover", "2025-04-01", "2025-04-30").param("n", "1"), TURNOVER))
        .containsExactly(
            new InventoryTurnoverResponse(
                world.phoneX(), "Phone X", "DASH-P1", 30, 100, 70, money("0.35")));
  }

  @Test
  @DisplayName(
      "JSON từ số liệu thật: tiền giữ 2 chữ số lẻ, field null có mặt; thời điểm ISO-8601 UTC —"
          + " 00:15 ngày 01/04 giờ VN là 17:15Z ngày 31/03")
  void jsonOfRealValues() throws Exception {
    assertThat(body(range("/revenue/daily", "2025-04-16", "2025-04-16")))
        .isEqualTo(
            "[{\"periodStart\":\"2025-04-16\",\"orderCount\":1,\"completedCount\":0,"
                + "\"grossSubtotal\":0.00,\"totalDiscount\":0.00,\"totalShipping\":0.00,"
                + "\"netRevenue\":0.00,\"refundedAmount\":0.00,\"avgOrderValue\":null}]");
    // C2: đơn được tính đầu tiên 10:00 ngày 03/03, cuối cùng 00:15 ngày 01/04 (giờ VN)
    assertThat(body(get(LTV, world.c2())))
        .contains(
            "\"totalSpent\":6190000.00",
            "\"firstOrderAt\":\"2025-03-03T03:00:00Z\"",
            "\"lastOrderAt\":\"2025-03-31T17:15:00Z\"");
  }

  // ================================================================
  // Lỗi
  // ================================================================

  static Stream<Arguments> rangesRejectedByTheService() {
    return Stream.of(
        Arguments.of(
            "from > to",
            range("/funnel", "2025-03-01", "2025-02-01"),
            "from (2025-03-01) phải trước hoặc bằng to (2025-02-01)."),
        Arguments.of(
            "367 ngày",
            range("/revenue/daily", "2025-01-01", "2026-01-02"),
            "Khoảng tối đa 366 ngày, nhận 367 ngày (2025-01-01 – 2026-01-02)."),
        // %2B trên URL — MockMvc nhận giá trị đã giải mã qua param(), không qua query string
        Arguments.of(
            "to=%2B999999999-12-31 (LocalDate.MAX)",
            range("/revenue/daily", "2025-01-01", "+999999999-12-31"),
            "from, to phải nằm trong 2000-01-01 – 2099-12-31, nhận from=2025-01-01,"
                + " to=+999999999-12-31."));
  }

  /**
   * Bean Validation không diễn đạt được luật của khoảng ngày — service kiểm (bài 03). Trần thời
   * gian: không có kiểm năm ở service thì {@code LocalDate.MAX} tới pgjdbc thành {@code infinity}
   * và {@code generate_series} chạy không dừng — test đỏ thay vì treo cả build.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("rangesRejectedByTheService")
  @DisplayName("service thật chặn khoảng ngày → 400 VALIDATION_FAILED, detail của service")
  void serviceRejectsInvalidRanges(
      String name, MockHttpServletRequestBuilder request, String detail) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(LockTestSupport.WAIT_SECONDS),
        () ->
            mockMvc
                .perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.errors").doesNotExist()));
  }

  /**
   * Chuỗi lỗi thật: MV {@code WITH NO DATA} → Postgres {@code 55000} → service đổi thành {@code
   * ServiceUnavailableException} → handler: 503 problem+json, log ERROR. Job làm mới (ở đây gọi
   * tay) nạp lại MV → 200.
   */
  @Test
  @DisplayName(
      "MV WITH NO DATA → 4 endpoint đọc MV trả 503 DASH_REVENUE_NOT_READY, báo cáo khác vẫn 200;"
          + " refresh như job → 200 lại")
  void unpopulatedRevenueViewIsServiceUnavailableUntilRefreshed(CapturedOutput output)
      throws Exception {
    data.unpopulateRevenueView();

    for (MockHttpServletRequestBuilder request :
        List.of(
            range("/revenue/daily", "2025-04-01", "2025-04-01"),
            range("/revenue/weekly", "2025-04-01", "2025-04-01"),
            year("/revenue/monthly", 2025),
            year("/growth", 2025))) {
      mockMvc
          .perform(request)
          .andExpect(status().isServiceUnavailable())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("DASH_REVENUE_NOT_READY"))
          .andExpect(jsonPath("$.title").value("Revenue data is not ready yet"))
          .andExpect(
              jsonPath("$.detail")
                  .value(
                      "Số liệu doanh thu chưa sẵn sàng — đang chờ lần làm mới kế tiếp, thử lại"
                          + " sau."));
    }
    assertThat(read(range("/funnel", YEAR_FROM, YEAR_TO), OrderFunnelResponse.class).placed())
        .isEqualTo(10);
    assertThat(output)
        .contains("Business 5xx [DASH_REVENUE_NOT_READY] at /api/v1/dashboard/revenue/daily")
        .doesNotContain("marked as broken");

    assertThat(dashboardService.refreshDailyRevenue()).isEqualTo(RefreshOutcome.POPULATED);

    assertThat(read(range("/revenue/daily", "2025-04-01", "2025-04-01"), PERIODS))
        .extracting(PeriodRevenueResponse::netRevenue)
        .containsExactly(money("6000000.00"));
  }
}
