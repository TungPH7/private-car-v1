package com.tungph7.learnpostgres.feature.admin.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.admin.service.AdminService;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API admin của F08 — index, thống kê, bảo trì của schema {@code app}, và EXPLAIN / benchmark
 * câu tìm sản phẩm, dưới {@code /api/v1/admin}.
 *
 * <p><b>URL theo tài nguyên.</b> ANALYZE / VACUUM là hành động trên một bảng: {@code POST
 * /tables/{name}/analyze}, cạnh {@code GET /tables/{name}/stats} — cùng khuôn {@code POST
 * /orders/{id}/cancel} (F05), không phải {@code POST /analyze} kèm tên bảng trong body (động từ
 * đứng riêng trên URL — API-002). EXPLAIN / benchmark là hành động trên câu tìm sản phẩm: {@code
 * POST /search/products/explain}. Body là {@link ProductSearchRequest} — đúng tham số của {@code
 * GET /api/v1/search/products}; tùy chọn của admin ({@code matchMode}, {@code iterations}) đi trên
 * query. Thêm một câu được phép = thêm một cặp endpoint mang tên câu đó, không nhận SQL hay map
 * tham số tự do.
 *
 * <p><b>Chưa có xác thực.</b> {@code SecurityConfig} cho mọi client gọi {@code /api/**}; khi có
 * JWT, {@code /api/v1/admin/**} chỉ dành cho admin. Không giả lập bằng header kiểu {@code X-Admin:
 * true}: client tự đặt được mọi header, kiểm tra như vậy không chặn được ai.
 *
 * <p>Giới hạn khai bằng hằng số của {@link AdminService}, service kiểm lại — như các controller của
 * F07. Tên bảng không có Bean Validation: giới hạn tính theo byte UTF-8 ({@code @Size} đếm ký tự),
 * chỉ service kiểm.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(
    name = "Admin",
    description = "Indexes, statistics & maintenance of schema app (F08) — no authentication yet")
public class AdminController {

  /** {@code limit} của câu chậm khi client không gửi. */
  private static final String DEFAULT_SLOW_QUERIES = "20";

  /** {@code iterations} của benchmark khi client không gửi. */
  private static final String DEFAULT_ITERATIONS = "100";

  // ---- Mô tả của OpenAPI, dựng từ chính hằng số service dùng — không lệch được ----

  private static final String TABLE_NAME =
      "Tên bảng / materialized view trong schema app, đúng như trong catalog; tối đa "
          + AdminService.MAX_TABLE_NAME_BYTES
          + " byte UTF-8";

  private static final String TABLE_NAME_ERRORS =
      "VALIDATION_FAILED (tên bảng rỗng / quá " + AdminService.MAX_TABLE_NAME_BYTES + " byte)";

  private static final String TABLE_NOT_FOUND =
      "ADM_TABLE_NOT_FOUND — schema app không có bảng / materialized view tên đó";

  private static final String TABLE_LOCKED =
      "ADM_TABLE_LOCKED — transaction khác giữ bảng quá "
          + AdminService.LOCK_TIMEOUT_SECONDS
          + " giây; thử lại sau";

  private static final String SEARCH_ERRORS =
      "VALIDATION_FAILED (tham số tìm ngoài giới hạn như GET /api/v1/search/products),"
          + " MALFORMED_REQUEST (thiếu body, JSON hỏng, sai kiểu)";

  private final AdminService service;

  public AdminController(AdminService service) {
    this.service = service;
  }

  // -----------------------------------------------------------------
  // Index, câu chậm, thống kê bảng
  // -----------------------------------------------------------------

  @GetMapping("/indexes")
  @Operation(
      summary = "Index của schema app",
      description = "Cấu trúc và kích thước, theo bảng rồi tên index. Mức dùng: /indexes/usage.")
  @ApiResponse(responseCode = "200", description = "Mọi index của schema app")
  public List<IndexResponse> indexes() {
    return service.indexes();
  }

  @GetMapping("/indexes/usage")
  @Operation(
      summary = "Mức dùng của index",
      description =
          "Số lần quét từ lần reset thống kê gần nhất. UNUSED mà enforcesConstraint = false:"
              + " ứng viên để bỏ; index giữ ràng buộc (khóa chính, UNIQUE, EXCLUDE) thì không.")
  @ApiResponse(
      responseCode = "200",
      description = "Mọi index của schema app — chưa được quét trước, rồi index lớn trước")
  public List<IndexUsageResponse> indexUsage() {
    return service.indexUsage();
  }

  @GetMapping("/slow-queries")
  @Operation(
      summary = "Câu tốn nhiều thời gian nhất (pg_stat_statements)",
      description =
          "Theo tổng thời gian thực thi, chỉ câu của database và role của app, cộng dồn từ lần"
              + " reset gần nhất. queryId là chuỗi: số 64 bit, đọc thành number của JavaScript"
              + " chỉ giữ được 15–16 chữ số đầu.")
  @ApiResponse(responseCode = "200", description = "Tối đa limit câu, tốn nhất trước")
  @ApiResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED (limit ngoài 1 – "
              + AdminService.MAX_SLOW_QUERIES
              + "), INVALID_PARAMETER (limit không phải số)")
  public List<SlowQueryResponse> slowQueries(
      @RequestParam(defaultValue = DEFAULT_SLOW_QUERIES) @Min(1) @Max(AdminService.MAX_SLOW_QUERIES)
          int limit) {
    return service.slowQueries(limit);
  }

  @GetMapping("/tables/{name}/stats")
  @Operation(
      summary = "Thống kê của bảng",
      description =
          "Kích thước; row sống / chết là ước lượng của bộ thống kê, không phải count(*); số lần"
              + " quét; mốc VACUUM / ANALYZE.")
  @ApiResponse(responseCode = "200", description = "Thống kê của bảng")
  @ApiResponse(responseCode = "400", description = TABLE_NAME_ERRORS)
  @ApiResponse(responseCode = "404", description = TABLE_NOT_FOUND)
  public TableStatsResponse tableStats(
      @Parameter(description = TABLE_NAME) @PathVariable String name) {
    return service.tableStats(name);
  }

  // -----------------------------------------------------------------
  // Bảo trì
  // -----------------------------------------------------------------

  @PostMapping("/tables/{name}/analyze")
  @Operation(
      summary = "ANALYZE bảng",
      description =
          "Cập nhật thống kê cho planner, trong transaction riêng (commit khi xong); trả thống kê"
              + " của bảng sau khi chạy. Không chặn đọc / ghi.")
  @ApiResponse(responseCode = "200", description = "Thống kê của bảng sau ANALYZE")
  @ApiResponse(responseCode = "400", description = TABLE_NAME_ERRORS)
  @ApiResponse(responseCode = "404", description = TABLE_NOT_FOUND)
  @ApiResponse(responseCode = "409", description = TABLE_LOCKED)
  public TableStatsResponse analyze(
      @Parameter(description = TABLE_NAME) @PathVariable String name) {
    return service.analyze(name);
  }

  @PostMapping("/tables/{name}/vacuum")
  @Operation(
      summary = "VACUUM bảng",
      description =
          "full = false: VACUUM — dọn row chết để tái dùng chỗ, không chặn đọc / ghi. full = true:"
              + " VACUUM (FULL, ANALYZE) — viết lại cả bảng, trả chỗ trống cho hệ điều hành, và"
              + " khóa ACCESS EXCLUSIVE suốt lúc chạy: mọi câu đọc / ghi bảng đó đứng chờ tới khi"
              + " xong. Chạy ngoài transaction; trả thống kê của bảng sau khi chạy.")
  @ApiResponse(responseCode = "200", description = "Thống kê của bảng sau VACUUM")
  @ApiResponse(
      responseCode = "400",
      description = TABLE_NAME_ERRORS + ", INVALID_PARAMETER (full không phải boolean)")
  @ApiResponse(responseCode = "404", description = TABLE_NOT_FOUND)
  @ApiResponse(responseCode = "409", description = TABLE_LOCKED)
  public TableStatsResponse vacuum(
      @Parameter(description = TABLE_NAME) @PathVariable String name,
      @Parameter(description = "true = VACUUM (FULL, ANALYZE) — khóa cả bảng suốt lúc chạy")
          @RequestParam(defaultValue = "false")
          boolean full) {
    return service.vacuum(name, full);
  }

  // -----------------------------------------------------------------
  // EXPLAIN + benchmark — câu tìm sản phẩm
  // -----------------------------------------------------------------

  @PostMapping("/search/products/explain")
  @Operation(
      summary = "EXPLAIN ANALYZE câu tìm sản phẩm",
      description =
          "Body: đúng tham số của GET /api/v1/search/products. Chạy thật câu trang mà lần tìm đó"
              + " gửi đi ở ngưỡng matchMode (tìm thật chỉ chạy RELAXED khi STRICT không ra gì),"
              + " trong transaction READ ONLY, tối đa "
              + AdminService.EXPLAIN_TIMEOUT_SECONDS
              + " giây. Trả SQL, tham số, tóm tắt plan (thời gian, buffer, từng node, node tốn"
              + " thời gian nhất) và JSON thô của Postgres.")
  @ApiResponse(responseCode = "200", description = "Plan và tóm tắt")
  @ApiResponse(
      responseCode = "400",
      description = SEARCH_ERRORS + ", INVALID_PARAMETER (thiếu matchMode / ngoài danh sách)")
  @ApiResponse(
      responseCode = "503",
      description =
          "ADM_QUERY_TIMEOUT — câu chạy, hoặc chờ khóa của bảng, quá "
              + AdminService.EXPLAIN_TIMEOUT_SECONDS
              + " giây; Postgres hủy (statement_timeout)")
  public ExplainResponse explainProductSearch(
      @Valid @RequestBody ProductSearchRequest search,
      @Parameter(description = "Ngưỡng so tên của câu được giải thích") @RequestParam
          MatchMode matchMode) {
    return service.explainProductSearch(search.toCriteria(), matchMode, search.toPageable());
  }

  @PostMapping("/search/products/benchmark")
  @Operation(
      summary = "Benchmark tìm sản phẩm",
      description =
          "Body: đúng tham số của GET /api/v1/search/products. Gọi tìm sản phẩm iterations lần,"
              + " mỗi lần như một request (transaction riêng, ngưỡng chuẩn rồi nới nếu cần), sau "
              + AdminService.BENCHMARK_WARM_UP
              + " lần khởi động; p50 / p95 / p99 theo nearest-rank. Dừng sớm ở "
              + AdminService.BENCHMARK_TIME_LIMIT_SECONDS
              + " giây — iterations trong kết quả là số lần đã đo.")
  @ApiResponse(responseCode = "200", description = "Thời gian của các lần đo, đơn vị ms")
  @ApiResponse(
      responseCode = "400",
      description =
          SEARCH_ERRORS
              + ", VALIDATION_FAILED (iterations ngoài 1 – "
              + AdminService.MAX_BENCHMARK_ITERATIONS
              + "), INVALID_PARAMETER (iterations không phải số)")
  public BenchmarkResponse benchmarkProductSearch(
      @Valid @RequestBody ProductSearchRequest search,
      @Parameter(description = "Số lần đo, không tính lần khởi động")
          @RequestParam(defaultValue = DEFAULT_ITERATIONS)
          @Min(1)
          @Max(AdminService.MAX_BENCHMARK_ITERATIONS)
          int iterations) {
    return service.benchmarkProductSearch(search.toCriteria(), search.toPageable(), iterations);
  }
}
