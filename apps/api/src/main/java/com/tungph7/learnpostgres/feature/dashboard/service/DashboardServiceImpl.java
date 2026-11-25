package com.tungph7.learnpostgres.feature.dashboard.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
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
import com.tungph7.learnpostgres.feature.dashboard.repository.DashboardQueryRepository;

/**
 * Cài đặt {@link DashboardService}: kiểm đầu vào rồi gọi repository — logic của từng báo cáo nằm
 * trong SQL ({@link DashboardQueryRepository}).
 *
 * <p><b>Transaction.</b> Mặc định của class là {@code readOnly = true}: Spring đánh dấu connection
 * read-only, pgjdbc mở transaction {@code READ ONLY} của Postgres. Riêng {@link
 * #refreshDailyRevenue()} là đọc-ghi, và PHẢI có transaction:
 *
 * <ul>
 *   <li>Trong transaction READ ONLY, {@code REFRESH} bị từ chối: {@code 25006}.
 *   <li>Không có transaction thì hỏng theo cách tệ hơn: Hikari cấu hình {@code auto-commit: false},
 *       connection về lại pool mà còn việc chưa commit thì bị rollback — refresh mất lặng lẽ, không
 *       có lỗi nào.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

  private static final LocalDate EARLIEST_DATE = LocalDate.of(MIN_YEAR, Month.JANUARY, 1);
  private static final LocalDate LATEST_DATE = LocalDate.of(MAX_YEAR, Month.DECEMBER, 31);

  /** SQLSTATE {@code object_not_in_prerequisite_state} — đọc MV đang {@code WITH NO DATA}. */
  private static final String OBJECT_NOT_IN_PREREQUISITE_STATE = "55000";

  private final DashboardQueryRepository repository;
  private final Clock clock;
  private final BusinessProperties businessProperties;

  public DashboardServiceImpl(
      DashboardQueryRepository repository, Clock clock, BusinessProperties businessProperties) {
    this.repository = repository;
    this.clock = clock;
    this.businessProperties = businessProperties;
  }

  // ================================================================
  // 1–3. Doanh thu theo kỳ
  // ================================================================

  @Override
  public List<PeriodRevenueResponse> dailyRevenue(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return readDailyRevenueView(() -> repository.dailyRevenue(from, to));
  }

  @Override
  public List<PeriodRevenueResponse> weeklyRevenue(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return readDailyRevenueView(() -> repository.weeklyRevenue(from, to));
  }

  @Override
  public List<PeriodRevenueResponse> monthlyRevenue(int year) {
    requireYear(year);
    return readDailyRevenueView(() -> repository.monthlyRevenue(year));
  }

  // ================================================================
  // 4. Theo category
  // ================================================================

  @Override
  public List<CategoryRevenueResponse> monthlyRevenueByCategory(int year) {
    requireYear(year);
    return repository.monthlyRevenueByCategory(year);
  }

  // ================================================================
  // 5–6. Top N
  // ================================================================

  @Override
  public List<TopProductResponse> topNBestSellers(int n, LocalDate from, LocalDate to) {
    requireTopN(n);
    requireRange(from, to);
    return repository.topNBestSellers(n, from, to);
  }

  @Override
  public List<TopCustomerResponse> topNCustomersBySpend(
      int n, BigDecimal minTotalSpent, LocalDate from, LocalDate to) {
    requireTopN(n);
    requireMinTotalSpent(minTotalSpent);
    requireRange(from, to);
    return repository.topNCustomersBySpend(n, minTotalSpent, from, to);
  }

  // ================================================================
  // 7–8. Phễu, cohort
  // ================================================================

  @Override
  public OrderFunnelResponse orderFunnel(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return repository.orderFunnel(from, to);
  }

  /**
   * Repository trả đủ {@code months} tháng, kể cả tháng chưa tới (0 khách) — câu SQL chỉ phụ thuộc
   * tham số. "Bây giờ" là việc của service: lấy từ {@link Clock}, tháng theo business timezone —
   * 00:30 ngày 01/11 giờ VN (17:30 ngày 31/10 UTC) đã là tháng 11.
   */
  @Override
  public List<CohortRetentionResponse> customerCohortRetention(YearMonth cohortMonth, int months) {
    requireCohort(cohortMonth, months);
    YearMonth currentMonth = YearMonth.now(clock.withZone(businessProperties.timezone()));
    long elapsedMonths = ChronoUnit.MONTHS.between(cohortMonth, currentMonth) + 1;
    if (elapsedMonths < 1) {
      return List.of();
    }
    return repository.customerCohortRetention(cohortMonth, (int) Math.min(months, elapsedMonths));
  }

  // ================================================================
  // 9–13. Phân tích
  // ================================================================

  @Override
  public List<InventoryTurnoverResponse> inventoryTurnover(int n, LocalDate from, LocalDate to) {
    requireTopN(n);
    requireRange(from, to);
    return repository.inventoryTurnover(n, from, to);
  }

  @Override
  public List<OrderSegmentResponse> avgOrderValueBySegment(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return repository.avgOrderValueBySegment(from, to);
  }

  @Override
  public List<RevenueGrowthResponse> monthOverMonthGrowth(int year) {
    requireYear(year);
    return readDailyRevenueView(() -> repository.monthOverMonthGrowth(year));
  }

  @Override
  public List<TopCategoryResponse> topCategoriesByRevenue(int n, LocalDate from, LocalDate to) {
    requireTopN(n);
    requireRange(from, to);
    return repository.topCategoriesByRevenue(n, from, to);
  }

  @Override
  public List<RevenueGroupingSetResponse> revenueGroupingSets(LocalDate from, LocalDate to) {
    requireRange(from, to);
    return repository.revenueGroupingSets(from, to);
  }

  // ================================================================
  // 14. Theo khách
  // ================================================================

  @Override
  public CustomerLifetimeValueResponse customerLifetimeValue(UUID customerId) {
    return repository
        .customerLifetimeValue(customerId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(customerId)));
  }

  // ================================================================
  // Bảo trì
  // ================================================================

  /**
   * Ba bước trong MỘT transaction đọc-ghi; khóa nhả và kết quả commit cùng lúc ở cuối:
   *
   * <ol>
   *   <li><b>Khóa advisory</b> ({@code pg_try_advisory_xact_lock}). Instance khác đang giữ → {@code
   *       SKIPPED}, không chờ. Thiếu bước này, hai {@code REFRESH ... CONCURRENTLY} chạy chồng thì
   *       lần sau xếp hàng chờ lần trước xong rồi tính lại toàn bộ thêm một lần nữa.
   *   <li><b>MV chưa có dữ liệu</b> → {@code REFRESH} thường ({@code POPULATED}). Kiểm trước, không
   *       thử {@code CONCURRENTLY} rồi bắt lỗi: lỗi đó là {@code 0A000} — HikariCP coi là
   *       connection hỏng và đóng nó, rollback sau đó ném "Connection is closed" thay cho lỗi gốc.
   *   <li>{@code REFRESH ... CONCURRENTLY} ({@code REFRESHED}).
   * </ol>
   */
  @Override
  @Transactional
  public RefreshOutcome refreshDailyRevenue() {
    if (!repository.tryLockDailyRevenueRefresh()) {
      return RefreshOutcome.SKIPPED;
    }
    if (!repository.isDailyRevenuePopulated()) {
      repository.populateDailyRevenue();
      return RefreshOutcome.POPULATED;
    }
    repository.refreshDailyRevenue();
    return RefreshOutcome.REFRESHED;
  }

  // ================================================================
  // Đọc MV — chưa có dữ liệu → 503 DASH_REVENUE_NOT_READY
  // ================================================================

  /**
   * Chạy một câu đọc {@code app.mv_daily_revenue} — doanh thu ngày / tuần / tháng, tăng trưởng.
   *
   * <p>MV đang {@code WITH NO DATA} thì Postgres từ chối đọc: {@code 55000} "materialized view ...
   * has not been populated". Tình trạng đó tạm thời — job làm mới nạp lại ở lần chạy kế tiếp
   * ({@link RefreshOutcome#POPULATED}) — nên trả 503, không để rơi xuống 500 "lỗi không lường
   * trước" của handler chung. Lỗi khác đi tiếp nguyên vẹn.
   *
   * <p>Đổi lỗi sau khi câu đọc hỏng, không kiểm {@code relispopulated} trước như refresh: kiểm
   * trước là thêm một câu cho mọi request vì một tình huống hiếm, mà MV vẫn có thể mất dữ liệu giữa
   * hai câu. Bắt ở đây không làm hỏng transaction: repository không có {@code @Transactional} nên
   * không có gì bị đánh dấu rollback-only, và {@code 55000} không nằm trong các mã HikariCP coi là
   * connection hỏng — exception mới bay qua proxy của service, transaction rollback như mọi lỗi
   * khác.
   */
  private static <T> T readDailyRevenueView(Supplier<T> query) {
    try {
      return query.get();
    } catch (DataAccessException ex) {
      if (ex.getMostSpecificCause() instanceof SQLException sql
          && OBJECT_NOT_IN_PREREQUISITE_STATE.equals(sql.getSQLState())) {
        throw new ServiceUnavailableException(
            ErrorCode.DASH_REVENUE_NOT_READY,
            "Số liệu doanh thu chưa sẵn sàng — đang chờ lần làm mới kế tiếp, thử lại sau.",
            ex);
      }
      throw ex;
    }
  }

  // ================================================================
  // Kiểm đầu vào — sai → 400 VALIDATION_FAILED
  // ================================================================

  /**
   * Khoảng {@code [from, to]}: bắt buộc, nằm trong {@link #MIN_YEAR} – {@link #MAX_YEAR}, {@code
   * from <= to}, dài tối đa {@link #MAX_RANGE_DAYS} ngày tính cả hai đầu.
   */
  private static void requireRange(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw invalid("from và to bắt buộc (yyyy-MM-dd), nhận from=%s, to=%s.", from, to);
    }
    if (isOutsideSupportedYears(from) || isOutsideSupportedYears(to)) {
      throw invalid(
          "from, to phải nằm trong %s – %s, nhận from=%s, to=%s.",
          EARLIEST_DATE, LATEST_DATE, from, to);
    }
    if (from.isAfter(to)) {
      throw invalid("from (%s) phải trước hoặc bằng to (%s).", from, to);
    }
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days > MAX_RANGE_DAYS) {
      throw invalid(
          "Khoảng tối đa %d ngày, nhận %d ngày (%s – %s).", MAX_RANGE_DAYS, days, from, to);
    }
  }

  private static boolean isOutsideSupportedYears(LocalDate date) {
    return date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE);
  }

  private static void requireYear(int year) {
    if (year < MIN_YEAR || year > MAX_YEAR) {
      throw invalid("year phải từ %d tới %d, nhận %d.", MIN_YEAR, MAX_YEAR, year);
    }
  }

  private static void requireTopN(int n) {
    if (n < 1 || n > MAX_TOP_N) {
      throw invalid("n phải từ 1 tới %d, nhận %d.", MAX_TOP_N, n);
    }
  }

  /**
   * Đếm chữ số như {@code @Digits} của Bean Validation — phần nguyên = {@code precision - scale},
   * phần lẻ = {@code scale} như client gửi — để controller và service chặn đúng cùng những giá trị.
   */
  private static void requireMinTotalSpent(BigDecimal minTotalSpent) {
    if (minTotalSpent == null || minTotalSpent.signum() < 0) {
      throw invalid("minTotalSpent bắt buộc và không âm, nhận %s.", minTotalSpent);
    }
    int integerDigits = minTotalSpent.precision() - minTotalSpent.scale();
    if (integerDigits > MAX_SPEND_INTEGER_DIGITS
        || minTotalSpent.scale() > MAX_SPEND_FRACTION_DIGITS) {
      throw invalid(
          "minTotalSpent tối đa %d chữ số phần nguyên và %d chữ số lẻ, nhận %s.",
          MAX_SPEND_INTEGER_DIGITS, MAX_SPEND_FRACTION_DIGITS, minTotalSpent);
    }
  }

  private static void requireCohort(YearMonth cohortMonth, int months) {
    if (cohortMonth == null) {
      throw invalid("cohortMonth bắt buộc (yyyy-MM).");
    }
    if (cohortMonth.getYear() < MIN_YEAR || cohortMonth.getYear() > MAX_YEAR) {
      throw invalid(
          "cohortMonth phải nằm trong %d-01 – %d-12, nhận %s.", MIN_YEAR, MAX_YEAR, cohortMonth);
    }
    if (months < 1 || months > MAX_COHORT_MONTHS) {
      throw invalid("months phải từ 1 tới %d, nhận %d.", MAX_COHORT_MONTHS, months);
    }
  }

  private static BadRequestException invalid(String format, Object... args) {
    return new BadRequestException(ErrorCode.VALIDATION_FAILED, format.formatted(args));
  }
}
