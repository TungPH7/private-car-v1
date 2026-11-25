package com.tungph7.learnpostgres.feature.admin.repository;

import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.tungph7.learnpostgres.common.jdbc.RecordRowMapper;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;

/**
 * Đọc catalog và bộ thống kê của Postgres cho schema {@code app}, cùng ba thao tác bảo trì:
 * ANALYZE, VACUUM, EXPLAIN ANALYZE (F08).
 *
 * <p>Ghép theo OID ({@code pg_index.indexrelid}, {@code pg_stat_user_tables.relid}), không theo
 * tên: tên index / bảng chỉ duy nhất trong một schema. Số đếm của bộ thống kê cộng dồn từ lần reset
 * gần nhất, và chỉ thấy phần của những transaction đã kết thúc.
 *
 * <p>Tên bảng của client không bao giờ nối vào SQL: {@link #qualifiedName} tra catalog bằng tham số
 * và trả tên đã quote ({@code format('%I.%I')}) — chỉ bảng có thật trong {@code app} mới tới được
 * ANALYZE / VACUUM.
 */
@Repository
public class AdminQueryRepository {

  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;

  public AdminQueryRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Mọi index của schema {@code app}, theo bảng rồi tên index. */
  public List<IndexResponse> indexes() {
    return jdbc.sql(
            """
            SELECT t.relname                                           AS table_name,
                   i.relname                                           AS index_name,
                   am.amname                                           AS method,
                   regexp_replace(pg_get_indexdef(x.indexrelid), '^.*? USING ', '')
                                                                       AS definition,
                   x.indisunique                                       AS unique,
                   x.indisprimary                                      AS primary,
                   x.indisvalid                                        AS valid,
                   x.indpred IS NOT NULL                               AS partial,
                   pg_relation_size(x.indexrelid)                      AS size_bytes,
                   pg_size_pretty(pg_relation_size(x.indexrelid))      AS size
            FROM pg_index x
            JOIN pg_class i ON i.oid = x.indexrelid
            JOIN pg_class t ON t.oid = x.indrelid
            JOIN pg_am am   ON am.oid = i.relam
            WHERE t.relnamespace = 'app'::regnamespace
            ORDER BY t.relname, i.relname
            """)
        .query(RecordRowMapper.of(IndexResponse.class))
        .list();
  }

  /**
   * Mức dùng của mọi index của schema {@code app} — chưa được quét trước, rồi index lớn trước: đầu
   * danh sách là ứng viên để bỏ (trừ index {@code enforcesConstraint}).
   */
  public List<IndexUsageResponse> indexUsage() {
    return jdbc.sql(
            """
            SELECT s.relname                                      AS table_name,
                   s.indexrelname                                 AS index_name,
                   s.idx_scan                                     AS scans,
                   s.last_idx_scan                                AS last_scan_at,
                   s.idx_tup_read                                 AS tuples_read,
                   s.idx_tup_fetch                                AS tuples_fetched,
                   pg_relation_size(s.indexrelid)                 AS size_bytes,
                   pg_size_pretty(pg_relation_size(s.indexrelid)) AS size,
                   x.indisunique OR x.indisexclusion              AS enforces_constraint,
                   CASE WHEN s.idx_scan = 0   THEN 'UNUSED'
                        WHEN s.idx_scan < 100 THEN 'RARELY_USED'
                        ELSE 'ACTIVE'
                   END                                            AS usage
            FROM pg_stat_user_indexes s
            JOIN pg_index x ON x.indexrelid = s.indexrelid
            WHERE s.schemaname = 'app'
            ORDER BY s.idx_scan, size_bytes DESC, s.relname, s.indexrelname
            """)
        .query(RecordRowMapper.of(IndexUsageResponse.class))
        .list();
  }

  /**
   * {@code limit} câu tốn nhiều thời gian thực thi nhất (tổng, không phải trung bình) của database
   * hiện tại, do chính role của app chạy.
   *
   * <p>{@code pg_stat_statements} chung cho cả cluster: lọc {@code dbid}. App role chỉ đọc được
   * text câu của chính nó — row của role khác hiện {@code <insufficient privilege>} — nên lọc
   * {@code userid}. Bỏ câu đọc chính view này.
   */
  public List<SlowQueryResponse> slowQueries(int limit) {
    return jdbc.sql(
            """
            SELECT s.queryid                              AS query_id,
                   s.query,
                   s.calls,
                   round(s.total_exec_time::numeric, 2)   AS total_ms,
                   round(s.mean_exec_time::numeric, 2)    AS mean_ms,
                   round(s.max_exec_time::numeric, 2)     AS max_ms,
                   round((100 * s.total_exec_time
                          / NULLIF(sum(s.total_exec_time) OVER (), 0))::numeric, 2)
                                                          AS pct_of_total,
                   s.rows,
                   round(100.0 * s.shared_blks_hit
                         / NULLIF(s.shared_blks_hit + s.shared_blks_read, 0), 2)
                                                          AS cache_hit_pct
            FROM pg_stat_statements s
            WHERE s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
              AND s.userid = current_user::regrole
              AND s.query NOT LIKE '%pg_stat_statements%'
            ORDER BY s.total_exec_time DESC, s.queryid
            LIMIT :limit
            """)
        .param("limit", limit)
        .query(RecordRowMapper.of(SlowQueryResponse.class))
        .list();
  }

  /** Kích thước và thống kê của bảng / materialized view {@code tableName} trong {@code app}. */
  public Optional<TableStatsResponse> tableStats(String tableName) {
    return jdbc.sql(
            """
            SELECT c.relname                                         AS table_name,
                   pg_total_relation_size(c.oid)                     AS total_bytes,
                   pg_size_pretty(pg_total_relation_size(c.oid))     AS total_size,
                   pg_relation_size(c.oid)                           AS table_bytes,
                   pg_size_pretty(pg_relation_size(c.oid))           AS table_size,
                   pg_indexes_size(c.oid)                            AS indexes_bytes,
                   pg_size_pretty(pg_indexes_size(c.oid))            AS indexes_size,
                   s.n_live_tup                                      AS live_rows,
                   s.n_dead_tup                                      AS dead_rows,
                   round(100.0 * s.n_dead_tup
                         / NULLIF(s.n_live_tup + s.n_dead_tup, 0), 2) AS dead_pct,
                   s.n_mod_since_analyze                             AS modified_since_analyze,
                   s.seq_scan                                        AS seq_scans,
                   COALESCE(s.idx_scan, 0)                           AS index_scans,
                   s.last_vacuum,
                   s.last_autovacuum,
                   s.last_analyze,
                   s.last_autoanalyze
            FROM pg_class c
            JOIN pg_stat_user_tables s ON s.relid = c.oid
            WHERE c.relnamespace = 'app'::regnamespace
              AND c.relname = :name
              AND c.relkind IN ('r', 'p', 'm')
            """)
        .param("name", tableName)
        .query(RecordRowMapper.of(TableStatsResponse.class))
        .optional();
  }

  /**
   * {@code ANALYZE} bảng — cập nhật thống kê phân bố dữ liệu cho planner. Thống kê mới có hiệu lực
   * khi transaction commit.
   *
   * <p>Phải gọi trong transaction: ngoài transaction, connection về pool với việc chưa commit và
   * Hikari ({@code auto-commit: false}) rollback — thống kê của planner mất, trong khi bộ đếm
   * {@code last_analyze} vẫn ghi là đã chạy. Chờ khóa của bảng quá {@code lockTimeout} thì Postgres
   * hủy câu ({@code 55P03}).
   *
   * @return {@code false} khi {@code app} không có bảng / materialized view tên đó
   * @throws IllegalStateException không có transaction
   */
  public boolean analyze(String tableName, Duration lockTimeout) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "ANALYZE phải chạy trong transaction — ngoài transaction, thống kê mới bị rollback");
    }
    Optional<String> table = qualifiedName(tableName);
    table.ifPresent(
        name -> {
          jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
              .param("timeout", Long.toString(lockTimeout.toMillis()))
              .query(String.class)
              .single();
          jdbc.sql("ANALYZE " + name).update();
        });
    return table.isPresent();
  }

  /**
   * {@code VACUUM} bảng, hoặc {@code VACUUM (FULL, ANALYZE)}. {@code VACUUM} không chạy được trong
   * transaction block, mà connection của app tắt auto-commit ({@code application.yml}) — pgjdbc tự
   * mở transaction trước câu đầu tiên. Vì vậy chạy trên một connection bật auto-commit, trả lại như
   * cũ trước khi về pool.
   *
   * <p>{@code VACUUM FULL} viết lại cả bảng và giữ khóa {@code ACCESS EXCLUSIVE} suốt lúc chạy: mọi
   * câu đọc / ghi bảng đó đứng chờ. Nó không báo gì cho bộ thống kê — {@code n_dead_tup}, {@code
   * last_vacuum} giữ số cũ — nên đi kèm {@code ANALYZE}. Chờ khóa quá {@code lockTimeout} thì
   * Postgres hủy câu ({@code 55P03}): {@code VACUUM FULL} đứng trong hàng đợi khóa là chặn luôn mọi
   * câu đến sau trên bảng đó. {@code lock_timeout} đặt cho session rồi {@code RESET} trước khi trả
   * connection.
   *
   * @return {@code false} khi {@code app} không có bảng / materialized view tên đó
   * @throws IllegalStateException gọi trong một transaction
   */
  public boolean vacuum(String tableName, boolean full, Duration lockTimeout) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "VACUUM không chạy được trong transaction block — gọi ngoài @Transactional");
    }
    Optional<String> table = qualifiedName(tableName);
    table.ifPresent(
        name ->
            jdbcTemplate.execute(
                (ConnectionCallback<Void>)
                    con -> {
                      boolean autoCommit = con.getAutoCommit();
                      con.setAutoCommit(true);
                      try (Statement statement = con.createStatement()) {
                        statement.execute("SET lock_timeout = " + lockTimeout.toMillis());
                        try {
                          statement.execute((full ? "VACUUM (FULL, ANALYZE) " : "VACUUM ") + name);
                        } finally {
                          statement.execute("RESET lock_timeout");
                        }
                      } finally {
                        con.setAutoCommit(autoCommit);
                      }
                      return null;
                    }));
    return table.isPresent();
  }

  /**
   * {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} một câu do code dựng — không bao giờ là SQL của
   * client. Kết quả là JSON thô của Postgres (một mảng, một phần tử).
   *
   * <p>{@code ANALYZE} <b>chạy thật</b> câu được giải thích. Hai lớp chặn: phải gọi trong
   * transaction {@code READ ONLY} — câu ghi bị Postgres từ chối ({@code 25006}) thay vì chạy thật —
   * và {@code statement_timeout} = {@code timeout} cho riêng transaction đó.
   *
   * @throws IllegalStateException transaction hiện tại không phải {@code READ ONLY}, hoặc không có
   *     transaction
   */
  public String explainAnalyze(SqlStatement statement, Duration timeout) {
    String readOnly =
        jdbc.sql("SELECT current_setting('transaction_read_only')").query(String.class).single();
    if (!"on".equals(readOnly)) {
      throw new IllegalStateException(
          "EXPLAIN ANALYZE chạy thật câu được giải thích — chỉ gọi trong transaction READ ONLY");
    }
    jdbc.sql("SELECT set_config('statement_timeout', :timeout, true)")
        .param("timeout", Long.toString(timeout.toMillis()))
        .query(String.class)
        .single();
    statement.applySettings(jdbc);
    return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement.sql())
        .params(statement.params())
        .query(String.class)
        .single();
  }

  /**
   * Tên đầy đủ, đã quote, của bảng / materialized view {@code tableName} trong {@code app} — lấy từ
   * catalog, nên chỉ có thể là tên của một relation có thật.
   */
  private Optional<String> qualifiedName(String tableName) {
    return jdbc.sql(
            """
            SELECT format('%I.%I', n.nspname, c.relname)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'app'
              AND c.relname = :name
              AND c.relkind IN ('r', 'p', 'm')
            """)
        .param("name", tableName)
        .query(String.class)
        .optional();
  }
}
