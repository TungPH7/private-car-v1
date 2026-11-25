package com.tungph7.learnpostgres.feature.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

/**
 * {@link AdminService} trên Postgres thật — ranh giới transaction của từng thao tác, thứ mock không
 * chứng minh được:
 *
 * <ul>
 *   <li>EXPLAIN chạy trong transaction {@code READ ONLY} của Postgres (repository từ chối chạy
 *       ngoài nó) và giải thích đúng câu tìm thật.
 *   <li>ANALYZE commit thống kê mới; VACUUM chạy ngoài transaction, kể cả khi người gọi đang có
 *       transaction; {@code VACUUM FULL} đi kèm ANALYZE.
 *   <li>Bảng bận → bỏ sau {@code lock_timeout}, không treo; connection không bị Hikari đóng.
 *   <li>Benchmark: mỗi lần tìm một transaction READ ONLY riêng, như một request.
 * </ul>
 *
 * <p>ANALYZE / VACUUM chạy trên một bảng thử riêng ({@value #PROBE}, tắt autovacuum để thống kê chỉ
 * đổi khi test đổi), tạo lại trước mỗi test.
 */
@SpringBootTest
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class AdminServiceIntegrationTest extends AbstractContainerBaseTest {

  private static final String PROBE = "admin_svc_probe";
  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Autowired AdminService adminService;
  @Autowired SearchService searchService;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired DataSource dataSource;

  /** Repository đi qua bean này — spy để chen một câu ghi nhận vào trước mỗi câu SQL. */
  @MockitoSpyBean JdbcClient jdbcClient;

  @BeforeEach
  void createProbeTable() {
    dropProbeTable();
    tx.executeWithoutResult(
        status -> {
          jdbc.execute(
              "CREATE TABLE app.admin_svc_probe (id int PRIMARY KEY, note text)"
                  + " WITH (autovacuum_enabled = false)");
          jdbc.update(
              "INSERT INTO app.admin_svc_probe SELECT g, 'note ' || g FROM generate_series(1, 1000)"
                  + " g");
        });
  }

  @AfterEach
  void dropProbeTable() {
    tx.executeWithoutResult(status -> jdbc.execute("DROP TABLE IF EXISTS app.admin_svc_probe"));
  }

  /**
   * Số dòng thống kê của planner cho bảng thử — đọc ở transaction riêng: chỉ thấy thứ đã commit.
   */
  private long plannerStatistics() {
    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stats WHERE schemaname = 'app' AND tablename = ?",
            Long.class,
            PROBE);
    return rows == null ? 0 : rows;
  }

  /**
   * Trước mỗi câu SQL repository gửi qua {@link JdbcClient}, ghi lại {@code transaction_read_only}
   * và lúc bắt đầu của chính transaction đó — {@link JdbcTemplate} dùng chung connection đang gắn
   * với transaction.
   */
  private List<String> recordTransactions() {
    List<String> seen = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              seen.add(
                  jdbc.queryForObject(
                      "SELECT current_setting('transaction_read_only') || ' '"
                          + " || transaction_timestamp()",
                      String.class));
              return invocation.callRealMethod();
            })
        .when(jdbcClient)
        .sql(anyString());
    return seen;
  }

  // ================================================================
  // EXPLAIN
  // ================================================================

  @Test
  @DisplayName(
      "AC-12 (mức service): EXPLAIN đúng câu tìm thật, cùng ngưỡng, trong transaction READ ONLY —"
          + " plan có từng node")
  void explainRunsTheRealSearchStatementInReadOnly() {
    List<String> transactions = recordTransactions();
    ProductSearchCriteria iphone =
        new ProductSearchCriteria("iphone", null, null, null, null, null);

    ExplainResponse explain =
        adminService.explainProductSearch(iphone, MatchMode.RELAXED, FIRST_PAGE);

    assertThat(transactions).isNotEmpty().allMatch(line -> line.startsWith("on "));
    assertThat(explain.sql())
        .isEqualTo(searchService.pageStatement(iphone, MatchMode.RELAXED, FIRST_PAGE).sql());
    assertThat(explain.settings()).containsEntry("pg_trgm.word_similarity_threshold", "0.4");
    assertThat(explain.nodes()).first().extracting(PlanNodeResponse::nodeType).isEqualTo("Limit");
    assertThat(explain.nodes()).contains(explain.bottleneck());
    assertThat(explain.executionMs()).isPositive();
    assertThat(explain.plan().isArray()).isTrue();
  }

  // ================================================================
  // ANALYZE, VACUUM
  // ================================================================

  @Test
  @DisplayName("ANALYZE commit thống kê mới của planner; kết quả là thống kê sau khi chạy")
  void analyzeCommitsTheNewStatistics() {
    assertThat(plannerStatistics()).as("bảng mới, chưa ANALYZE").isZero();

    TableStatsResponse stats = adminService.analyze(PROBE);

    assertThat(stats.lastAnalyze()).isNotNull();
    assertThat(plannerStatistics()).isPositive();
  }

  @Test
  @DisplayName(
      "VACUUM gọi từ trong transaction của người gọi → chạy ngoài nó (transaction đó tạm gác, dùng"
          + " tiếp được)")
  void vacuumRunsOutsideTheCallersTransaction() {
    TableStatsResponse stats =
        tx.execute(
            status -> {
              TableStatsResponse result = adminService.vacuum(PROBE, false);
              assertThat(
                      jdbc.queryForObject("SELECT count(*) FROM app.admin_svc_probe", Long.class))
                  .isEqualTo(1000);
              return result;
            });

    assertThat(stats.lastVacuum()).isNotNull();
    assertThat(stats.liveRows()).isEqualTo(1000);
  }

  @Test
  @DisplayName(
      "VACUUM FULL: bảng viết lại nhỏ đi, và nhờ ANALYZE đi kèm, thống kê trả về thấy 0 row chết")
  void vacuumFullReportsTheRewrittenTable() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("UPDATE app.admin_svc_probe SET note = note || '!'");
          jdbc.execute("SELECT pg_stat_force_next_flush()");
        });
    TableStatsResponse before = adminService.tableStats(PROBE);
    assertThat(before.deadRows()).isEqualTo(1000);

    TableStatsResponse after = adminService.vacuum(PROBE, true);

    assertThat(after.deadRows()).isZero();
    assertThat(after.lastAnalyze()).isNotNull();
    assertThat(after.tableBytes()).isLessThan(before.tableBytes());
  }

  @Test
  @DisplayName(
      "bảng đang bị giữ khóa → ANALYZE / VACUUM FULL bỏ sau 2 s: 409 ADM_TABLE_LOCKED, không treo,"
          + " connection không bị Hikari đóng")
  void busyTableGivesUpAfterTheLockTimeout(CapturedOutput output) throws Exception {
    try (Connection otherTransaction =
        LockTestSupport.holdTableLock(
            dataSource, "LOCK TABLE app.admin_svc_probe IN SHARE UPDATE EXCLUSIVE MODE")) {
      Duration limit = Duration.ofSeconds(LockTestSupport.WAIT_SECONDS);
      Throwable analyze =
          assertTimeoutPreemptively(limit, () -> catchThrowable(() -> adminService.analyze(PROBE)));
      Throwable vacuumFull =
          assertTimeoutPreemptively(
              limit, () -> catchThrowable(() -> adminService.vacuum(PROBE, true)));

      for (Throwable thrown : List.of(analyze, vacuumFull)) {
        assertThat(thrown).isInstanceOf(ConflictException.class).hasMessageContaining("2 giây");
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.ADM_TABLE_LOCKED);
      }
    }
    assertThat(adminService.analyze(PROBE).lastAnalyze()).isNotNull();
    assertThat(output).doesNotContain("marked as broken");
  }

  // ================================================================
  // Benchmark
  // ================================================================

  @Test
  @DisplayName(
      "AC-13 (mức service): benchmark gọi tìm 10 + 3 lần, mỗi lần một transaction READ ONLY riêng")
  void benchmarkRunsEverySearchInItsOwnReadOnlyTransaction() {
    List<String> transactions = recordTransactions();
    ProductSearchCriteria nothing =
        new ProductSearchCriteria("xyzabc", null, null, null, null, null);

    BenchmarkResponse result = adminService.benchmarkProductSearch(nothing, FIRST_PAGE, 3);

    assertThat(result.warmUp()).isEqualTo(AdminService.BENCHMARK_WARM_UP);
    assertThat(result.iterations()).isEqualTo(3);
    assertThat(result.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(result.totalElements()).isZero();
    // mỗi lần tìm: 2 setting (ngưỡng, plan_cache_mode) + trang ở STRICT, rồi cũng vậy ở RELAXED
    assertThat(transactions).hasSize(13 * 6).allMatch(line -> line.startsWith("on "));
    assertThat(transactions.stream().distinct()).hasSize(13);
  }
}
