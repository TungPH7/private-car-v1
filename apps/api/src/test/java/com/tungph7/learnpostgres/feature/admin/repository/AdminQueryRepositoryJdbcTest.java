package com.tungph7.learnpostgres.feature.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.domain.IndexUsage;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.repository.SearchQueryRepository;

/**
 * Slice test cho {@link AdminQueryRepository} trên Postgres thật — catalog và bộ thống kê của chính
 * schema {@code app} mà Flyway dựng (V1–V18).
 *
 * <p>Mặc định mỗi test chạy trong transaction của {@code @JdbcTest}, rollback cuối test. Ngoại lệ
 * có ghi rõ: VACUUM ({@code NOT_SUPPORTED} — không chạy được trong transaction), ANALYZE ngoài
 * transaction ({@code NOT_SUPPORTED} — để thấy lớp chặn) và EXPLAIN ({@code readOnly = true} — như
 * service sẽ gọi). Bộ thống kê không theo transaction: ANALYZE / VACUUM vẫn được đếm sau rollback,
 * còn lượt quét index chỉ được đếm khi transaction quét nó kết thúc.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminQueryRepository.class, SearchQueryRepository.class})
class AdminQueryRepositoryJdbcTest extends AbstractContainerBaseTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  @Autowired AdminQueryRepository repository;
  @Autowired SearchQueryRepository searchRepository;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource dataSource;

  private Map<String, IndexUsageResponse> usageByIndex() {
    jdbc.sql("SELECT pg_stat_clear_snapshot()").query().singleRow();
    return repository.indexUsage().stream()
        .collect(Collectors.toMap(IndexUsageResponse::indexName, Function.identity()));
  }

  private long tableCounter(String column, String table) {
    jdbc.sql("SELECT pg_stat_clear_snapshot()").query().singleRow();
    return jdbc.sql("SELECT " + column + " FROM pg_stat_user_tables WHERE relid = :table::regclass")
        .param("table", "app." + table)
        .query(Long.class)
        .single();
  }

  private static String sqlState(Throwable ex) {
    return NestedExceptionUtils.getMostSpecificCause(ex) instanceof SQLException sql
        ? sql.getSQLState()
        : null;
  }

  // -----------------------------------------------------------------
  // Index catalog + mức dùng
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-10: mọi index của schema app — phương thức, định nghĩa, cờ, kích thước — kể cả index của"
          + " materialized view và EXCLUDE")
  void indexesListEveryIndexOfTheAppSchema() {
    List<IndexResponse> indexes = repository.indexes();
    Map<String, IndexResponse> byName =
        indexes.stream().collect(Collectors.toMap(IndexResponse::indexName, Function.identity()));

    assertThat(indexes)
        .hasSize(
            jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'app'")
                .query(Integer.class)
                .single())
        .allSatisfy(
            index -> {
              assertThat(index.sizeBytes()).isPositive();
              assertThat(index.size()).isNotBlank();
              assertThat(index.valid()).isTrue();
            });
    assertThat(byName.get("idx_products_name_trgm"))
        .extracting(
            IndexResponse::tableName,
            IndexResponse::method,
            IndexResponse::definition,
            IndexResponse::unique,
            IndexResponse::partial)
        .containsExactly(
            "products", "gin", "gin (search_normalize((name)::text) gin_trgm_ops)", false, false);
    assertThat(byName.get("products_pkey"))
        .extracting(IndexResponse::unique, IndexResponse::primary)
        .containsExactly(true, true);
    assertThat(byName.get("idx_products_active_created_at").definition())
        .isEqualTo("btree (created_at DESC) WHERE ((status)::text = 'ACTIVE'::text)");
    assertThat(byName.get("uq_mv_daily_revenue_day").tableName()).isEqualTo("mv_daily_revenue");
    assertThat(byName.get("ex_coupons_code_no_overlap").method()).isEqualTo("gist");
  }

  @Test
  @DisplayName(
      "AC-11: mức dùng phân loại theo số lần quét; UNIQUE / khóa chính / EXCLUDE được đánh dấu giữ"
          + " ràng buộc")
  void indexUsageClassifiesByScans() {
    Map<String, IndexUsageResponse> usage = usageByIndex();

    assertThat(usage.values())
        .allSatisfy(
            index ->
                assertThat(index.usage())
                    .isEqualTo(
                        index.scans() == 0
                            ? IndexUsage.UNUSED
                            : index.scans() < 100 ? IndexUsage.RARELY_USED : IndexUsage.ACTIVE));
    assertThat(usage.get("products_pkey").enforcesConstraint()).isTrue();
    assertThat(usage.get("uq_customers_phone_active").enforcesConstraint()).isTrue();
    assertThat(usage.get("ex_coupons_code_no_overlap").enforcesConstraint()).isTrue();
    assertThat(usage.get("idx_products_name_trgm").enforcesConstraint()).isFalse();
  }

  @Test
  @DisplayName(
      "Lượt quét index được đếm khi transaction quét nó kết thúc — trong transaction đang mở thì"
          + " chưa thấy")
  void indexScansCountOnceTheScanningTransactionEnds() {
    String scanTrigramIndex =
        "SELECT count(*) FROM app.products WHERE app.search_normalize(name) LIKE '%zzz%'";
    long before = usageByIndex().get("idx_products_name_trgm").scans();

    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    jdbc.sql(scanTrigramIndex).query(Long.class).single();
    assertThat(usageByIndex().get("idx_products_name_trgm").scans()).isEqualTo(before);

    TransactionTemplate separate = new TransactionTemplate(transactionManager);
    separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    separate.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL enable_seqscan = off").update();
          jdbc.sql(scanTrigramIndex).query(Long.class).single();
          jdbc.sql("SELECT pg_stat_force_next_flush()").query().singleRow();
        });

    IndexUsageResponse after = usageByIndex().get("idx_products_name_trgm");
    assertThat(after.scans()).isEqualTo(before + 1);
    assertThat(after.lastScanAt()).isNotNull();
  }

  // -----------------------------------------------------------------
  // pg_stat_statements
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "Câu chậm: của database + role hiện tại, đã chuẩn hoá ($1), theo tổng thời gian; không có"
          + " câu đọc pg_stat_statements")
  void slowQueriesComeFromThisDatabaseAndRole() {
    // Câu đánh dấu phải có cấu trúc riêng (bảng + điều kiện): alias không tính vào queryid, nên
    // "SELECT 4242 AS marker" gộp chung mục với mọi "SELECT <hằng số>" chạy trước nó — và mục giữ
    // text của câu đến trước (bài 03 §7.2)
    jdbc.sql(
            "SELECT count(*) AS f08_slow_query_marker FROM app._app_migration_log"
                + " WHERE notes = 'f08 slow query marker'")
        .query(Long.class)
        .single();

    List<SlowQueryResponse> top = repository.slowQueries(500);

    assertThat(top)
        .anySatisfy(
            query -> {
              assertThat(query.query())
                  .isEqualTo(
                      "SELECT count(*) AS f08_slow_query_marker FROM app._app_migration_log"
                          + " WHERE notes = $1");
              assertThat(query.calls()).isPositive();
            })
        .noneMatch(query -> query.query().contains("pg_stat_statements"))
        .allSatisfy(
            query -> {
              assertThat(query.meanMs()).isLessThanOrEqualTo(query.maxMs());
              assertThat(query.pctOfTotal()).isBetween(BigDecimal.ZERO, HUNDRED);
            })
        .extracting(SlowQueryResponse::totalMs)
        .isSortedAccordingTo((x, y) -> y.compareTo(x));
    assertThat(repository.slowQueries(1)).hasSize(1);
  }

  // -----------------------------------------------------------------
  // Thống kê bảng, ANALYZE, VACUUM
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "Thống kê bảng / materialized view của app; tên không có thật, ngoài app, hay chứa SQL →"
          + " rỗng")
  void tableStatsOfAppRelationsOnly() {
    TableStatsResponse products = repository.tableStats("products").orElseThrow();

    assertThat(products.tableName()).isEqualTo("products");
    assertThat(products.indexesBytes()).isPositive();
    assertThat(products.totalBytes())
        .isGreaterThanOrEqualTo(products.tableBytes() + products.indexesBytes());
    assertThat(products.totalSize()).isNotBlank();
    assertThat(repository.tableStats("mv_daily_revenue")).isPresent();
    assertThat(repository.tableStats("no_such_table")).isEmpty();
    assertThat(repository.tableStats("pg_class")).isEmpty();
    assertThat(repository.tableStats("products; DROP TABLE app.products")).isEmpty();
  }

  @Test
  @DisplayName("ANALYZE chỉ tới được bảng có thật của app; được đếm ngay, kể cả khi rollback")
  void analyzeReachesOnlyAppRelations() {
    long before = tableCounter("analyze_count", "products");

    assertThat(repository.analyze("products", TIMEOUT)).isTrue();
    assertThat(tableCounter("analyze_count", "products")).isEqualTo(before + 1);
    assertThat(repository.analyze("no_such_table", TIMEOUT)).isFalse();
    assertThat(repository.analyze("products; DROP TABLE app.products", TIMEOUT)).isFalse();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "ANALYZE ngoài transaction → IllegalStateException trước khi gửi gì tới Postgres — Hikari sẽ"
          + " rollback thống kê mới")
  void analyzeNeedsATransaction() {
    long before = tableCounter("analyze_count", "products");

    assertThatThrownBy(() -> repository.analyze("products", TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    assertThat(tableCounter("analyze_count", "products")).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "ANALYZE chờ khóa quá lock_timeout → Postgres hủy câu (55P03 — Spring không phân loại:"
          + " UncategorizedSQLException)")
  void analyzeGivesUpOnALockedTable() throws SQLException {
    // Lưới an toàn: thiếu lock_timeout thì ANALYZE dừng ở đây (57014) thay vì chờ mãi
    jdbc.sql("SET LOCAL statement_timeout = '5s'").update();
    try (Connection other =
        LockTestSupport.holdTableLock(
            dataSource, "LOCK TABLE app._app_migration_log IN SHARE UPDATE EXCLUSIVE MODE")) {
      assertThatThrownBy(() -> repository.analyze("_app_migration_log", Duration.ofMillis(200)))
          .isInstanceOf(UncategorizedSQLException.class)
          .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("55P03"));
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "VACUUM chạy ngoài transaction (connection bật auto-commit riêng); VACUUM FULL viết lại bảng"
          + " — file dữ liệu mới")
  void vacuumRunsOnAnAutoCommitConnection() {
    long vacuums = tableCounter("vacuum_count", "products");
    assertThat(repository.vacuum("products", false, TIMEOUT)).isTrue();
    assertThat(tableCounter("vacuum_count", "products")).isEqualTo(vacuums + 1);

    String filenode = "SELECT pg_relation_filenode('app._app_migration_log')";
    long fileBefore = jdbc.sql(filenode).query(Long.class).single();
    assertThat(repository.vacuum("_app_migration_log", true, TIMEOUT)).isTrue();
    assertThat(jdbc.sql(filenode).query(Long.class).single()).isNotEqualTo(fileBefore);

    assertThat(repository.vacuum("no_such_table", false, TIMEOUT)).isFalse();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "VACUUM FULL đi kèm ANALYZE: bộ thống kê thấy bảng đã viết lại — n_dead_tup về 0,"
          + " analyze_count + 1")
  void vacuumFullAnalyzesTheRewrittenTable() {
    TransactionTemplate committed = new TransactionTemplate(transactionManager);
    committed.executeWithoutResult(
        status -> {
          jdbc.sql("UPDATE app._app_migration_log SET notes = notes").update();
          jdbc.sql("SELECT pg_stat_force_next_flush()").query().singleRow();
        });
    assertThat(tableCounter("n_dead_tup", "_app_migration_log")).isPositive();
    long analyzes = tableCounter("analyze_count", "_app_migration_log");

    assertThat(repository.vacuum("_app_migration_log", true, TIMEOUT)).isTrue();

    assertThat(tableCounter("n_dead_tup", "_app_migration_log")).isZero();
    assertThat(tableCounter("analyze_count", "_app_migration_log")).isEqualTo(analyzes + 1);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "VACUUM FULL chờ khóa quá lock_timeout → bỏ (55P03); connection được RESET lock_timeout"
          + " trước khi về pool")
  void vacuumGivesUpOnALockedTableAndResetsTheLockTimeout() throws SQLException {
    try (Connection other =
            LockTestSupport.holdTableLock(
                dataSource, "LOCK TABLE app._app_migration_log IN ACCESS SHARE MODE");
        Connection own = dataSource.getConnection()) {
      // Repository trên đúng một connection, để đọc lock_timeout của chính connection đó sau đó
      SingleConnectionDataSource single = new SingleConnectionDataSource(own, true);
      AdminQueryRepository onOwnConnection =
          new AdminQueryRepository(JdbcClient.create(single), new JdbcTemplate(single));

      assertTimeoutPreemptively(
          Duration.ofSeconds(LockTestSupport.WAIT_SECONDS),
          () ->
              assertThatThrownBy(
                      () ->
                          onOwnConnection.vacuum(
                              "_app_migration_log", true, Duration.ofMillis(200)))
                  .isInstanceOf(UncategorizedSQLException.class)
                  .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("55P03")));
      assertThat(JdbcClient.create(single).sql("SHOW lock_timeout").query(String.class).single())
          .isEqualTo("0");
    }
  }

  @Test
  @DisplayName("VACUUM trong transaction → IllegalStateException trước khi gửi gì tới Postgres")
  void vacuumRefusesToRunInsideATransaction() {
    assertThatThrownBy(() -> repository.vacuum("products", false, TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
  }

  // -----------------------------------------------------------------
  // EXPLAIN ANALYZE
  // -----------------------------------------------------------------

  @Test
  @DisplayName("EXPLAIN ANALYZE ngoài transaction READ ONLY → IllegalStateException")
  void explainNeedsAReadOnlyTransaction() {
    SqlStatement select = new SqlStatement("SELECT 1", Map.of(), Map.of());

    assertThatThrownBy(() -> repository.explainAnalyze(select, TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ ONLY");
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName(
      "AC-12 (mức repository): JSON plan của đúng câu tìm sản phẩm, cùng ngưỡng pg_trgm và"
          + " statement_timeout của transaction")
  void explainReturnsTheJsonPlanOfTheStatement() {
    assertThat(jdbc.sql("SHOW transaction_read_only").query(String.class).single()).isEqualTo("on");
    // Vài row thì planner quét cả bảng / đi hết index created_at — chỉ chừa bitmap scan
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    jdbc.sql("SET LOCAL enable_indexscan = off").update();
    SqlStatement search =
        searchRepository.pageStatement(
            new ProductSearchCriteria("iphone", null, null, null, null, null),
            MatchMode.RELAXED,
            PageRequest.of(0, 20));

    String plan = repository.explainAnalyze(search, TIMEOUT);

    assertThat(plan)
        .startsWith("[")
        .contains("\"Node Type\": \"Limit\"")
        .contains("\"Index Name\": \"idx_products_name_trgm\"")
        .contains("\"Execution Time\"");
    assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("10s");
    assertThat(jdbc.sql("SHOW pg_trgm.word_similarity_threshold").query(String.class).single())
        .isEqualTo("0.4");
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName(
      "Câu chạy quá timeout bị Postgres hủy — 57014, Spring dịch thành QueryTimeoutException")
  void explainStopsAtTheTimeout() {
    SqlStatement slow = new SqlStatement("SELECT pg_sleep(1)", Map.of(), Map.of());

    assertThatThrownBy(() -> repository.explainAnalyze(slow, Duration.ofMillis(100)))
        .isInstanceOf(QueryTimeoutException.class)
        .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("57014"));
  }

  @Test
  @Transactional(readOnly = true)
  @DisplayName(
      "READ ONLY chặn câu ghi: EXPLAIN ANALYZE một DELETE → 25006 (UncategorizedSQLException),"
          + " không xoá gì")
  void readOnlyStopsWrites() {
    SqlStatement delete =
        new SqlStatement("DELETE FROM app.products WHERE false", Map.of(), Map.of());

    assertThatThrownBy(() -> repository.explainAnalyze(delete, TIMEOUT))
        .isInstanceOf(UncategorizedSQLException.class)
        .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("25006"));
  }
}
