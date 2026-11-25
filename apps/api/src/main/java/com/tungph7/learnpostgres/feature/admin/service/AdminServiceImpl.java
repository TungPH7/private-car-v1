package com.tungph7.learnpostgres.feature.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.admin.repository.AdminQueryRepository;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import tools.jackson.databind.ObjectMapper;

/**
 * Cài đặt {@link AdminService}.
 *
 * <p><b>Transaction</b> — mỗi thao tác một kiểu, khai trên từng method:
 *
 * <ul>
 *   <li>Đọc catalog / thống kê, EXPLAIN: {@code readOnly = true} của class — transaction {@code
 *       READ ONLY} của Postgres. Với EXPLAIN đó là điều kiện bắt buộc: repository từ chối chạy
 *       {@code EXPLAIN ANALYZE} ngoài nó, vì {@code ANALYZE} chạy thật câu được giải thích.
 *   <li>ANALYZE: transaction đọc-ghi, commit khi xong.
 *   <li>VACUUM: {@code NOT_SUPPORTED} — không chạy được trong transaction block; transaction của
 *       người gọi (nếu có) được tạm gác.
 *   <li>Benchmark: {@code NOT_SUPPORTED} — mỗi lần đo là một lời gọi {@link
 *       SearchService#searchProducts} với transaction riêng của nó, như một request thật.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AdminServiceImpl implements AdminService {

  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(LOCK_TIMEOUT_SECONDS);
  private static final Duration EXPLAIN_TIMEOUT = Duration.ofSeconds(EXPLAIN_TIMEOUT_SECONDS);
  private static final Duration BENCHMARK_TIME_LIMIT =
      Duration.ofSeconds(BENCHMARK_TIME_LIMIT_SECONDS);

  /** SQLSTATE {@code lock_not_available} — chờ khóa quá {@code lock_timeout}. */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private static final int P50 = 50;
  private static final int P95 = 95;
  private static final int P99 = 99;

  /** Nano giây → mili giây: dời dấu phẩy sang trái 6 chữ số. */
  private static final int NANOS_TO_MILLIS_SCALE = 6;

  private static final int MILLIS_SCALE = 2;

  private final AdminQueryRepository repository;
  private final SearchService searchService;
  private final ObjectMapper objectMapper;
  private final LatencySampler sampler = new LatencySampler(System::nanoTime);

  public AdminServiceImpl(
      AdminQueryRepository repository, SearchService searchService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.searchService = searchService;
    this.objectMapper = objectMapper;
  }

  // ================================================================
  // Catalog + thống kê
  // ================================================================

  @Override
  public List<IndexResponse> indexes() {
    return repository.indexes();
  }

  @Override
  public List<IndexUsageResponse> indexUsage() {
    return repository.indexUsage();
  }

  @Override
  public List<SlowQueryResponse> slowQueries(int limit) {
    if (limit < 1 || limit > MAX_SLOW_QUERIES) {
      throw invalid("limit phải từ 1 tới %d, nhận %d.", MAX_SLOW_QUERIES, limit);
    }
    return repository.slowQueries(limit);
  }

  @Override
  public TableStatsResponse tableStats(String tableName) {
    requireTableName(tableName);
    return statsOf(tableName);
  }

  // ================================================================
  // Bảo trì
  // ================================================================

  @Override
  @Transactional
  public TableStatsResponse analyze(String tableName) {
    requireTableName(tableName);
    maintain("ANALYZE", tableName, () -> repository.analyze(tableName, LOCK_TIMEOUT));
    return statsOf(tableName);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public TableStatsResponse vacuum(String tableName, boolean full) {
    requireTableName(tableName);
    maintain(
        full ? "VACUUM FULL" : "VACUUM",
        tableName,
        () -> repository.vacuum(tableName, full, LOCK_TIMEOUT));
    return statsOf(tableName);
  }

  /**
   * Chạy một lệnh bảo trì của repository: {@code false} (không có bảng) → 404; chờ khóa quá {@code
   * lock_timeout} → 409.
   *
   * <p>Nhận ra lần chờ khóa quá hạn bằng SQLSTATE {@code 55P03} của lỗi gốc, không bằng lớp
   * exception: {@code JdbcTemplate} chỉ dùng bảng mã lỗi theo DB ({@code sql-error-codes.xml} — nơi
   * PostgreSQL có {@code 55P03 → CannotAcquireLockException}) khi app tự đặt file đó ở gốc
   * classpath; không thì dịch theo lớp SQLSTATE, và lớp {@code 55} không thuộc nhóm nào → {@code
   * UncategorizedSQLException}.
   *
   * <p>Bắt ngay trong method {@code @Transactional} của ANALYZE vẫn an toàn: repository không có
   * transaction riêng nên không có gì bị đánh dấu rollback-only, và {@code 55P03} không nằm trong
   * các mã HikariCP coi là connection hỏng — exception mới bay qua proxy, transaction rollback như
   * mọi lỗi khác.
   */
  private static void maintain(String command, String tableName, BooleanSupplier operation) {
    boolean found;
    try {
      found = operation.getAsBoolean();
    } catch (DataAccessException ex) {
      if (ex.getMostSpecificCause() instanceof SQLException sql
          && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
        throw new ConflictException(
            ErrorCode.ADM_TABLE_LOCKED,
            ("%s '%s' đã chờ khóa của bảng %d giây và bỏ — transaction khác đang giữ bảng, thử"
                    + " lại sau.")
                .formatted(command, tableName, LOCK_TIMEOUT_SECONDS),
            ex);
      }
      throw ex;
    }
    if (!found) {
      throw tableNotFound(tableName);
    }
  }

  // ================================================================
  // EXPLAIN + benchmark — chỉ câu được phép
  // ================================================================

  /**
   * Câu lấy từ {@link SearchService#pageStatement} — đúng câu tìm thật gửi đi, sau đúng các bước
   * kiểm đầu vào của tìm thật. Bắt {@code 57014} như {@link #analyze} bắt {@code 55P03}.
   */
  @Override
  public ExplainResponse explainProductSearch(
      ProductSearchCriteria criteria, MatchMode matchMode, Pageable pageable) {
    SqlStatement statement = searchService.pageStatement(criteria, matchMode, pageable);
    String plan;
    try {
      plan = repository.explainAnalyze(statement, EXPLAIN_TIMEOUT);
    } catch (QueryTimeoutException ex) {
      throw new ServiceUnavailableException(
          ErrorCode.ADM_QUERY_TIMEOUT,
          ("EXPLAIN ANALYZE bị Postgres hủy sau %d giây (statement_timeout) — câu chạy quá lâu,"
                  + " hoặc phải chờ khóa của bảng quá lâu (transaction khác đang giữ bảng).")
              .formatted(EXPLAIN_TIMEOUT_SECONDS),
          ex);
    }
    return ExplainPlanReader.read(statement, objectMapper.readTree(plan));
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BenchmarkResponse benchmarkProductSearch(
      ProductSearchCriteria criteria, Pageable pageable, int iterations) {
    if (iterations < 1 || iterations > MAX_BENCHMARK_ITERATIONS) {
      throw invalid("iterations phải từ 1 tới %d, nhận %d.", MAX_BENCHMARK_ITERATIONS, iterations);
    }
    LatencySampler.Samples<ProductSearchPageResponse> samples =
        sampler.sample(
            BENCHMARK_WARM_UP,
            iterations,
            BENCHMARK_TIME_LIMIT,
            () -> searchService.searchProducts(criteria, pageable));

    long[] sorted = samples.nanos().stream().mapToLong(Long::longValue).sorted().toArray();
    long total = 0;
    for (long nanos : sorted) {
      total += nanos;
    }
    ProductSearchPageResponse last = samples.lastResult();
    return new BenchmarkResponse(
        samples.warmUp(),
        iterations,
        sorted.length,
        millis(sorted[0]),
        millis(nearestRank(sorted, P50)),
        millis(nearestRank(sorted, P95)),
        millis(nearestRank(sorted, P99)),
        millis(sorted[sorted.length - 1]),
        BigDecimal.valueOf(total, NANOS_TO_MILLIS_SCALE)
            .divide(BigDecimal.valueOf(sorted.length), MILLIS_SCALE, RoundingMode.HALF_UP),
        last.matchMode(),
        last.page().totalElements());
  }

  /**
   * Nearest-rank: phần tử thứ ⌈p × n / 100⌉ (đếm từ 1) của dãy đã sắp tăng dần — luôn là một lần đo
   * có thật. 100 lần đo: p99 là lần thứ 99, không phải lần chậm nhất.
   */
  static long nearestRank(long[] sorted, int percentile) {
    return sorted[Math.ceilDiv(percentile * sorted.length, 100) - 1];
  }

  private static BigDecimal millis(long nanos) {
    return BigDecimal.valueOf(nanos, NANOS_TO_MILLIS_SCALE)
        .setScale(MILLIS_SCALE, RoundingMode.HALF_UP);
  }

  // ================================================================
  // Kiểm đầu vào + lỗi
  // ================================================================

  /**
   * Bắt buộc, tối đa {@link #MAX_TABLE_NAME_BYTES} byte UTF-8 — đếm byte như Postgres, không đếm ký
   * tự: tên dài hơn sẽ bị cắt khi so với {@code pg_class.relname} và có thể khớp một bảng khác.
   */
  private static void requireTableName(String tableName) {
    if (tableName == null || tableName.isBlank()) {
      throw invalid("Tên bảng bắt buộc.");
    }
    int bytes = tableName.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_TABLE_NAME_BYTES) {
      throw invalid(
          "Tên bảng tối đa %d byte (giới hạn tên của Postgres), nhận %d byte.",
          MAX_TABLE_NAME_BYTES, bytes);
    }
  }

  /**
   * Thống kê của bảng, trong transaction (hoặc không có transaction) của method đang chạy — gọi qua
   * repository, không qua {@link #tableStats}: tự gọi method {@code @Transactional} của chính class
   * thì bỏ qua proxy (SP-014).
   */
  private TableStatsResponse statsOf(String tableName) {
    return repository.tableStats(tableName).orElseThrow(() -> tableNotFound(tableName));
  }

  private static NotFoundException tableNotFound(String tableName) {
    return new NotFoundException(
        ErrorCode.ADM_TABLE_NOT_FOUND,
        "Schema app không có bảng / materialized view '%s'.".formatted(tableName));
  }

  private static BadRequestException invalid(String format, Object... args) {
    return new BadRequestException(ErrorCode.VALIDATION_FAILED, format.formatted(args));
  }
}
