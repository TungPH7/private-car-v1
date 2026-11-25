package com.tungph7.learnpostgres.feature.admin.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Chạy một thao tác nhiều lần và đo từng lần bằng đồng hồ đơn điệu — như {@code ab -n N -t T}: dừng
 * khi đủ {@code iterations} lần đo hoặc khi hết {@code timeLimit}, cái nào tới trước. Luôn có ít
 * nhất một lần đo.
 *
 * <p>{@code timeLimit} tính cho cả lần khởi động lẫn lần đo, kiểm giữa hai lần chạy — một lần chạy
 * đang dở không bị cắt.
 */
final class LatencySampler {

  /**
   * @param warmUp số lần khởi động đã chạy
   * @param nanos thời gian từng lần đo, theo thứ tự chạy
   * @param lastResult kết quả của lần đo cuối
   */
  record Samples<T>(int warmUp, List<Long> nanos, T lastResult) {}

  private final LongSupplier ticker;

  /**
   * @param ticker đồng hồ đơn điệu, đơn vị nano giây — {@code System::nanoTime}
   */
  LatencySampler(LongSupplier ticker) {
    this.ticker = ticker;
  }

  <T> Samples<T> sample(
      int warmUp, int iterations, Duration timeLimit, Supplier<? extends T> operation) {
    long deadline = ticker.getAsLong() + timeLimit.toNanos();
    int warmedUp = 0;
    while (warmedUp < warmUp && ticker.getAsLong() < deadline) {
      operation.get();
      warmedUp++;
    }

    List<Long> nanos = new ArrayList<>(iterations);
    T last;
    do {
      long start = ticker.getAsLong();
      last = operation.get();
      nanos.add(ticker.getAsLong() - start);
    } while (nanos.size() < iterations && ticker.getAsLong() < deadline);
    return new Samples<>(warmedUp, List.copyOf(nanos), last);
  }
}
