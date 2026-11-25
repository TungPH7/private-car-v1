package com.tungph7.learnpostgres.feature.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

import com.tungph7.learnpostgres.common.config.SchedulingConfig;

/**
 * {@link DailyRevenueRefreshJob}: bật / tắt theo {@code app.dashboard.refresh.enabled}, lịch cron
 * đọc theo business timezone, lỗi không làm mất lịch, chỉ ủy quyền cho service và log theo kết quả.
 *
 * <p>Tắt được là điều kiện để integration test chạy ổn định — job nền refresh giữa chừng sẽ làm MV
 * đổi dưới chân test đang kiểm "MV chỉ đổi khi refresh". Dùng {@link ApplicationContextRunner}:
 * context tối thiểu, không DB.
 */
class DailyRevenueRefreshJobTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(DashboardService.class, () -> mock(DashboardService.class))
          .withUserConfiguration(DailyRevenueRefreshJob.class);

  @Test
  @DisplayName("enabled=true → có bean job")
  void enabledCreatesJob() {
    runner
        .withPropertyValues("app.dashboard.refresh.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(DailyRevenueRefreshJob.class));
  }

  @Test
  @DisplayName("enabled=false → không có bean job (profile test)")
  void disabledSkipsJob() {
    runner
        .withPropertyValues("app.dashboard.refresh.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(DailyRevenueRefreshJob.class));
  }

  @Test
  @DisplayName("thiếu cấu hình → không có bean job — bật phải là quyết định tường minh")
  void missingPropertySkipsJob() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DailyRevenueRefreshJob.class));
  }

  /**
   * "02:00 mỗi ngày" ở New York: lần chạy kế tiếp tính từ 00:00 UTC ngày 10/10/2026 (20:00 ngày
   * 09/10 giờ New York, EDT −4) là 02:00 EDT = 06:00 UTC.
   *
   * <p>Bỏ {@code zone} thì {@code CronTrigger} lấy múi giờ của clock trong trigger context — chạy
   * thật là clock mặc định của scheduler (múi giờ JVM), ở đây là clock UTC của test: ra 02:00 UTC
   * (phản chứng đã chạy). Chọn New York vì nó khác cả UTC lẫn múi giờ máy dev; dùng giờ VN thì trên
   * máy dev (Asia/Bangkok, cùng +07) cron chạy thật không phân biệt được có {@code zone} hay không.
   */
  @Test
  @DisplayName("cron đọc theo app.business.timezone, không theo múi giờ của JVM")
  void cronFollowsBusinessTimezone() {
    runner
        .withUserConfiguration(SchedulingConfig.class)
        .withPropertyValues(
            "app.dashboard.refresh.enabled=true",
            "app.dashboard.refresh.cron=0 0 2 * * *",
            "app.business.timezone=America/New_York")
        .run(
            ctx -> {
              CronTask task = refreshTask(ctx.getBean(ScheduledTaskHolder.class));
              SimpleTriggerContext lastRun =
                  new SimpleTriggerContext(
                      Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC));

              assertThat(task.getExpression()).isEqualTo("0 0 2 * * *");
              assertThat(task.getTrigger().nextExecution(lastRun))
                  .isEqualTo(Instant.parse("2026-10-10T06:00:00Z"));
            });
  }

  /**
   * Job không bắt exception — điều đó chỉ đúng nếu lỗi không làm mất lịch. Dựng scheduler như app
   * ({@code TaskSchedulingAutoConfiguration} → {@code ThreadPoolTaskScheduler}), cron mỗi giây,
   * service lần nào cũng ném lỗi: {@code ErrorHandler} mặc định của Spring cho task lặp lại ghi log
   * ERROR rồi để lần sau chạy tiếp.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("lỗi không làm mất lịch: scheduler log ERROR rồi vẫn gọi lại ở chu kỳ sau")
  void failureKeepsTheSchedule(CapturedOutput output) {
    DashboardService failing = mock(DashboardService.class);
    when(failing.refreshDailyRevenue()).thenThrow(new IllegalStateException("refresh hỏng"));

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withBean(DashboardService.class, () -> failing)
        .withUserConfiguration(SchedulingConfig.class, DailyRevenueRefreshJob.class)
        .withPropertyValues(
            "app.dashboard.refresh.enabled=true",
            "app.dashboard.refresh.cron=* * * * * *",
            "app.business.timezone=Asia/Ho_Chi_Minh")
        .run(ctx -> verify(failing, timeout(5_000).atLeast(2)).refreshDailyRevenue());

    assertThat(
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("ERROR") && line.contains("scheduled task")))
        .isNotEmpty();
  }

  private static CronTask refreshTask(ScheduledTaskHolder holder) {
    return holder.getScheduledTasks().stream()
        .map(ScheduledTask::getTask)
        .filter(CronTask.class::isInstance)
        .map(CronTask.class::cast)
        .filter(task -> task.toString().contains(DailyRevenueRefreshJob.class.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Không thấy cron task của DailyRevenueRefreshJob"));
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("REFRESHED → log INFO kèm thời gian chạy")
  void logsRefreshAtInfo(CapturedOutput output) {
    DashboardService service = mock(DashboardService.class);
    when(service.refreshDailyRevenue()).thenReturn(RefreshOutcome.REFRESHED);

    new DailyRevenueRefreshJob(service).run();

    verify(service).refreshDailyRevenue();
    assertThat(output.getAll().lines().filter(line -> line.contains("REFRESHED")))
        .singleElement()
        .satisfies(line -> assertThat(line).contains("INFO").contains(" ms"));
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("POPULATED → log WARN: MV từng ở trạng thái chưa có dữ liệu là chuyện bất thường")
  void logsPopulateAtWarn(CapturedOutput output) {
    DashboardService service = mock(DashboardService.class);
    when(service.refreshDailyRevenue()).thenReturn(RefreshOutcome.POPULATED);

    new DailyRevenueRefreshJob(service).run();

    assertThat(output.getAll().lines().filter(line -> line.contains("POPULATED")))
        .singleElement()
        .satisfies(line -> assertThat(line).contains("WARN").contains("WITH NO DATA"));
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName(
      "SKIPPED → không log INFO / WARN: bỏ qua vì instance khác đang chạy là chuyện thường")
  void skipIsQuiet(CapturedOutput output) {
    DashboardService service = mock(DashboardService.class);
    when(service.refreshDailyRevenue()).thenReturn(RefreshOutcome.SKIPPED);

    new DailyRevenueRefreshJob(service).run();

    verify(service).refreshDailyRevenue();
    assertThat(
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("SKIPPED"))
                .filter(line -> line.contains("INFO") || line.contains("WARN")))
        .isEmpty();
  }
}
