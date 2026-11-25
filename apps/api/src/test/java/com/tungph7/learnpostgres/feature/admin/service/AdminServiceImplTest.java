package com.tungph7.learnpostgres.feature.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.UncategorizedSQLException;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.admin.repository.AdminQueryRepository;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test cho {@link AdminServiceImpl}: đầu vào sai bị chặn (400) trước khi chạm DB, bảng không
 * có → 404, chờ khóa quá hạn → 409, EXPLAIN quá giờ → 503, benchmark đo đúng số lần.
 *
 * <p>Transaction thật (EXPLAIN trong READ ONLY, VACUUM ngoài transaction, mỗi lần đo một
 * transaction) ở {@code AdminServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
  private static final ProductSearchCriteria IPHONE =
      new ProductSearchCriteria("iphone", null, null, null, null, null);

  @Mock AdminQueryRepository repository;
  @Mock SearchService searchService;

  private AdminServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AdminServiceImpl(repository, searchService, JsonMapper.builder().build());
  }

  private static TableStatsResponse stats(String table) {
    return new TableStatsResponse(
        table, 0, "0 bytes", 0, "0 bytes", 0, "0 bytes", 0, 0, null, 0, 0, 0, null, null, null,
        null);
  }

  /** Lỗi Spring dịch từ một {@code SQLException} có SQLSTATE {@code sqlState}. */
  private static UncategorizedSQLException sqlError(String sqlState) {
    return new UncategorizedSQLException(
        "ANALYZE", "ANALYZE app.products", new SQLException("ERROR", sqlState));
  }

  private static void assertError(Throwable thrown, Class<?> type, ErrorCode code, String part) {
    assertThat(thrown).isInstanceOf(type).hasMessageContaining(part);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  private static void assertInvalid(Throwable thrown, String messagePart) {
    assertError(thrown, BadRequestException.class, ErrorCode.VALIDATION_FAILED, messagePart);
  }

  // ================================================================
  // Catalog + thống kê
  // ================================================================

  @Test
  @DisplayName("index, mức dùng: trả nguyên kết quả của repository")
  void catalogPassesThrough() {
    when(repository.indexes()).thenReturn(List.of());
    when(repository.indexUsage()).thenReturn(List.of());

    assertThat(service.indexes()).isEmpty();
    assertThat(service.indexUsage()).isEmpty();
  }

  @Test
  @DisplayName("slow queries: limit ngoài 1 – 100 → 400; hai mép → được")
  void slowQueriesLimit() {
    assertInvalid(catchThrowable(() -> service.slowQueries(0)), "limit phải từ 1 tới 100, nhận 0");
    assertInvalid(catchThrowable(() -> service.slowQueries(101)), "nhận 101");
    verifyNoInteractions(repository);

    service.slowQueries(1);
    service.slowQueries(AdminService.MAX_SLOW_QUERIES);

    verify(repository).slowQueries(1);
    verify(repository).slowQueries(100);
  }

  @Test
  @DisplayName(
      "tên bảng: thiếu / trống → 400; quá 63 byte UTF-8 → 400 dù ít hơn 63 ký tự; 63 byte → được")
  void tableNameLimits() {
    assertInvalid(catchThrowable(() -> service.tableStats(null)), "Tên bảng bắt buộc");
    assertInvalid(catchThrowable(() -> service.tableStats("  ")), "Tên bảng bắt buộc");
    assertInvalid(
        catchThrowable(() -> service.tableStats("t".repeat(64))),
        "tối đa 63 byte (giới hạn tên của Postgres), nhận 64 byte");
    // 32 ký tự "ă" = 64 byte UTF-8
    assertInvalid(catchThrowable(() -> service.tableStats("ă".repeat(32))), "nhận 64 byte");
    verifyNoInteractions(repository);

    String longest = "t".repeat(AdminService.MAX_TABLE_NAME_BYTES);
    when(repository.tableStats(longest)).thenReturn(Optional.of(stats(longest)));

    assertThat(service.tableStats(longest).tableName()).isEqualTo(longest);
  }

  @Test
  @DisplayName("thống kê bảng không có trong app → 404 ADM_TABLE_NOT_FOUND")
  void tableStatsOfUnknownTable() {
    when(repository.tableStats("no_such_table")).thenReturn(Optional.empty());

    assertError(
        catchThrowable(() -> service.tableStats("no_such_table")),
        NotFoundException.class,
        ErrorCode.ADM_TABLE_NOT_FOUND,
        "Schema app không có bảng / materialized view 'no_such_table'");
  }

  // ================================================================
  // ANALYZE, VACUUM
  // ================================================================

  @Test
  @DisplayName("ANALYZE / VACUUM: chạy với lock_timeout 2 s rồi trả thống kê của bảng sau khi chạy")
  void maintenanceReturnsTheStatsAfterwards() {
    when(repository.analyze("products", LOCK_TIMEOUT)).thenReturn(true);
    when(repository.vacuum("products", true, LOCK_TIMEOUT)).thenReturn(true);
    when(repository.tableStats("products")).thenReturn(Optional.of(stats("products")));

    assertThat(service.analyze("products").tableName()).isEqualTo("products");
    assertThat(service.vacuum("products", true).tableName()).isEqualTo("products");

    verify(repository).analyze("products", LOCK_TIMEOUT);
    verify(repository).vacuum("products", true, LOCK_TIMEOUT);
  }

  @Test
  @DisplayName("ANALYZE / VACUUM bảng không có → 404, không đọc thống kê")
  void maintenanceOfUnknownTable() {
    when(repository.analyze("no_such_table", LOCK_TIMEOUT)).thenReturn(false);
    when(repository.vacuum("no_such_table", false, LOCK_TIMEOUT)).thenReturn(false);

    assertError(
        catchThrowable(() -> service.analyze("no_such_table")),
        NotFoundException.class,
        ErrorCode.ADM_TABLE_NOT_FOUND,
        "'no_such_table'");
    assertError(
        catchThrowable(() -> service.vacuum("no_such_table", false)),
        NotFoundException.class,
        ErrorCode.ADM_TABLE_NOT_FOUND,
        "'no_such_table'");
    verify(repository).analyze("no_such_table", LOCK_TIMEOUT);
    verify(repository).vacuum("no_such_table", false, LOCK_TIMEOUT);
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName(
      "chờ khóa quá lock_timeout (SQLSTATE 55P03, Spring không phân loại) → 409 ADM_TABLE_LOCKED,"
          + " giữ lỗi gốc")
  void lockTimeoutIsAConflict() {
    UncategorizedSQLException lockTimeout = sqlError("55P03");
    when(repository.analyze("products", LOCK_TIMEOUT)).thenThrow(lockTimeout);
    when(repository.vacuum("products", true, LOCK_TIMEOUT)).thenThrow(lockTimeout);

    Throwable analyze = catchThrowable(() -> service.analyze("products"));
    Throwable vacuum = catchThrowable(() -> service.vacuum("products", true));

    assertError(
        analyze,
        ConflictException.class,
        ErrorCode.ADM_TABLE_LOCKED,
        "ANALYZE 'products' đã chờ khóa của bảng 2 giây và bỏ");
    assertError(vacuum, ConflictException.class, ErrorCode.ADM_TABLE_LOCKED, "VACUUM FULL");
    assertThat(analyze).hasCause(lockTimeout);
  }

  @Test
  @DisplayName("lỗi DB khác của ANALYZE / VACUUM → đi tiếp nguyên vẹn, không thành 409")
  void otherDatabaseErrorsPassThrough() {
    UncategorizedSQLException readOnly = sqlError("25006");
    // Lỗi Spring không bọc SQLException — vd. single() không có dòng
    EmptyResultDataAccessException noRow = new EmptyResultDataAccessException(1);
    when(repository.analyze("products", LOCK_TIMEOUT)).thenThrow(readOnly);
    when(repository.analyze("orders", LOCK_TIMEOUT)).thenThrow(noRow);
    when(repository.vacuum(anyString(), anyBoolean(), any()))
        .thenThrow(new IllegalStateException("VACUUM không chạy được trong transaction block"));

    assertThat(catchThrowable(() -> service.analyze("products"))).isSameAs(readOnly);
    assertThat(catchThrowable(() -> service.analyze("orders"))).isSameAs(noRow);
    assertThat(catchThrowable(() -> service.vacuum("products", false)))
        .isInstanceOf(IllegalStateException.class);
  }

  // ================================================================
  // EXPLAIN
  // ================================================================

  @Test
  @DisplayName(
      "EXPLAIN: câu lấy từ SearchService (đã kiểm đầu vào), timeout 10 s, JSON đọc thành tóm tắt")
  void explainUsesTheSearchStatement() {
    SqlStatement statement =
        new SqlStatement(
            "SELECT 1", Map.of("q", "iphone"), Map.of("pg_trgm.word_similarity_threshold", "0.6"));
    when(searchService.pageStatement(IPHONE, MatchMode.STRICT, FIRST_PAGE)).thenReturn(statement);
    when(repository.explainAnalyze(statement, Duration.ofSeconds(10)))
        .thenReturn(
            """
            [{"Plan": {"Node Type": "Result", "Plan Rows": 1, "Actual Total Time": 0.002,
                       "Actual Rows": 1, "Actual Loops": 1,
                       "Shared Hit Blocks": 0, "Shared Read Blocks": 0},
              "Planning Time": 0.031, "Execution Time": 0.012}]
            """);

    ExplainResponse explain = service.explainProductSearch(IPHONE, MatchMode.STRICT, FIRST_PAGE);

    assertThat(explain.sql()).isEqualTo("SELECT 1");
    assertThat(explain.settings()).containsEntry("pg_trgm.word_similarity_threshold", "0.6");
    assertThat(explain.nodes())
        .singleElement()
        .extracting(PlanNodeResponse::nodeType)
        .isEqualTo("Result");
  }

  @Test
  @DisplayName(
      "EXPLAIN quá statement_timeout (57014 → QueryTimeoutException) → 503 ADM_QUERY_TIMEOUT")
  void explainTimeoutIsServiceUnavailable() {
    SqlStatement statement = new SqlStatement("SELECT 1", Map.of(), Map.of());
    QueryTimeoutException timeout = new QueryTimeoutException("canceling statement");
    when(searchService.pageStatement(IPHONE, MatchMode.RELAXED, FIRST_PAGE)).thenReturn(statement);
    when(repository.explainAnalyze(statement, Duration.ofSeconds(10))).thenThrow(timeout);

    Throwable thrown =
        catchThrowable(() -> service.explainProductSearch(IPHONE, MatchMode.RELAXED, FIRST_PAGE));

    assertError(
        thrown,
        ServiceUnavailableException.class,
        ErrorCode.ADM_QUERY_TIMEOUT,
        "bị Postgres hủy sau 10 giây");
    assertThat(thrown).hasCause(timeout);
  }

  @Test
  @DisplayName("EXPLAIN đầu vào sai → lỗi 400 của SearchService đi thẳng ra, không chạy EXPLAIN")
  void explainRejectsWhatTheSearchRejects() {
    BadRequestException invalid =
        new BadRequestException(ErrorCode.VALIDATION_FAILED, "Từ khoá phải dài 2 – 80 ký tự");
    when(searchService.pageStatement(IPHONE, MatchMode.STRICT, FIRST_PAGE)).thenThrow(invalid);

    assertThat(
            catchThrowable(
                () -> service.explainProductSearch(IPHONE, MatchMode.STRICT, FIRST_PAGE)))
        .isSameAs(invalid);
    verifyNoInteractions(repository);
  }

  // ================================================================
  // Benchmark
  // ================================================================

  @Test
  @DisplayName("benchmark: iterations ngoài 1 – 1000 → 400, không gọi tìm lần nào")
  void benchmarkIterations() {
    assertInvalid(
        catchThrowable(() -> service.benchmarkProductSearch(IPHONE, FIRST_PAGE, 0)),
        "iterations phải từ 1 tới 1000, nhận 0");
    assertInvalid(
        catchThrowable(() -> service.benchmarkProductSearch(IPHONE, FIRST_PAGE, 1001)),
        "nhận 1001");
    verifyNoInteractions(searchService);
  }

  @Test
  @DisplayName(
      "benchmark: 10 lần khởi động + đúng số lần đo; percentile theo thứ tự; mức so tên và tổng của"
          + " lần cuối")
  void benchmarkRunsWarmUpThenMeasures() {
    ProductSearchPageResponse relaxed =
        ProductSearchPageResponse.of(new PageImpl<>(List.of(), FIRST_PAGE, 0), MatchMode.RELAXED);
    when(searchService.searchProducts(IPHONE, FIRST_PAGE)).thenReturn(relaxed);

    BenchmarkResponse result = service.benchmarkProductSearch(IPHONE, FIRST_PAGE, 7);

    verify(searchService, times(AdminService.BENCHMARK_WARM_UP + 7))
        .searchProducts(IPHONE, FIRST_PAGE);
    assertThat(result.warmUp()).isEqualTo(10);
    assertThat(result.requestedIterations()).isEqualTo(7);
    assertThat(result.iterations()).isEqualTo(7);
    assertThat(
            List.of(result.minMs(), result.p50Ms(), result.p95Ms(), result.p99Ms(), result.maxMs()))
        .isSorted();
    assertThat(result.meanMs()).isBetween(result.minMs(), result.maxMs());
    assertThat(result.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(result.totalElements()).isZero();
  }

  @Test
  @DisplayName(
      "nearest-rank: 100 lần đo → p50 / p95 / p99 là lần thứ 50 / 95 / 99 (không phải lần chậm"
          + " nhất); 1 lần đo → mọi percentile là nó")
  void nearestRank() {
    long[] hundred = new long[100];
    for (int i = 0; i < hundred.length; i++) {
      hundred[i] = i + 1;
    }

    assertThat(AdminServiceImpl.nearestRank(hundred, 50)).isEqualTo(50);
    assertThat(AdminServiceImpl.nearestRank(hundred, 95)).isEqualTo(95);
    assertThat(AdminServiceImpl.nearestRank(hundred, 99)).isEqualTo(99);
    assertThat(AdminServiceImpl.nearestRank(new long[] {7}, 99)).isEqualTo(7);
    // ⌈50 × 7 / 100⌉ = ⌈3,5⌉ = 4 → phần tử thứ 4
    assertThat(AdminServiceImpl.nearestRank(new long[] {1, 2, 3, 4, 5, 6, 7}, 50)).isEqualTo(4);
  }
}
