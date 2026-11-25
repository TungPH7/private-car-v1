package com.tungph7.learnpostgres.feature.dashboard.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

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
 * 14 báo cáo của dashboard (F07) + làm mới materialized view mà doanh thu theo kỳ và tăng trưởng
 * đọc.
 *
 * <p>Báo cáo chỉ đọc: chạy trong transaction {@code READ ONLY} của Postgres — lệnh ghi lỡ lọt vào
 * bị từ chối ({@code 25006}). Ngày, tháng, năm theo lịch của cửa hàng; khoảng {@code [from, to]}
 * lấy cả hai đầu.
 *
 * <p>Đầu vào kiểm ở đây, sai → 400 {@code VALIDATION_FAILED}. Repository không chặn gì: {@code from
 * > to} ra list rỗng, {@code n} âm là lỗi SQL của {@code LIMIT}, năm 1 là lỗi của {@code
 * make_date}.
 *
 * <ul>
 *   <li>Khoảng ngày: bắt buộc, {@code from <= to}, dài tối đa {@link #MAX_RANGE_DAYS} ngày.
 *   <li>Mọi ngày, tháng, năm nằm trong {@link #MIN_YEAR} – {@link #MAX_YEAR}.
 *   <li>{@code n} của top N: 1 – {@link #MAX_TOP_N}. {@code months} của cohort: 1 – {@link
 *       #MAX_COHORT_MONTHS}.
 *   <li>{@code minTotalSpent}: bắt buộc, không âm, tối đa {@link #MAX_SPEND_INTEGER_DIGITS} chữ số
 *       phần nguyên và {@link #MAX_SPEND_FRACTION_DIGITS} chữ số lẻ.
 * </ul>
 *
 * <p>Bốn báo cáo đọc {@code app.mv_daily_revenue} (doanh thu ngày / tuần / tháng, tăng trưởng): MV
 * đang chưa có dữ liệu ({@code REFRESH ... WITH NO DATA}) → 503 {@code DASH_REVENUE_NOT_READY} —
 * tạm thời, {@link #refreshDailyRevenue()} nạp lại ở lần chạy kế tiếp của job.
 */
public interface DashboardService {

  /**
   * Khoảng ngày dài nhất, tính cả hai đầu — một năm, kể cả năm nhuận. Chặn chi phí của một request:
   * báo cáo đọc dòng hàng (top N, GROUPING SETS...) chạy lâu tỷ lệ với số dòng hàng trong khoảng.
   */
  int MAX_RANGE_DAYS = 366;

  /** {@code n} lớn nhất của các báo cáo top N. */
  int MAX_TOP_N = 100;

  /** Số tháng lớn nhất của cohort — hai năm tính từ tháng đăng ký. */
  int MAX_COHORT_MONTHS = 24;

  /** Năm sớm nhất được hỏi — mọi ngày / tháng / năm của báo cáo. */
  int MIN_YEAR = 2000;

  /**
   * Năm muộn nhất được hỏi. Ngoài {@link #MIN_YEAR} – {@code MAX_YEAR} là gõ sai, không phải dữ
   * liệu.
   */
  int MAX_YEAR = 2099;

  /**
   * Chữ số phần nguyên tối đa của {@code minTotalSpent} — dưới 10^15, xa trên mọi tổng chi tiêu
   * thật (mỗi đơn dưới 10^10, cột {@code NUMERIC(12,2)}). Không có trần thì số cực lớn tới được
   * pgjdbc, và từ {@code 1e131072} trở lên pgjdbc gửi thành {@code 0}: ngưỡng "không ai đạt" lặng
   * lẽ thành "mọi khách".
   */
  int MAX_SPEND_INTEGER_DIGITS = 15;

  /**
   * Chữ số lẻ tối đa của {@code minTotalSpent} — tiền có 2 chữ số lẻ như cột {@code total}. Đếm như
   * client gửi ({@code 100.500} là 3 chữ số lẻ). Không có trần thì {@code 1e-16384} trở xuống làm
   * Postgres từ chối tham số ({@code 22P03}), lỗi lớp 22 → 409.
   */
  int MAX_SPEND_FRACTION_DIGITS = 2;

  // ---- 1–3. Doanh thu theo kỳ — đọc app.mv_daily_revenue (chưa có dữ liệu → 503) ----

  /** Mỗi ngày trong {@code [from, to]} một row; ngày không có đơn vẫn có row, mọi số = 0. */
  List<PeriodRevenueResponse> dailyRevenue(LocalDate from, LocalDate to);

  /**
   * Mỗi tuần (thứ Hai – Chủ nhật) chạm vào {@code [from, to]} một row — chỉ cộng các ngày trong
   * khoảng.
   */
  List<PeriodRevenueResponse> weeklyRevenue(LocalDate from, LocalDate to);

  /** 12 tháng của {@code year}. */
  List<PeriodRevenueResponse> monthlyRevenue(int year);

  // ---- 4. Theo category ----

  /**
   * Doanh số hàng theo tháng × category trực tiếp của sản phẩm, năm {@code year} — chỉ ô có bán.
   */
  List<CategoryRevenueResponse> monthlyRevenueByCategory(int year);

  // ---- 5–6. Top N ----

  /** {@code n} sản phẩm bán nhiều nhất (theo số lượng) trong {@code [from, to]}. */
  List<TopProductResponse> topNBestSellers(int n, LocalDate from, LocalDate to);

  /**
   * {@code n} khách chi tiêu nhiều nhất trong {@code [from, to]}.
   *
   * @param minTotalSpent chỉ lấy khách tiêu ít nhất chừng này trong khoảng; {@code 0} = mọi khách
   */
  List<TopCustomerResponse> topNCustomersBySpend(
      int n, BigDecimal minTotalSpent, LocalDate from, LocalDate to);

  // ---- 7–8. Phễu, cohort ----

  /** Phễu của mọi đơn đặt trong {@code [from, to]} — luôn một row, khoảng không có đơn: toàn 0. */
  OrderFunnelResponse orderFunnel(LocalDate from, LocalDate to);

  /**
   * Retention của khách đăng ký trong {@code cohortMonth}: từ tháng đăng ký, tối đa {@code months}
   * tháng — nhưng không quá tháng hiện tại của cửa hàng. Tháng chưa tới chưa có số liệu; để nó ra
   * 0% là vẽ một cú rơi không có thật. Cohort ở tương lai → list rỗng.
   */
  List<CohortRetentionResponse> customerCohortRetention(YearMonth cohortMonth, int months);

  // ---- 9–13. Phân tích ----

  /** {@code n} sản phẩm ACTIVE có vòng quay tồn kho cao nhất trong {@code [from, to]}. */
  List<InventoryTurnoverResponse> inventoryTurnover(int n, LocalDate from, LocalDate to);

  /** Thống kê giá trị đơn được tính trong {@code [from, to]} theo phân khúc, SMALL → VIP. */
  List<OrderSegmentResponse> avgOrderValueBySegment(LocalDate from, LocalDate to);

  /** 12 tháng của {@code year}, mỗi tháng so với tháng trước và cùng tháng năm trước. */
  List<RevenueGrowthResponse> monthOverMonthGrowth(int year);

  /** {@code n} category gốc có doanh số cao nhất trong {@code [from, to]}, gồm cả category con. */
  List<TopCategoryResponse> topCategoriesByRevenue(int n, LocalDate from, LocalDate to);

  /** Doanh số trong {@code [from, to]} ở 4 cấp: tháng × category, tháng, category, tổng cộng. */
  List<RevenueGroupingSetResponse> revenueGroupingSets(LocalDate from, LocalDate to);

  // ---- 14. Theo khách ----

  /**
   * Giá trị trọn đời của khách. Khách chưa có đơn được tính vẫn có kết quả (0 đơn, 0.00); khách
   * không tồn tại → 404 {@code CUS_NOT_FOUND}.
   */
  CustomerLifetimeValueResponse customerLifetimeValue(UUID customerId);

  // ---- Bảo trì ----

  /**
   * Làm mới {@code app.mv_daily_revenue} — lệnh <b>ghi</b>, chạy trong transaction đọc-ghi của
   * riêng nó và commit khi xong. Nhiều instance gọi cùng lúc: một instance refresh, các instance
   * còn lại nhận {@link RefreshOutcome#SKIPPED} ngay, không xếp hàng chờ.
   */
  RefreshOutcome refreshDailyRevenue();
}
