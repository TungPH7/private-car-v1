package com.tungph7.learnpostgres;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Chạy N task song song với một starting gate — để chúng thực sự nổ cùng lúc.
 *
 * <p>Không có starting gate thì thread đầu tiên thường chạy xong trước khi thread cuối được tạo,
 * thành ra test "concurrency" lại đang đo tuần tự và luôn xanh dù code có race.
 *
 * <p>Cách hoạt động:
 *
 * <ul>
 *   <li>Mỗi thread {@code countDown()} latch {@code ready} rồi chặn ở {@code start.await()}.
 *   <li>Thread chính đợi {@code ready} về 0 (mọi thread đã sẵn sàng) mới mở {@code start}.
 *   <li>Cả N thread rời vạch xuất phát trong cùng một khoảnh khắc.
 * </ul>
 *
 * <p>Task ném exception thì phần tử tương ứng trong list kết quả là {@code null} — các test ở F03
 * tự đếm success/fail bằng {@code AtomicInteger} bên trong lambda nên không cần đọc list.
 */
public final class ConcurrencyTestSupport {

  private static final int READY_TIMEOUT_SECONDS = 30;
  private static final int TASK_TIMEOUT_SECONDS = 60;

  private ConcurrencyTestSupport() {
    // utility class
  }

  public static <T> List<T> runConcurrently(int threads, Supplier<T> task) {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<T>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return task.get();
              }));
    }

    try {
      if (!ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "Chỉ có "
                + (threads - ready.getCount())
                + "/"
                + threads
                + " thread sẵn sàng sau "
                + READY_TIMEOUT_SECONDS
                + "s — pool quá nhỏ hoặc máy quá tải.");
      }
      start.countDown();

      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) {
        try {
          results.add(f.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (Exception ex) {
          results.add(null);
        }
      }
      return results;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    } finally {
      pool.shutdownNow();
    }
  }
}
