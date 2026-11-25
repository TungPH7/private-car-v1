package com.tungph7.learnpostgres.feature.dashboard.service;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy {@link DashboardService#refreshDailyRevenue()} theo lịch — doanh thu theo ngày / tuần /
 * tháng và tăng trưởng của dashboard (đọc từ MV) trễ tối đa một chu kỳ.
 *
 * <p>Lớp riêng thay vì đặt {@code @Scheduled} lên service, cùng lý do với {@code
 * OrderExpirationJob} (F05): tắt được bằng cấu hình ({@code app.dashboard.refresh.enabled} —
 * profile test tắt, để MV chỉ đổi khi test tự refresh), và service gọi được thẳng mà không phải chờ
 * lịch.
 *
 * <p>{@code cron} chứ không {@code fixedDelay}: mọi instance của app bấm giờ theo đồng hồ, cùng
 * chạy một lúc, nên khóa advisory trong service gom được chúng lại — một instance refresh, các
 * instance khác {@link RefreshOutcome#SKIPPED}. {@code fixedDelay} đếm từ lúc mỗi instance khởi
 * động, các lần chạy lệch pha nhau và khóa không gom được gì. Cron đọc theo business timezone: lịch
 * kiểu "02:00 mỗi ngày" là 02:00 giờ cửa hàng, không phải giờ của máy chủ.
 *
 * <p>Không bắt exception: transaction đã rollback trong proxy của service; {@code ErrorHandler} mặc
 * định của scheduler ghi log ERROR kèm stack trace, và lần chạy sau vẫn tới theo lịch.
 */
@Component
@ConditionalOnProperty(prefix = "app.dashboard.refresh", name = "enabled", havingValue = "true")
class DailyRevenueRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(DailyRevenueRefreshJob.class);

  private final DashboardService dashboardService;

  DailyRevenueRefreshJob(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @Scheduled(cron = "${app.dashboard.refresh.cron}", zone = "${app.business.timezone}")
  public void run() {
    long startedAt = System.nanoTime();
    RefreshOutcome outcome = dashboardService.refreshDailyRevenue();
    long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    switch (outcome) {
      case SKIPPED -> log.debug("mv_daily_revenue: {} — instance khác đang refresh", outcome);
      case POPULATED ->
          log.warn(
              "mv_daily_revenue: {} trong {} ms — MV đang WITH NO DATA, đã nạp bằng REFRESH"
                  + " không CONCURRENTLY",
              outcome,
              tookMillis);
      default -> log.info("mv_daily_revenue: {} trong {} ms", outcome, tookMillis);
    }
  }
}
