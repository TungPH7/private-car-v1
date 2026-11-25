package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + fail-fast của {@link OrderExpirationProperties} — cùng cách {@code
 * BusinessPropertiesTest}: context tối thiểu chỉ có properties này.
 */
class OrderExpirationPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(OrderExpirationProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  @DisplayName("ISO-8601 duration + số nguyên → bind đúng")
  void bindsDurationAndBatchSize() {
    runner
        .withPropertyValues(
            "app.order.expiration.pending-ttl=PT30M", "app.order.expiration.batch-size=100")
        .run(
            ctx -> {
              OrderExpirationProperties props = ctx.getBean(OrderExpirationProperties.class);
              assertThat(props.pendingTtl()).isEqualTo(Duration.ofMinutes(30));
              assertThat(props.batchSize()).isEqualTo(100);
            });
  }

  /** TTL 0 nghĩa là mọi đơn vừa đặt đều "quá hạn" — job sẽ expire đơn ngay khi khách bấm đặt. */
  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "-PT5M"})
  @DisplayName("pending-ttl bằng 0 hoặc âm → context không start")
  void rejectsNonPositiveTtl(String ttl) {
    runner
        .withPropertyValues(
            "app.order.expiration.pending-ttl=" + ttl, "app.order.expiration.batch-size=100")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("batch-size bằng 0 → context không start")
  void rejectsZeroBatchSize() {
    runner
        .withPropertyValues(
            "app.order.expiration.pending-ttl=PT30M", "app.order.expiration.batch-size=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  @DisplayName("thiếu cấu hình → context không start (không âm thầm chạy với giá trị rỗng)")
  void rejectsMissingConfig() {
    runner.run(ctx -> assertThat(ctx).hasFailed());
  }
}
