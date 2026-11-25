package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;

/**
 * Unit test cho {@link OrderNumberGenerator} — thời gian cố định bằng {@link Clock#fixed}, nên mốc
 * giao tháng kiểm được chính xác thay vì phụ thuộc lúc chạy test.
 */
@ExtendWith(MockitoExtension.class)
class OrderNumberGeneratorTest {

  /** 30/9 17:30 UTC = 1/10 00:30 giờ Việt Nam: cùng một instant, khác tháng theo zone. */
  private static final Instant MONTH_BOUNDARY = Instant.parse("2026-09-30T17:30:00Z");

  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  @Mock OrderRepository orderRepository;

  private OrderNumberGenerator generator(Instant now, ZoneId businessZone) {
    return new OrderNumberGenerator(
        orderRepository, Clock.fixed(now, ZoneOffset.UTC), new BusinessProperties(businessZone));
  }

  @Test
  @DisplayName("format ORD-YYYYMM-NNNNNN, sequence pad đủ 6 chữ số")
  void formatsWithPaddedSequence() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(42L);

    assertThat(generator(Instant.parse("2026-09-15T03:00:00Z"), VIETNAM).next())
        .isEqualTo("ORD-202609-000042");
  }

  @Test
  @DisplayName("tháng theo business timezone — 17:30 UTC ngày 30/9 đã là tháng 10 ở Việt Nam")
  void monthFollowsBusinessTimezone() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(42L);

    assertThat(generator(MONTH_BOUNDARY, VIETNAM).next()).isEqualTo("ORD-202610-000042");
  }

  @Test
  @DisplayName("cùng instant, business timezone khác → tháng khác: tháng do config quyết định")
  void monthChangesWithConfiguredZone() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(42L);

    assertThat(generator(MONTH_BOUNDARY, ZoneOffset.UTC).next()).isEqualTo("ORD-202609-000042");
  }

  @Test
  @DisplayName("sequence 7 chữ số KHÔNG bị cắt còn 6 (lỗi của lpad)")
  void longSequenceIsNotTruncated() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(1_234_567L);

    assertThat(generator(MONTH_BOUNDARY, VIETNAM).next()).isEqualTo("ORD-202610-1234567");
  }

  @Test
  @DisplayName("sequence lớn nhất của BIGINT vẫn vừa order_number VARCHAR(32)")
  void maxSequenceFitsColumn() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(Long.MAX_VALUE);

    String number = generator(MONTH_BOUNDARY, VIETNAM).next();

    assertThat(number).isEqualTo("ORD-202610-" + Long.MAX_VALUE).hasSizeLessThanOrEqualTo(32);
  }

  /**
   * Locale mặc định của JVM có thể đổi chữ số của {@code %d} (vd {@code ar-EG} ra chữ số Ả Rập).
   * Generator dùng {@code Locale.ROOT} nên số đơn luôn là ASCII.
   */
  @Test
  @DisplayName("locale mặc định của JVM không làm đổi chữ số trong số đơn")
  void independentOfDefaultLocale() {
    when(orderRepository.nextOrderNumberSequence()).thenReturn(42L);
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"));

      assertThat(generator(MONTH_BOUNDARY, VIETNAM).next()).isEqualTo("ORD-202610-000042");
    } finally {
      Locale.setDefault(original);
    }
  }
}
