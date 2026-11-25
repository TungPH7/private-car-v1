package com.tungph7.learnpostgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cổng khóa cho test concurrency: một giao dịch "chen ngang" giữ khóa row, đợi tới khi đủ N giao
 * dịch đứng chờ khóa đó, rồi mới nhả.
 *
 * <p>Khác {@link ConcurrencyTestSupport}: starting gate chỉ cho N thread XUẤT PHÁT cùng lúc — chúng
 * có thật sự chồng nhau ở đúng câu SQL cần đo hay không vẫn là may rủi của scheduler, thread đầu có
 * thể commit xong trước khi thread sau kịp đọc. Cổng khóa thì bảo đảm: lúc nhả, cả N giao dịch đã
 * chạy qua mọi câu đọc và đang đứng ở câu ghi đầu tiên chạm row bị khóa.
 *
 * <pre>{@code
 * try (Connection gate = LockTestSupport.holdRowLock(dataSource, "SELECT 1 ... FOR UPDATE", id)) {
 *   Future<?> a = pool.submit(...);
 *   Future<?> b = pool.submit(...);
 *   LockTestSupport.awaitLockWaiters(jdbc, 2);
 *   gate.commit(); // nhả — a và b chạy tiếp từ đúng câu đang chờ
 * }
 * }</pre>
 *
 * <p>Cổng là khóa row ({@link #holdRowLock}) khi giao dịch cần chặn đi tới một câu ghi, và là khóa
 * bảng ({@link #holdTableLock}) khi nó chỉ đọc — câu đọc không chờ khóa row nào.
 *
 * <p>Connection của cổng lấy từ pool của app (profile test: Hikari 5 connection): cổng + N giao
 * dịch + 1 connection cho {@link #awaitLockWaiters} phải vừa pool.
 */
public final class LockTestSupport {

  /** Trần chờ: quá mức này là có giao dịch không tới được câu bị khóa — fail thay vì treo. */
  public static final long WAIT_SECONDS = 20;

  private static final long POLL_MILLIS = 20;

  private LockTestSupport() {
    // utility class
  }

  /**
   * Mở một transaction riêng chạy {@code lockSql} (thường là {@code SELECT ... FOR UPDATE}) và GIỮ
   * nó mở — như một giao dịch khác đang dở dang. {@code commit()} / {@code rollback()} để nhả; đóng
   * connection (try-with-resources) cũng rollback và nhả.
   *
   * @param params tham số {@code ?} của {@code lockSql}, theo thứ tự
   */
  public static Connection holdRowLock(DataSource dataSource, String lockSql, Object... params)
      throws SQLException {
    Connection connection = dataSource.getConnection();
    try (PreparedStatement statement = connection.prepareStatement(lockSql)) {
      connection.setAutoCommit(false);
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeQuery().close();
      return connection;
    } catch (SQLException | RuntimeException ex) {
      connection.close();
      throw ex;
    }
  }

  /**
   * Như {@link #holdRowLock} nhưng giữ khóa <b>bảng</b> — {@code lockTableSql} là một câu {@code
   * LOCK TABLE ... IN ... MODE} (không trả row nên không chạy qua {@code executeQuery} được).
   *
   * <p>Dùng khi giao dịch cần chặn là một câu đọc: SELECT, kể cả phần đọc của {@code REFRESH
   * MATERIALIZED VIEW}, không chờ khóa row nào (MVCC) — chỉ {@code ACCESS EXCLUSIVE} trên bảng mới
   * làm nó đứng lại.
   */
  public static Connection holdTableLock(DataSource dataSource, String lockTableSql)
      throws SQLException {
    Connection connection = dataSource.getConnection();
    try (Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(lockTableSql);
      return connection;
    } catch (SQLException | RuntimeException ex) {
      connection.close();
      throw ex;
    }
  }

  /**
   * Đợi tới khi ít nhất {@code expected} giao dịch của database đang đứng chờ khóa.
   *
   * <p>Mỗi lần hỏi là một transaction mới (connection trả về pool thì rollback), nên {@code
   * pg_stat_activity} không bị giữ nguyên snapshot giữa các lần hỏi.
   */
  public static void awaitLockWaiters(JdbcTemplate jdbc, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    int waiting;
    while ((waiting = lockWaiters(jdbc)) < expected) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "Sau %ds chỉ có %d/%d giao dịch chờ khóa".formatted(WAIT_SECONDS, waiting, expected));
      }
      Thread.sleep(POLL_MILLIS);
    }
  }

  /** Số giao dịch của database hiện tại đang chờ khóa ({@code wait_event_type = 'Lock'}). */
  public static int lockWaiters(JdbcTemplate jdbc) {
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity"
                + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
            Integer.class);
    return waiting == null ? 0 : waiting;
  }
}
