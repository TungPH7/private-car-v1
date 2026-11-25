package com.tungph7.learnpostgres.feature.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.feature.dashboard.repository.DashboardQueryRepository;

/**
 * {@link BusinessTimezoneCheck}: app không start khi {@code app.business.timezone} khác {@code
 * app.business_timezone()} của DB. Context tối thiểu như {@code BusinessPropertiesTest}; DB giả
 * bằng mock repository — đường thật (hàm V16 trên Postgres) đi qua mọi {@code @SpringBootTest}.
 */
class BusinessTimezoneCheckTest {

  private static ApplicationContextRunner runner(String appZone, String databaseZone) {
    DashboardQueryRepository repository = mock(DashboardQueryRepository.class);
    when(repository.businessTimezone()).thenReturn(databaseZone);
    return new ApplicationContextRunner()
        .withBean(DashboardQueryRepository.class, () -> repository)
        .withBean(BusinessProperties.class, () -> new BusinessProperties(ZoneId.of(appZone)))
        .withUserConfiguration(BusinessTimezoneCheck.class);
  }

  @Test
  @DisplayName("app và DB cùng Asia/Ho_Chi_Minh → context start")
  void startsWhenZonesMatch() {
    runner("Asia/Ho_Chi_Minh", "Asia/Ho_Chi_Minh")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(BusinessTimezoneCheck.class));
  }

  @Test
  @DisplayName("app UTC, DB Asia/Ho_Chi_Minh → context không start, message nêu cả hai")
  void failsWhenZonesDiffer() {
    runner("UTC", "Asia/Ho_Chi_Minh")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.business.timezone = UTC")
                    .hasMessageContaining("app.business_timezone() của DB = Asia/Ho_Chi_Minh"));
  }

  @Test
  @DisplayName("Asia/Saigon (bí danh của Asia/Ho_Chi_Minh) → vẫn coi là lệch: config ghi đúng tên")
  void aliasIsStillAMismatch() {
    runner("Asia/Saigon", "Asia/Ho_Chi_Minh").run(ctx -> assertThat(ctx).hasFailed());
  }
}
