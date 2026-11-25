package com.tungph7.learnpostgres.feature.dashboard.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.dashboard.dto.CategoryRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.CohortRetentionResponse;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API của dashboard (F07) — 13 báo cáo, chỉ GET, dưới {@code /api/v1/dashboard}. Báo cáo thứ
 * 14 (LTV của một khách) ở {@link CustomerLifetimeValueController}, vì URL của nó bắt đầu bằng
 * {@code /customers}.
 *
 * <p>Không mapper: service trả thẳng record của {@code dto} — repository dựng chúng từ result set,
 * không có entity nào để đổi.
 *
 * <p><b>Giới hạn đầu vào khai hai lần, cùng một hằng số của {@link DashboardService}.</b> Bean
 * Validation ở đây cho OpenAPI ({@code minimum} / {@code maximum}) và cho {@code errors[]} theo
 * từng tham số; service kiểm lại vì nó còn được gọi từ chỗ khác, và kiểm những gì annotation không
 * diễn đạt được — khoảng ngày ({@code from <= to}, độ dài, cửa sổ năm). Ngày nhận dạng {@code
 * yyyy-MM-dd} theo lịch của cửa hàng, lấy cả hai đầu.
 *
 * <p>Bốn báo cáo đọc materialized view (doanh thu ngày / tuần / tháng, tăng trưởng) trả 503 {@code
 * DASH_REVENUE_NOT_READY} khi MV đang chưa có dữ liệu — job làm mới nạp lại ở lần chạy kế tiếp.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Dashboard & reports — read-only (F07)")
public class DashboardController {

  /** {@code n} khi client không gửi — mọi báo cáo top N. */
  private static final String DEFAULT_TOP_N = "10";

  /** {@code months} của cohort khi client không gửi — một năm từ tháng đăng ký. */
  private static final String DEFAULT_COHORT_MONTHS = "12";

  // ---- Mô tả của OpenAPI, dựng từ chính hằng số service kiểm — không lệch được ----

  private static final String RANGE_RULES =
      "from > to, khoảng dài quá "
          + DashboardService.MAX_RANGE_DAYS
          + " ngày, ngày ngoài "
          + DashboardService.MIN_YEAR
          + "-01-01 – "
          + DashboardService.MAX_YEAR
          + "-12-31";

  private static final String TOP_N_RULE = "n ngoài 1 – " + DashboardService.MAX_TOP_N;

  private static final String RANGE_ERRORS =
      "VALIDATION_FAILED ("
          + RANGE_RULES
          + "), INVALID_PARAMETER (thiếu from / to, sai yyyy-MM-dd)";

  private static final String TOP_N_RANGE_ERRORS =
      "VALIDATION_FAILED ("
          + TOP_N_RULE
          + ", "
          + RANGE_RULES
          + "), INVALID_PARAMETER (thiếu from / to, sai yyyy-MM-dd, n không phải số)";

  private static final String TOP_CUSTOMERS_ERRORS =
      "VALIDATION_FAILED ("
          + TOP_N_RULE
          + ", minTotalSpent âm hoặc quá "
          + DashboardService.MAX_SPEND_INTEGER_DIGITS
          + " chữ số phần nguyên / "
          + DashboardService.MAX_SPEND_FRACTION_DIGITS
          + " chữ số lẻ, "
          + RANGE_RULES
          + "), INVALID_PARAMETER (thiếu from / to, sai yyyy-MM-dd, n / minTotalSpent không phải"
          + " số)";

  private static final String YEAR_ERRORS =
      "VALIDATION_FAILED (year ngoài "
          + DashboardService.MIN_YEAR
          + " – "
          + DashboardService.MAX_YEAR
          + "), INVALID_PARAMETER (thiếu year, year không phải số)";

  private static final String COHORT_ERRORS =
      "VALIDATION_FAILED (cohortMonth ngoài "
          + DashboardService.MIN_YEAR
          + "-01 – "
          + DashboardService.MAX_YEAR
          + "-12, months ngoài 1 – "
          + DashboardService.MAX_COHORT_MONTHS
          + "), INVALID_PARAMETER (thiếu cohortMonth, sai yyyy-MM, months không phải số)";

  private static final String REVENUE_NOT_READY =
      "DASH_REVENUE_NOT_READY — materialized view đang chưa có dữ liệu (REFRESH ... WITH NO DATA);"
          + " job làm mới nạp lại ở lần chạy kế tiếp, thử lại sau";

  private static final String FROM_MATERIALIZED_VIEW =
      " Đọc materialized view mv_daily_revenue: số liệu mới tới lần làm mới gần nhất (job theo"
          + " lịch, mặc định 5 phút một lần).";

  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  // -----------------------------------------------------------------
  // 1–3. Doanh thu theo kỳ — đọc materialized view
  // -----------------------------------------------------------------

  @GetMapping("/revenue/daily")
  @Operation(
      summary = "Doanh thu theo ngày",
      description =
          "Mỗi ngày trong [from, to] một row; ngày không có đơn vẫn có row, mọi số = 0."
              + FROM_MATERIALIZED_VIEW)
  @ApiResponse(responseCode = "200", description = "Một row mỗi ngày, cũ nhất trước")
  @ApiResponse(responseCode = "400", description = RANGE_ERRORS)
  @ApiResponse(responseCode = "503", description = REVENUE_NOT_READY)
  public List<PeriodRevenueResponse> dailyRevenue(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.dailyRevenue(from, to);
  }

  @GetMapping("/revenue/weekly")
  @Operation(
      summary = "Doanh thu theo tuần",
      description =
          "Tuần thứ Hai – Chủ nhật. Mỗi tuần chạm vào [from, to] một row, chỉ cộng các ngày trong"
              + " khoảng — periodStart của tuần đầu có thể trước from."
              + FROM_MATERIALIZED_VIEW)
  @ApiResponse(responseCode = "200", description = "Một row mỗi tuần, cũ nhất trước")
  @ApiResponse(responseCode = "400", description = RANGE_ERRORS)
  @ApiResponse(responseCode = "503", description = REVENUE_NOT_READY)
  public List<PeriodRevenueResponse> weeklyRevenue(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.weeklyRevenue(from, to);
  }

  @GetMapping("/revenue/monthly")
  @Operation(
      summary = "Doanh thu theo tháng",
      description = "12 tháng của year; tháng không có đơn vẫn có row." + FROM_MATERIALIZED_VIEW)
  @ApiResponse(responseCode = "200", description = "12 row, tháng 1 trước")
  @ApiResponse(responseCode = "400", description = YEAR_ERRORS)
  @ApiResponse(responseCode = "503", description = REVENUE_NOT_READY)
  public List<PeriodRevenueResponse> monthlyRevenue(
      @RequestParam @Min(DashboardService.MIN_YEAR) @Max(DashboardService.MAX_YEAR) int year) {
    return service.monthlyRevenue(year);
  }

  // -----------------------------------------------------------------
  // 4. Theo category
  // -----------------------------------------------------------------

  @GetMapping("/revenue/by-category")
  @Operation(
      summary = "Doanh số hàng theo tháng × category",
      description =
          "Năm year, category trực tiếp (hiện tại) của sản phẩm — không cộng dồn lên category"
              + " cha; chỉ các ô có bán. categoryId = null: sản phẩm chưa phân loại. Tiền trước"
              + " giảm giá (SUM line_total).")
  @ApiResponse(responseCode = "200", description = "Các ô tháng × category có bán")
  @ApiResponse(responseCode = "400", description = YEAR_ERRORS)
  public List<CategoryRevenueResponse> monthlyRevenueByCategory(
      @RequestParam @Min(DashboardService.MIN_YEAR) @Max(DashboardService.MAX_YEAR) int year) {
    return service.monthlyRevenueByCategory(year);
  }

  // -----------------------------------------------------------------
  // 5–6. Top N
  // -----------------------------------------------------------------

  @GetMapping("/top/products")
  @Operation(
      summary = "Top N sản phẩm bán chạy",
      description =
          "Xếp theo số lượng bán, hòa thì theo doanh số. Chỉ đơn được tính doanh thu (PAID /"
              + " SHIPPED / DELIVERED) đặt trong [from, to].")
  @ApiResponse(responseCode = "200", description = "Tối đa n sản phẩm; khoảng không có đơn: []")
  @ApiResponse(responseCode = "400", description = TOP_N_RANGE_ERRORS)
  public List<TopProductResponse> topNBestSellers(
      @RequestParam(defaultValue = DEFAULT_TOP_N) @Min(1) @Max(DashboardService.MAX_TOP_N) int n,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.topNBestSellers(n, from, to);
  }

  @GetMapping("/top/customers")
  @Operation(
      summary = "Top N khách chi tiêu nhiều nhất",
      description =
          "Tổng tiền các đơn được tính trong [from, to], chưa trừ hoàn tiền; chỉ khách tiêu ít"
              + " nhất minTotalSpent.")
  @ApiResponse(responseCode = "200", description = "Tối đa n khách; không ai đạt ngưỡng: []")
  @ApiResponse(responseCode = "400", description = TOP_CUSTOMERS_ERRORS)
  public List<TopCustomerResponse> topNCustomersBySpend(
      @RequestParam(defaultValue = DEFAULT_TOP_N) @Min(1) @Max(DashboardService.MAX_TOP_N) int n,
      @Parameter(
              description =
                  "Ngưỡng tổng chi tiêu trong khoảng; 0 = mọi khách có đơn. Tối đa "
                      + DashboardService.MAX_SPEND_INTEGER_DIGITS
                      + " chữ số phần nguyên, "
                      + DashboardService.MAX_SPEND_FRACTION_DIGITS
                      + " chữ số lẻ")
          @RequestParam(defaultValue = "0")
          @PositiveOrZero
          @Digits(
              integer = DashboardService.MAX_SPEND_INTEGER_DIGITS,
              fraction = DashboardService.MAX_SPEND_FRACTION_DIGITS)
          BigDecimal minTotalSpent,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.topNCustomersBySpend(n, minTotalSpent, from, to);
  }

  // -----------------------------------------------------------------
  // 7–8. Phễu, cohort
  // -----------------------------------------------------------------

  @GetMapping("/funnel")
  @Operation(
      summary = "Phễu đơn hàng",
      description =
          "Mọi đơn đặt trong [from, to]. paid / shipped / delivered đếm đơn đã đi qua bước đó"
              + " (đơn PAID rồi hủy vẫn đã thanh toán); pending / cancelled / expired theo trạng"
              + " thái hiện tại. Tỷ lệ % làm tròn 2 chữ số, null khi mẫu số = 0.")
  @ApiResponse(
      responseCode = "200",
      description = "Luôn một object — khoảng không có đơn: mọi số đếm = 0, tỷ lệ null")
  @ApiResponse(responseCode = "400", description = RANGE_ERRORS)
  public OrderFunnelResponse orderFunnel(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.orderFunnel(from, to);
  }

  @GetMapping("/cohort")
  @Operation(
      summary = "Retention theo cohort tháng đăng ký",
      description =
          "Khách đăng ký trong cohortMonth (yyyy-MM, giờ cửa hàng). Từ tháng đăng ký"
              + " (monthOffset = 0), tối đa months tháng, không quá tháng hiện tại của cửa hàng —"
              + " tháng chưa tới không có row.")
  @ApiResponse(
      responseCode = "200",
      description = "Mỗi tháng một row, kể cả tháng 0 khách mua; cohort ở tương lai: []")
  @ApiResponse(responseCode = "400", description = COHORT_ERRORS)
  public List<CohortRetentionResponse> customerCohortRetention(
      @Parameter(description = "Tháng đăng ký, yyyy-MM", example = "2025-01")
          @RequestParam
          @DateTimeFormat(pattern = "yyyy-MM")
          YearMonth cohortMonth,
      @RequestParam(defaultValue = DEFAULT_COHORT_MONTHS)
          @Min(1)
          @Max(DashboardService.MAX_COHORT_MONTHS)
          int months) {
    return service.customerCohortRetention(cohortMonth, months);
  }

  // -----------------------------------------------------------------
  // 9–13. Phân tích
  // -----------------------------------------------------------------

  @GetMapping("/inventory/turnover")
  @Operation(
      summary = "Vòng quay tồn kho",
      description =
          "n sản phẩm ACTIVE có vòng quay cao nhất trong [from, to]: hàng bán (chốt lúc giao) /"
              + " tồn kho bình quân hai đầu kỳ, dựng từ sổ inventory_movements. turnoverRatio null"
              + " khi tồn kho bình quân = 0.")
  @ApiResponse(responseCode = "200", description = "Tối đa n sản phẩm")
  @ApiResponse(responseCode = "400", description = TOP_N_RANGE_ERRORS)
  public List<InventoryTurnoverResponse> inventoryTurnover(
      @RequestParam(defaultValue = DEFAULT_TOP_N) @Min(1) @Max(DashboardService.MAX_TOP_N) int n,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.inventoryTurnover(n, from, to);
  }

  @GetMapping("/aov")
  @Operation(
      summary = "Giá trị đơn theo phân khúc",
      description =
          "Đơn được tính trong [from, to] theo phân khúc SMALL → VIP: số đơn, trung bình, min,"
              + " max, tổng, trung vị, p95 (làm tròn 2 chữ số).")
  @ApiResponse(responseCode = "200", description = "Chỉ các phân khúc có đơn, SMALL trước")
  @ApiResponse(responseCode = "400", description = RANGE_ERRORS)
  public List<OrderSegmentResponse> avgOrderValueBySegment(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.avgOrderValueBySegment(from, to);
  }

  @GetMapping("/growth")
  @Operation(
      summary = "Tăng trưởng doanh thu theo tháng (MoM, YoY)",
      description =
          "12 tháng của year, mỗi tháng so với tháng trước và cùng tháng năm trước — tháng 1 so"
              + " với tháng 12 năm trước. % null khi tháng đem ra so không có doanh thu."
              + FROM_MATERIALIZED_VIEW)
  @ApiResponse(responseCode = "200", description = "12 row, tháng 1 trước")
  @ApiResponse(responseCode = "400", description = YEAR_ERRORS)
  @ApiResponse(responseCode = "503", description = REVENUE_NOT_READY)
  public List<RevenueGrowthResponse> monthOverMonthGrowth(
      @RequestParam @Min(DashboardService.MIN_YEAR) @Max(DashboardService.MAX_YEAR) int year) {
    return service.monthOverMonthGrowth(year);
  }

  @GetMapping("/top/categories")
  @Operation(
      summary = "Top N category gốc theo doanh số",
      description =
          "Mỗi category gốc cộng cả hàng của mọi category con cháu; revenueRank theo RANK() —"
              + " bằng nhau thì cùng hạng. Sản phẩm chưa phân loại không thuộc category gốc nào.")
  @ApiResponse(responseCode = "200", description = "Tối đa n category gốc")
  @ApiResponse(responseCode = "400", description = TOP_N_RANGE_ERRORS)
  public List<TopCategoryResponse> topCategoriesByRevenue(
      @RequestParam(defaultValue = DEFAULT_TOP_N) @Min(1) @Max(DashboardService.MAX_TOP_N) int n,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.topCategoriesByRevenue(n, from, to);
  }

  @GetMapping("/revenue/grouping-sets")
  @Operation(
      summary = "Doanh số nhiều cấp (GROUPING SETS)",
      description =
          "4 cấp trong một danh sách, phân biệt bằng groupingLevel: 0 = tháng × category, 1 ="
              + " tổng của tháng, 2 = tổng của category, 3 = tổng cộng. Ở cấp 0, categoryId = null"
              + " là sản phẩm chưa phân loại — loại row đọc từ groupingLevel, không đọc từ null."
              + " orderCount không cộng dồn giữa các row.")
  @ApiResponse(
      responseCode = "200",
      description =
          "Cấp 0 trước, tổng cộng cuối; khoảng không có đơn: chỉ row tổng cộng, revenue 0.00")
  @ApiResponse(responseCode = "400", description = RANGE_ERRORS)
  public List<RevenueGroupingSetResponse> revenueGroupingSets(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
    return service.revenueGroupingSets(from, to);
  }
}
