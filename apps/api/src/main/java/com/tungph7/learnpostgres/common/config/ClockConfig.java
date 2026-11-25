package com.tungph7.learnpostgres.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nguồn thời gian của app — inject {@link Clock} thay vì gọi {@code Instant.now()} / {@code
 * YearMonth.now()} trực tiếp, để unit test cố định được "bây giờ" ({@code Clock.fixed}) và kiểm
 * được các mốc khó như giao tháng.
 *
 * <p>Clock UTC: thời điểm là instant, không mang múi giờ. Code nào cần ngày/tháng theo lịch thì tự
 * chọn zone tường minh (vd business timezone ở {@link BusinessProperties}).
 */
@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
