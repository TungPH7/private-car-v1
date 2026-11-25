package com.tungph7.learnpostgres.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + fail-fast của {@link BusinessProperties}. Dùng {@link ApplicationContextRunner} — dựng
 * context tối thiểu chỉ có properties này, không cần DB hay web.
 */
class BusinessPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(BusinessProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  @DisplayName("tên IANA hợp lệ → bind thành ZoneId")
  void bindsIanaZone() {
    runner
        .withPropertyValues("app.business.timezone=Asia/Ho_Chi_Minh")
        .run(
            ctx ->
                assertThat(ctx.getBean(BusinessProperties.class).timezone())
                    .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh")));
  }

  @Test
  @DisplayName("tên zone sai (Asia/Hanoi không tồn tại) → context không start")
  void rejectsUnknownZone() {
    runner
        .withPropertyValues("app.business.timezone=Asia/Hanoi")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("thiếu cấu hình → context không start (không âm thầm dùng TZ của máy)")
  void rejectsMissingZone() {
    runner.run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("giá trị rỗng (APP_BUSINESS_TIMEZONE=) → context không start")
  void rejectsBlankZone() {
    runner.withPropertyValues("app.business.timezone=").run(ctx -> assertThat(ctx).hasFailed());
  }
}
