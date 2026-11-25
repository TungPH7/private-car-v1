package com.tungph7.learnpostgres.feature.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.feature.dashboard.dto.CustomerLifetimeValueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderFunnelResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.repository.DashboardQueryRepository;

/**
 * Unit test cho {@link DashboardServiceImpl}: đầu vào sai bị chặn (400) trước khi chạm DB, tháng
 * của cohort cắt theo "bây giờ" của cửa hàng, 404 của LTV, 503 khi MV chưa có dữ liệu, và cây quyết
 * định của refresh.
 *
 * <p>Những thứ mock không chứng minh được — transaction READ ONLY thật, refresh có commit, khóa
 * advisory giữa hai transaction, MV chưa có dữ liệu — ở {@code DashboardServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  /** 10/10/2026 10:00 giờ VN. */
  private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

  private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
  private static final LocalDate TO = LocalDate.of(2025, 1, 31);

  @Mock DashboardQueryRepository repository;

  private DashboardServiceImpl serviceAt(Instant now) {
    return new DashboardServiceImpl(
        repository, Clock.fixed(now, ZoneOffset.UTC), new BusinessProperties(VN));
  }

  private DashboardServiceImpl service() {
    return serviceAt(NOW);
  }

  private static void assertInvalid(Throwable thrown, String messagePart) {
    assertThat(thrown).isInstanceOf(BadRequestException.class).hasMessageContaining(messagePart);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  /** Một báo cáo theo khoảng ngày, gọi với {@code from} / {@code to} cho trước. */
  @FunctionalInterface
  interface RangeReport {
    Object call(DashboardService service, LocalDate from, LocalDate to);
  }

  /** Một báo cáo top N, gọi với {@code n} cho trước. */
  @FunctionalInterface
  interface TopNReport {
    Object call(DashboardService service, int n);
  }

  /** Một báo cáo theo năm. */
  @FunctionalInterface
  interface YearReport {
    Object call(DashboardService service, int year);
  }

  /** 9 / 14 báo cáo nhận khoảng ngày. */
  static Stream<Arguments> rangeReports() {
    return Stream.of(
        Arguments.of("dailyRevenue", (RangeReport) DashboardService::dailyRevenue),
        Arguments.of("weeklyRevenue", (RangeReport) DashboardService::weeklyRevenue),
        Arguments.of(
            "topNBestSellers",
            (RangeReport) (service, from, to) -> service.topNBestSellers(10, from, to)),
        Arguments.of(
            "topNCustomersBySpend",
            (RangeReport)
                (service, from, to) -> service.topNCustomersBySpend(10, BigDecimal.ZERO, from, to)),
        Arguments.of("orderFunnel", (RangeReport) DashboardService::orderFunnel),
        Arguments.of(
            "inventoryTurnover",
            (RangeReport) (service, from, to) -> service.inventoryTurnover(10, from, to)),
        Arguments.of(
            "avgOrderValueBySegment", (RangeReport) DashboardService::avgOrderValueBySegment),
        Arguments.of(
            "topCategoriesByRevenue",
            (RangeReport) (service, from, to) -> service.topCategoriesByRevenue(10, from, to)),
        Arguments.of("revenueGroupingSets", (RangeReport) DashboardService::revenueGroupingSets));
  }

  /** 4 báo cáo top N. */
  static Stream<Arguments> topNReports() {
    return Stream.of(
        Arguments.of(
            "topNBestSellers", (TopNReport) (service, n) -> service.topNBestSellers(n, FROM, TO)),
        Arguments.of(
            "topNCustomersBySpend",
            (TopNReport)
                (service, n) -> service.topNCustomersBySpend(n, BigDecimal.ZERO, FROM, TO)),
        Arguments.of(
            "inventoryTurnover",
            (TopNReport) (service, n) -> service.inventoryTurnover(n, FROM, TO)),
        Arguments.of(
            "topCategoriesByRevenue",
            (TopNReport) (service, n) -> service.topCategoriesByRevenue(n, FROM, TO)));
  }

  /** 3 báo cáo theo năm. */
  static Stream<Arguments> yearReports() {
    return Stream.of(
        Arguments.of("monthlyRevenue", (YearReport) DashboardService::monthlyRevenue),
        Arguments.of(
            "monthlyRevenueByCategory", (YearReport) DashboardService::monthlyRevenueByCategory),
        Arguments.of("monthOverMonthGrowth", (YearReport) DashboardService::monthOverMonthGrowth));
  }

  // ================================================================
  // Chuyển xuống repository
  // ================================================================

  @Test
  @DisplayName("đầu vào hợp lệ → repository nhận đúng tham số, service trả nguyên kết quả")
  void passesArgumentsAndResultsThrough() {
    DashboardServiceImpl service = service();
    List<PeriodRevenueResponse> days = List.of(emptyDay(FROM));
    OrderFunnelResponse funnel =
        new OrderFunnelResponse(0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null);
    BigDecimal minTotalSpent = new BigDecimal("500000");
    when(repository.dailyRevenue(FROM, TO)).thenReturn(days);
    when(repository.orderFunnel(FROM, TO)).thenReturn(funnel);

    assertThat(service.dailyRevenue(FROM, TO)).isSameAs(days);
    assertThat(service.orderFunnel(FROM, TO)).isSameAs(funnel);
    service.weeklyRevenue(FROM, TO);
    service.monthlyRevenue(2025);
    service.monthlyRevenueByCategory(2025);
    service.topNBestSellers(10, FROM, TO);
    service.topNCustomersBySpend(5, minTotalSpent, FROM, TO);
    service.inventoryTurnover(20, FROM, TO);
    service.avgOrderValueBySegment(FROM, TO);
    service.monthOverMonthGrowth(2025);
    service.topCategoriesByRevenue(3, FROM, TO);
    service.revenueGroupingSets(FROM, TO);

    verify(repository).weeklyRevenue(FROM, TO);
    verify(repository).monthlyRevenue(2025);
    verify(repository).monthlyRevenueByCategory(2025);
    verify(repository).topNBestSellers(10, FROM, TO);
    verify(repository).topNCustomersBySpend(5, minTotalSpent, FROM, TO);
    verify(repository).inventoryTurnover(20, FROM, TO);
    verify(repository).avgOrderValueBySegment(FROM, TO);
    verify(repository).monthOverMonthGrowth(2025);
    verify(repository).topCategoriesByRevenue(3, FROM, TO);
    verify(repository).revenueGroupingSets(FROM, TO);
  }

  private static PeriodRevenueResponse emptyDay(LocalDate day) {
    BigDecimal zero = new BigDecimal("0.00");
    return new PeriodRevenueResponse(day, 0, 0, zero, zero, zero, zero, zero, null);
  }

  // ================================================================
  // Khoảng ngày
  // ================================================================

  @ParameterizedTest(name = "{0}")
  @MethodSource("rangeReports")
  @DisplayName("mọi báo cáo theo khoảng: from > to → 400, không chạm DB")
  void everyRangeReportRejectsReversedRange(String name, RangeReport report) {
    Throwable thrown = catchThrowable(() -> report.call(service(), TO, FROM));

    assertInvalid(thrown, "phải trước hoặc bằng to");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rangeReports")
  @DisplayName("mọi báo cáo theo khoảng: thiếu from / to → 400")
  void everyRangeReportRequiresBothBounds(String name, RangeReport report) {
    assertInvalid(catchThrowable(() -> report.call(service(), null, TO)), "bắt buộc");
    assertInvalid(catchThrowable(() -> report.call(service(), FROM, null)), "bắt buộc");
    verifyNoInteractions(repository);
  }

  @Nested
  @DisplayName("độ dài và giới hạn của khoảng")
  class RangeLimits {

    @Test
    @DisplayName("trọn năm nhuận 2024 = 366 ngày → được; thêm 1 ngày → 400")
    void atMostOneLeapYear() {
      LocalDate start = LocalDate.of(2024, 1, 1);

      service().dailyRevenue(start, LocalDate.of(2024, 12, 31));
      Throwable thrown =
          catchThrowable(() -> service().dailyRevenue(start, LocalDate.of(2025, 1, 1)));

      verify(repository).dailyRevenue(start, LocalDate.of(2024, 12, 31));
      assertInvalid(thrown, "tối đa 366 ngày, nhận 367 ngày");
    }

    @Test
    @DisplayName("from = to → một ngày, hợp lệ")
    void singleDay() {
      service().orderFunnel(FROM, FROM);

      verify(repository).orderFunnel(FROM, FROM);
    }

    @Test
    @DisplayName("ngày ngoài 2000 – 2099 → 400; đúng hai mép → được")
    void supportedYears() {
      LocalDate first = LocalDate.of(2000, 1, 1);
      LocalDate last = LocalDate.of(2099, 12, 31);

      service().dailyRevenue(first, first);
      service().dailyRevenue(last, last);
      Throwable before = catchThrowable(() -> service().dailyRevenue(first.minusDays(1), first));
      Throwable after = catchThrowable(() -> service().dailyRevenue(last, last.plusDays(1)));

      verify(repository).dailyRevenue(first, first);
      verify(repository).dailyRevenue(last, last);
      assertInvalid(before, "2000-01-01 – 2099-12-31");
      assertInvalid(after, "2000-01-01 – 2099-12-31");
    }

    /**
     * {@code ?to=%2B999999999-12-31} qua HTTP parse ra đúng {@link LocalDate#MAX}; pgjdbc gửi nó
     * thành {@code infinity} và {@code generate_series} không dừng (bài 03 §4.2).
     */
    @Test
    @DisplayName("LocalDate.MAX / LocalDate.MIN → 400, không tới DB")
    void extremeDatesNeverReachTheDatabase() {
      Throwable max = catchThrowable(() -> service().dailyRevenue(FROM, LocalDate.MAX));
      Throwable min = catchThrowable(() -> service().dailyRevenue(LocalDate.MIN, TO));

      assertInvalid(max, "to=+999999999-12-31");
      assertInvalid(min, "from=-999999999-01-01");
      verifyNoInteractions(repository);
    }
  }

  // ================================================================
  // n, năm, ngưỡng chi tiêu
  // ================================================================

  @ParameterizedTest(name = "{0}")
  @MethodSource("topNReports")
  @DisplayName("mọi báo cáo top N: n ngoài 1 – 100 → 400; 1 và 100 → được")
  void everyTopNReportBoundsN(String name, TopNReport report) {
    assertInvalid(catchThrowable(() -> report.call(service(), 0)), "n phải từ 1 tới 100, nhận 0");
    assertInvalid(catchThrowable(() -> report.call(service(), -1)), "nhận -1");
    assertInvalid(catchThrowable(() -> report.call(service(), 101)), "nhận 101");
    verifyNoInteractions(repository);

    report.call(service(), 1);
    report.call(service(), DashboardService.MAX_TOP_N);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("yearReports")
  @DisplayName("mọi báo cáo theo năm: ngoài 2000 – 2099 → 400; hai mép → được")
  void everyYearReportBoundsYear(String name, YearReport report) {
    assertInvalid(catchThrowable(() -> report.call(service(), 1999)), "year phải từ 2000 tới 2099");
    assertInvalid(catchThrowable(() -> report.call(service(), 2100)), "nhận 2100");
    assertInvalid(catchThrowable(() -> report.call(service(), 1)), "nhận 1");
    verifyNoInteractions(repository);

    report.call(service(), DashboardService.MIN_YEAR);
    report.call(service(), DashboardService.MAX_YEAR);
  }

  @Test
  @DisplayName("topNCustomersBySpend: minTotalSpent thiếu hoặc âm → 400; 0 → mọi khách")
  void minTotalSpentMustBePresentAndNotNegative() {
    assertInvalid(
        catchThrowable(() -> service().topNCustomersBySpend(10, null, FROM, TO)), "minTotalSpent");
    assertInvalid(
        catchThrowable(() -> service().topNCustomersBySpend(10, new BigDecimal("-0.01"), FROM, TO)),
        "nhận -0.01");
    verifyNoInteractions(repository);

    service().topNCustomersBySpend(10, BigDecimal.ZERO, FROM, TO);

    verify(repository).topNCustomersBySpend(10, BigDecimal.ZERO, FROM, TO);
  }

  /**
   * Số chữ số đếm như {@code @Digits} của controller. {@code 1E+999999} không chặn thì tới được
   * pgjdbc và bị gửi thành 0 — ngưỡng "không ai đạt" thành "mọi khách"; {@code 1E-20000} thì
   * Postgres từ chối tham số ({@code 22P03}) → 409.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"100.500", "0.001", "1E-20000", "1000000000000000", "1E+999999"})
  @DisplayName("topNCustomersBySpend: minTotalSpent quá 15 chữ số phần nguyên / 2 chữ số lẻ → 400")
  void minTotalSpentDigitsAreBounded(String value) {
    BigDecimal minTotalSpent = new BigDecimal(value);

    Throwable thrown =
        catchThrowable(() -> service().topNCustomersBySpend(10, minTotalSpent, FROM, TO));

    assertInvalid(thrown, "tối đa 15 chữ số phần nguyên và 2 chữ số lẻ, nhận " + minTotalSpent);
    verifyNoInteractions(repository);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"999999999999999.99", "0.01", "1E+3", "500000"})
  @DisplayName("topNCustomersBySpend: minTotalSpent trong giới hạn → tới repository nguyên vẹn")
  void minTotalSpentWithinDigitsPassesThrough(String value) {
    BigDecimal minTotalSpent = new BigDecimal(value);

    service().topNCustomersBySpend(10, minTotalSpent, FROM, TO);

    verify(repository).topNCustomersBySpend(10, minTotalSpent, FROM, TO);
  }

  // ================================================================
  // Cohort
  // ================================================================

  @Nested
  @DisplayName("customerCohortRetention")
  class CohortRetention {

    @Test
    @DisplayName("cohort đủ lâu → giữ nguyên số tháng được hỏi")
    void keepsMonthsOfAnOldCohort() {
      service().customerCohortRetention(YearMonth.of(2025, 1), 12);

      verify(repository).customerCohortRetention(YearMonth.of(2025, 1), 12);
    }

    @Test
    @DisplayName("cắt ở tháng hiện tại: cohort 08/2026, hỏi 12 tháng, bây giờ 10/2026 → 3 tháng")
    void stopsAtCurrentMonth() {
      service().customerCohortRetention(YearMonth.of(2026, 8), 12);

      verify(repository).customerCohortRetention(YearMonth.of(2026, 8), 3);
    }

    @Test
    @DisplayName("cohort của tháng hiện tại → 1 tháng (tháng 0, đang diễn ra)")
    void currentMonthCohortHasOneMonth() {
      service().customerCohortRetention(YearMonth.of(2026, 10), 12);

      verify(repository).customerCohortRetention(YearMonth.of(2026, 10), 1);
    }

    @Test
    @DisplayName("cohort ở tương lai → list rỗng, không chạm DB")
    void futureCohortIsEmpty() {
      assertThat(service().customerCohortRetention(YearMonth.of(2026, 11), 12)).isEmpty();

      verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("tháng hiện tại theo giờ cửa hàng: 17:30 ngày 31/10 UTC đã là tháng 11 ở VN")
    void currentMonthFollowsBusinessTimezone() {
      DashboardServiceImpl service = serviceAt(Instant.parse("2026-10-31T17:30:00Z"));

      service.customerCohortRetention(YearMonth.of(2026, 11), 12);

      verify(repository).customerCohortRetention(YearMonth.of(2026, 11), 1);
    }

    @Test
    @DisplayName("cohortMonth thiếu / ngoài 2000 – 2099, months ngoài 1 – 24 → 400")
    void rejectsInvalidInput() {
      assertInvalid(
          catchThrowable(() -> service().customerCohortRetention(null, 12)),
          "cohortMonth bắt buộc");
      assertInvalid(
          catchThrowable(() -> service().customerCohortRetention(YearMonth.of(1999, 12), 12)),
          "nhận 1999-12");
      assertInvalid(
          catchThrowable(() -> service().customerCohortRetention(YearMonth.of(2100, 1), 12)),
          "nhận 2100-01");
      assertInvalid(
          catchThrowable(() -> service().customerCohortRetention(YearMonth.of(2025, 1), 0)),
          "months phải từ 1 tới 24, nhận 0");
      assertInvalid(
          catchThrowable(() -> service().customerCohortRetention(YearMonth.of(2025, 1), 25)),
          "nhận 25");
      verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("months = 24 (tối đa) → được")
    void acceptsMaxMonths() {
      service().customerCohortRetention(YearMonth.of(2020, 1), DashboardService.MAX_COHORT_MONTHS);

      verify(repository).customerCohortRetention(YearMonth.of(2020, 1), 24);
    }
  }

  // ================================================================
  // LTV
  // ================================================================

  @Nested
  @DisplayName("customerLifetimeValue")
  class CustomerLifetimeValue {

    private final UUID customerId = UUID.randomUUID();

    @Test
    @DisplayName("có khách → trả nguyên kết quả của repository")
    void returnsValue() {
      CustomerLifetimeValueResponse ltv =
          new CustomerLifetimeValueResponse(
              customerId, "c@example.com", 0, new BigDecimal("0.00"), null, null, null);
      when(repository.customerLifetimeValue(customerId)).thenReturn(Optional.of(ltv));

      assertThat(service().customerLifetimeValue(customerId)).isSameAs(ltv);
    }

    @Test
    @DisplayName(
        "không có khách → 404 CUS_NOT_FOUND (repository trả Optional rỗng, không phải 500)")
    void unknownCustomerIsNotFound() {
      when(repository.customerLifetimeValue(customerId)).thenReturn(Optional.empty());

      Throwable thrown = catchThrowable(() -> service().customerLifetimeValue(customerId));

      assertThat(thrown)
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(customerId.toString());
      assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.CUS_NOT_FOUND);
    }
  }

  // ================================================================
  // Đọc MV chưa có dữ liệu → 503
  // ================================================================

  @Nested
  @DisplayName("MV chưa có dữ liệu")
  class MaterializedViewNotPopulated {

    /**
     * Cách Spring báo lỗi khi Postgres từ chối đọc MV đang {@code WITH NO DATA} — loại exception và
     * SQLSTATE đo trên Postgres thật ở {@code DashboardServiceIntegrationTest}.
     */
    private static UncategorizedSQLException notPopulated() {
      return new UncategorizedSQLException(
          "PreparedStatementCallback",
          "SELECT ... FROM app.mv_daily_revenue",
          new SQLException(
              "ERROR: materialized view \"mv_daily_revenue\" has not been populated", "55000"));
    }

    /**
     * Một báo cáo đọc MV: mock repository ném {@code failure} ở đúng câu báo cáo đó gửi đi. {@code
     * doThrow} chứ không {@code when(...).thenThrow}: dựng lại stub của một method đang ném lỗi
     * bằng {@code when(...)} là gọi trúng mock và ném ngay lúc dựng.
     */
    @FunctionalInterface
    interface Stub {
      void failWith(DashboardQueryRepository repository, RuntimeException failure);
    }

    /** 4 / 14 báo cáo đọc {@code app.mv_daily_revenue}. */
    static Stream<Arguments> materializedViewReports() {
      return Stream.of(
          Arguments.of(
              "dailyRevenue",
              (Stub) (repo, failure) -> doThrow(failure).when(repo).dailyRevenue(FROM, TO),
              (Function<DashboardService, Object>) s -> s.dailyRevenue(FROM, TO)),
          Arguments.of(
              "weeklyRevenue",
              (Stub) (repo, failure) -> doThrow(failure).when(repo).weeklyRevenue(FROM, TO),
              (Function<DashboardService, Object>) s -> s.weeklyRevenue(FROM, TO)),
          Arguments.of(
              "monthlyRevenue",
              (Stub) (repo, failure) -> doThrow(failure).when(repo).monthlyRevenue(2025),
              (Function<DashboardService, Object>) s -> s.monthlyRevenue(2025)),
          Arguments.of(
              "monthOverMonthGrowth",
              (Stub) (repo, failure) -> doThrow(failure).when(repo).monthOverMonthGrowth(2025),
              (Function<DashboardService, Object>) s -> s.monthOverMonthGrowth(2025)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("materializedViewReports")
    @DisplayName("55000 → 503 DASH_REVENUE_NOT_READY, giữ lỗi gốc làm cause")
    void becomesServiceUnavailable(
        String name, Stub stub, Function<DashboardService, Object> report) {
      UncategorizedSQLException failure = notPopulated();
      stub.failWith(repository, failure);

      Throwable thrown = catchThrowable(() -> report.apply(service()));

      assertThat(thrown).isInstanceOf(ServiceUnavailableException.class).cause().isSameAs(failure);
      BusinessException unavailable = (BusinessException) thrown;
      assertThat(unavailable.getCode()).isEqualTo(ErrorCode.DASH_REVENUE_NOT_READY);
      assertThat(unavailable.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("materializedViewReports")
    @DisplayName("lỗi khác (SQLSTATE khác, không có SQLException) → giữ nguyên, không thành 503")
    void otherFailuresPassThrough(
        String name, Stub stub, Function<DashboardService, Object> report) {
      RuntimeException statementTimeout =
          new UncategorizedSQLException(
              "PreparedStatementCallback",
              "SELECT ...",
              new SQLException("ERROR: canceling statement due to statement timeout", "57014"));
      RuntimeException withoutSqlState = new QueryTimeoutException("timeout");

      for (RuntimeException failure : List.of(statementTimeout, withoutSqlState)) {
        stub.failWith(repository, failure);

        assertThat(catchThrowable(() -> report.apply(service()))).isSameAs(failure);
      }
    }

    @Test
    @DisplayName("báo cáo không đọc MV: 55000 giữ nguyên — không phải 'doanh thu chưa sẵn sàng'")
    void onlyMaterializedViewReportsTranslate() {
      UncategorizedSQLException failure = notPopulated();
      when(repository.orderFunnel(FROM, TO)).thenThrow(failure);

      assertThat(catchThrowable(() -> service().orderFunnel(FROM, TO))).isSameAs(failure);
    }
  }

  // ================================================================
  // Refresh
  // ================================================================

  @Nested
  @DisplayName("refreshDailyRevenue")
  class RefreshDailyRevenue {

    @Test
    @DisplayName("instance khác đang giữ khóa → SKIPPED, không đụng tới MV")
    void skipsWhileAnotherInstanceHoldsTheLock() {
      when(repository.tryLockDailyRevenueRefresh()).thenReturn(false);

      assertThat(service().refreshDailyRevenue()).isEqualTo(RefreshOutcome.SKIPPED);

      verify(repository).tryLockDailyRevenueRefresh();
      verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("MV có dữ liệu → khóa, kiểm, REFRESH CONCURRENTLY — đúng thứ tự")
    void refreshesConcurrently() {
      when(repository.tryLockDailyRevenueRefresh()).thenReturn(true);
      when(repository.isDailyRevenuePopulated()).thenReturn(true);

      assertThat(service().refreshDailyRevenue()).isEqualTo(RefreshOutcome.REFRESHED);

      InOrder order = inOrder(repository);
      order.verify(repository).tryLockDailyRevenueRefresh();
      order.verify(repository).isDailyRevenuePopulated();
      order.verify(repository).refreshDailyRevenue();
      verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("MV đang WITH NO DATA → nạp bằng REFRESH thường, không thử CONCURRENTLY (0A000)")
    void populatesInsteadOfFailingWithFeatureNotSupported() {
      when(repository.tryLockDailyRevenueRefresh()).thenReturn(true);
      when(repository.isDailyRevenuePopulated()).thenReturn(false);

      assertThat(service().refreshDailyRevenue()).isEqualTo(RefreshOutcome.POPULATED);

      InOrder order = inOrder(repository);
      order.verify(repository).tryLockDailyRevenueRefresh();
      order.verify(repository).isDailyRevenuePopulated();
      order.verify(repository).populateDailyRevenue();
      verifyNoMoreInteractions(repository);
    }
  }
}
