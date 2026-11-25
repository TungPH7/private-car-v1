package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link OrderExpirationJob}: bật/tắt đúng theo {@code app.order.expiration.enabled} và chỉ ủy
 * quyền cho service.
 *
 * <p>Tắt được là điều kiện để integration test chạy ổn định — job nền mà chạy giữa chừng sẽ expire
 * đơn test đang kiểm. Dùng {@link ApplicationContextRunner}: context tối thiểu, không DB.
 */
class OrderExpirationJobTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(OrderService.class, () -> mock(OrderService.class))
          .withUserConfiguration(OrderExpirationJob.class);

  @Test
  @DisplayName("enabled=true → có bean job")
  void enabledCreatesJob() {
    runner
        .withPropertyValues("app.order.expiration.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(OrderExpirationJob.class));
  }

  @Test
  @DisplayName("enabled=false → không có bean job (profile test)")
  void disabledSkipsJob() {
    runner
        .withPropertyValues("app.order.expiration.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(OrderExpirationJob.class));
  }

  @Test
  @DisplayName("thiếu cấu hình → không có bean job — bật phải là quyết định tường minh")
  void missingPropertySkipsJob() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(OrderExpirationJob.class));
  }

  @Test
  @DisplayName("run() chỉ gọi OrderService.expirePendingOrders")
  void runDelegatesToService() {
    OrderService service = mock(OrderService.class);

    new OrderExpirationJob(service).run();

    verify(service).expirePendingOrders();
  }
}
