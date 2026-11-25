package com.tungph7.learnpostgres.feature.order.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tham số nghiệp vụ của việc expire đơn PENDING quá hạn thanh toán — prefix {@code
 * app.order.expiration}.
 *
 * <ul>
 *   <li>{@code pendingTtl} — đơn PENDING lâu hơn khoảng này thì expire (mặc định {@code PT30M}).
 *   <li>{@code batchSize} — số đơn tối đa mỗi lần chạy (mặc định {@code 100}).
 * </ul>
 *
 * <p>Hai khóa còn lại cùng prefix không nằm trong record vì annotation của {@link
 * OrderExpirationJob} đọc thẳng: {@code enabled} ({@code @ConditionalOnProperty}) và {@code
 * interval} ({@code @Scheduled}).
 *
 * <p>Fail-fast giống {@code BusinessProperties}: thiếu, bằng 0 hoặc âm → app không start, thay vì
 * chạy với một giá trị vô nghĩa (TTL 0 là expire mọi đơn vừa đặt).
 */
@ConfigurationProperties(prefix = "app.order.expiration")
public record OrderExpirationProperties(Duration pendingTtl, int batchSize) {

  public OrderExpirationProperties {
    if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()) {
      throw new IllegalArgumentException(
          "app.order.expiration.pending-ttl phải > 0 (vd PT30M), nhận: " + pendingTtl);
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "app.order.expiration.batch-size phải > 0, nhận: " + batchSize);
    }
  }
}
