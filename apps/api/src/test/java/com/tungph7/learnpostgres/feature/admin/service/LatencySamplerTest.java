package com.tungph7.learnpostgres.feature.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test cho {@link LatencySampler} với đồng hồ giả: mỗi lần chạy thao tác, đồng hồ nhích đúng
 * {@code costNanos} — số đo, số lần chạy và lúc dừng biết trước.
 */
class LatencySamplerTest {

  private final AtomicLong clock = new AtomicLong(1_000);
  private final AtomicInteger calls = new AtomicInteger();
  private final LatencySampler sampler = new LatencySampler(clock::get);

  /** Một thao tác tốn {@code costNanos}, trả số thứ tự lần gọi (1, 2, ...). */
  private int operation(long costNanos) {
    clock.addAndGet(costNanos);
    return calls.incrementAndGet();
  }

  @Test
  @DisplayName("đủ thời gian → khởi động đủ, đo đủ; mỗi lần đo đúng thời gian của thao tác")
  void runsWarmUpThenEveryIteration() {
    LatencySampler.Samples<Integer> samples =
        sampler.sample(3, 5, Duration.ofSeconds(30), () -> operation(7));

    assertThat(samples.warmUp()).isEqualTo(3);
    assertThat(samples.nanos()).containsExactly(7L, 7L, 7L, 7L, 7L);
    assertThat(calls).hasValue(8);
    assertThat(samples.lastResult()).isEqualTo(8);
  }

  @Test
  @DisplayName("hết giờ giữa chừng → dừng ở số lần đã đo, như ab -t; lần đang chạy không bị cắt")
  void stopsAtTheTimeLimit() {
    long second = Duration.ofSeconds(1).toNanos();

    LatencySampler.Samples<Integer> samples =
        sampler.sample(3, 100, Duration.ofSeconds(5), () -> operation(second));

    // 3 lần khởi động hết 3 s; đo lần 1 (3 → 4 s), lần 2 (4 → 5 s) — tới hạn, dừng
    assertThat(samples.warmUp()).isEqualTo(3);
    assertThat(samples.nanos()).containsExactly(second, second);
    assertThat(calls).hasValue(5);
  }

  @Test
  @DisplayName("khởi động đã ăn hết giờ → bỏ phần khởi động còn lại, vẫn đo một lần")
  void alwaysMeasuresAtLeastOnce() {
    long tenSeconds = Duration.ofSeconds(10).toNanos();

    LatencySampler.Samples<Integer> samples =
        sampler.sample(5, 100, Duration.ofSeconds(5), () -> operation(tenSeconds));

    assertThat(samples.warmUp()).isEqualTo(1);
    assertThat(samples.nanos()).containsExactly(tenSeconds);
    assertThat(samples.lastResult()).isEqualTo(2);
  }
}
