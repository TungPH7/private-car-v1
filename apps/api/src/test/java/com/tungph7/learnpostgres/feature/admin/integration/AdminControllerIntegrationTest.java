package com.tungph7.learnpostgres.feature.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.assertj.core.groups.Tuple;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.LockTestSupport;
import com.tungph7.learnpostgres.feature.admin.domain.IndexUsage;
import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.service.AdminService;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin của F08 đi hết HTTP → controller → service → Postgres, không mock gì — AC-10 – AC-13 qua
 * đúng endpoint của chúng, đối chiếu với catalog / bộ thống kê đọc bằng câu SQL viết tay và với
 * chính {@code GET /api/v1/search/products}.
 *
 * <p>{@code AdminControllerWebMvcTest} có HTTP nhưng service là mock; {@code
 * AdminQueryRepositoryJdbcTest}, {@code AdminServiceIntegrationTest} có Postgres nhưng không qua
 * HTTP. Ở đây kiểm những gì chỉ đúng khi ghép cả hai:
 *
 * <ul>
 *   <li>Catalog và bộ thống kê thật ra JSON: danh sách index khớp {@code pg_indexes}, mức dùng sau
 *       traffic thật, {@code queryId} chuỗi = đúng {@code queryid} của {@code pg_stat_statements}.
 *   <li>EXPLAIN / benchmark chạy đúng lần tìm của body: plan và số đo nói về cùng kết quả mà GET
 *       trả cho cùng tham số.
 *   <li>Lỗi sinh ra từ thành phần thật, qua handler thật: bảng không có (404), {@code lock_timeout}
 *       (409), {@code statement_timeout} khi chờ khóa (503) — và connection không bị HikariCP đóng.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional}: mỗi request một transaction của chính nó, chỉ thấy thứ đã
 * commit. Mỗi test: bảng thử {@value #PROBE} (tắt autovacuum, tạo lại — số đếm của bộ thống kê bắt
 * đầu từ 0) và 4 sản phẩm trong một category riêng, dọn trước và sau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class AdminControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String ADMIN = "/api/v1/admin";
  private static final String SEARCH = "/api/v1/search/products";
  private static final String EXPLAIN = ADMIN + "/search/products/explain";
  private static final String BENCHMARK = ADMIN + "/search/products/benchmark";
  private static final String PROBE = "admin_http_probe";
  private static final String SLUG = "adm-http-";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired SearchService searchService;
  @Autowired JdbcClient jdbc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired TransactionTemplate tx;
  @Autowired DataSource dataSource;

  private UUID category;

  @BeforeEach
  void setUp() {
    cleanup();
    tx.executeWithoutResult(
        status -> {
          jdbc.sql(
                  """
                  CREATE TABLE app.admin_http_probe (id int PRIMARY KEY, note text, kind text)
                  WITH (autovacuum_enabled = false)
                  """)
              .update();
          jdbc.sql("CREATE INDEX admin_http_probe_note_idx ON app.admin_http_probe (note)")
              .update();
          jdbc.sql("CREATE INDEX admin_http_probe_kind_idx ON app.admin_http_probe (kind)")
              .update();
          jdbc.sql(
                  """
                  INSERT INTO app.admin_http_probe
                  SELECT g, 'note ' || g, 'kind ' || g % 10 FROM generate_series(1, 1000) g
                  """)
              .update();
          category =
              jdbc.sql(
                      """
                      INSERT INTO app.categories (name, slug) VALUES ('Admin HTTP test', :slug)
                      RETURNING id
                      """)
                  .param("slug", SLUG + "cat")
                  .query(UUID.class)
                  .single();
          product(1, "Ốp lưng iPhone 15 Pro Trong suốt");
          product(2, "Ốp lưng iPhone 15 Pro Max Silicon Đen");
          product(3, "Iphone Case Leather");
          product(4, "Tai nghe AirPods Pro 2 Trắng");
        });
  }

  /** BẮT BUỘC dọn: dữ liệu và bảng thử đã commit. {@link #setUp()} cũng gọi ở dòng đầu. */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("DROP TABLE IF EXISTS app.admin_http_probe").update();
          jdbc.sql("DELETE FROM app.products WHERE slug LIKE :slug")
              .param("slug", SLUG + "%")
              .update();
          jdbc.sql("DELETE FROM app.categories WHERE slug LIKE :slug")
              .param("slug", SLUG + "%")
              .update();
        });
  }

  private void product(int number, String name) {
    jdbc.sql(
            """
            INSERT INTO app.products (name, slug, price, status, category_id)
            VALUES (:name, :slug, 150000, 'ACTIVE', :category)
            """)
        .param("name", name)
        .param("slug", SLUG + number)
        .param("category", category)
        .update();
  }

  /** Request → 200, body nguyên văn. */
  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private <T> T read(MockHttpServletRequestBuilder request, Class<T> type) throws Exception {
    return objectMapper.readValue(body(request), type);
  }

  private <T> T read(MockHttpServletRequestBuilder request, TypeReference<T> type)
      throws Exception {
    return objectMapper.readValue(body(request), type);
  }

  /** {@code GET /api/v1/search/products} của từ khoá trong category của test. */
  private ProductSearchPageResponse search(String q) throws Exception {
    return read(
        get(SEARCH).param("q", q).param("categoryId", category.toString()),
        ProductSearchPageResponse.class);
  }

  /** Body của EXPLAIN / benchmark — đúng tham số của {@link #search(String)}. */
  private String searchBody(String q) {
    return "{\"q\":\"%s\",\"categoryId\":\"%s\"}".formatted(q, category);
  }

  private static MockHttpServletRequestBuilder explain(MatchMode matchMode, String body) {
    return post(EXPLAIN)
        .param("matchMode", matchMode.name())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static PlanNodeResponse node(ExplainResponse explain, String nodeType) {
    return explain.nodes().stream()
        .filter(node -> node.nodeType().equals(nodeType))
        .findFirst()
        .orElseThrow();
  }

  // ================================================================
  // AC-10, AC-11 — index
  // ================================================================

  @Test
  @DisplayName(
      "AC-10: /indexes = mọi index của schema app theo pg_indexes (câu SQL viết tay) — bảng, tên,"
          + " định nghĩa — kèm kích thước, theo bảng rồi tên index; có 2 index của V18")
  void indexesListTheWholeAppSchema() throws Exception {
    List<IndexResponse> indexes =
        read(get(ADMIN + "/indexes"), new TypeReference<List<IndexResponse>>() {});
    List<Tuple> manual =
        jdbc.sql(
                """
                SELECT tablename, indexname, regexp_replace(indexdef, '^.*? USING ', '')
                FROM pg_indexes
                WHERE schemaname = 'app'
                """)
            .query((rs, rowNum) -> tuple(rs.getString(1), rs.getString(2), rs.getString(3)))
            .list();

    assertThat(indexes)
        .extracting(IndexResponse::tableName, IndexResponse::indexName, IndexResponse::definition)
        .containsExactlyInAnyOrderElementsOf(manual);
    assertThat(indexes)
        .isSortedAccordingTo(
            Comparator.comparing(IndexResponse::tableName).thenComparing(IndexResponse::indexName))
        // một index có ít nhất một trang (8 kB) — trang meta
        .allSatisfy(index -> assertThat(index.sizeBytes()).isGreaterThanOrEqualTo(8192));
    Map<String, IndexResponse> byName =
        indexes.stream().collect(Collectors.toMap(IndexResponse::indexName, Function.identity()));
    assertThat(byName.get("idx_products_name_trgm"))
        .satisfies(
            trigram -> {
              assertThat(trigram.method()).isEqualTo("gin");
              assertThat(trigram.definition())
                  .endsWith("search_normalize((name)::text) gin_trgm_ops)");
              assertThat(trigram.valid()).isTrue();
              assertThat(trigram.partial()).isFalse();
            });
    assertThat(byName.get("idx_customers_created_at"))
        .extracting(IndexResponse::tableName, IndexResponse::method, IndexResponse::definition)
        .containsExactly("customers", "btree", "btree (created_at)");
  }

  @Test
  @DisplayName(
      "AC-11: sau traffic thật, /indexes/usage tách index được quét (RARELY_USED) khỏi index chưa"
          + " ai quét (UNUSED — ứng viên để bỏ) và khóa chính (UNUSED nhưng giữ ràng buộc)")
  void indexUsageFlagsUnusedIndexesAfterTraffic() throws Exception {
    // Một lần quét admin_http_probe_note_idx, ghi vào bộ thống kê ngay khi transaction kết thúc
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL enable_seqscan = off").update();
          jdbc.sql("SELECT count(*) FROM app.admin_http_probe WHERE note = 'note 42'")
              .query(Long.class)
              .single();
          jdbc.sql("SELECT pg_stat_force_next_flush()").query().singleRow();
        });

    List<IndexUsageResponse> usage =
        read(get(ADMIN + "/indexes/usage"), new TypeReference<List<IndexUsageResponse>>() {});

    assertThat(usage)
        .filteredOn(index -> index.tableName().equals(PROBE))
        .extracting(
            IndexUsageResponse::indexName,
            IndexUsageResponse::scans,
            IndexUsageResponse::usage,
            IndexUsageResponse::enforcesConstraint)
        .containsExactlyInAnyOrder(
            tuple("admin_http_probe_note_idx", 1L, IndexUsage.RARELY_USED, false),
            tuple("admin_http_probe_kind_idx", 0L, IndexUsage.UNUSED, false),
            tuple("admin_http_probe_pkey", 0L, IndexUsage.UNUSED, true));
    // chưa được quét trước
    assertThat(usage).extracting(IndexUsageResponse::scans).isSorted();
  }

  // ================================================================
  // Câu chậm
  // ================================================================

  @Test
  @DisplayName(
      "/slow-queries: câu tìm vừa chạy qua HTTP có mặt, đã chuẩn hoá; queryId là chuỗi, đúng"
          + " từng chữ số queryid của pg_stat_statements; tốn nhất trước")
  void slowQueriesListTheSearchWithItsExactQueryId() throws Exception {
    // test user của container là superuser — app role thật không gọi được hàm này
    jdbc.sql("SELECT pg_stat_statements_reset()").query().singleRow();
    search("iphone");

    String json = body(get(ADMIN + "/slow-queries").param("limit", "100"));
    List<SlowQueryResponse> slow =
        objectMapper.readValue(json, new TypeReference<List<SlowQueryResponse>>() {});
    SlowQueryResponse page =
        slow.stream()
            .filter(query -> query.query().contains("count(*) OVER ()"))
            .filter(query -> query.query().contains("p.category_id = $"))
            .findFirst()
            .orElseThrow();
    long queryId =
        jdbc.sql(
                """
                SELECT queryid FROM pg_stat_statements
                WHERE query = :query AND userid = current_user::regrole
                  AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                """)
            .param("query", page.query())
            .query(Long.class)
            .single();

    assertThat(page.queryId()).isEqualTo(queryId);
    assertThat(json).contains("\"queryId\":\"" + queryId + "\"");
    assertThat(page.calls()).isEqualTo(1);
    assertThat(page.query()).contains("app.search_normalize($");
    assertThat(slow)
        .extracting(SlowQueryResponse::totalMs)
        .isSortedAccordingTo(Comparator.reverseOrder());
  }

  // ================================================================
  // Bảo trì — lỗi từ thành phần thật
  // ================================================================

  @Test
  @DisplayName(
      "Bảng không có → 404 từ catalog thật; bảng đang bị giữ khóa → 409 sau đúng lock_timeout 2 s"
          + " (ANALYZE, VACUUM FULL); nhả khóa → 200, connection không bị HikariCP đóng")
  void maintenanceErrorsComeFromTheCatalogAndTheLock(CapturedOutput output) throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            get(ADMIN + "/tables/khong_co/stats"),
            post(ADMIN + "/tables/khong_co/analyze"),
            post(ADMIN + "/tables/khong_co/vacuum"))) {
      mockMvc
          .perform(request)
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("ADM_TABLE_NOT_FOUND"))
          .andExpect(
              jsonPath("$.detail")
                  .value("Schema app không có bảng / materialized view 'khong_co'."));
    }

    try (Connection otherTransaction =
        LockTestSupport.holdTableLock(
            dataSource, "LOCK TABLE app.admin_http_probe IN SHARE UPDATE EXCLUSIVE MODE")) {
      Map<String, MockHttpServletRequestBuilder> commands =
          Map.of(
              "ANALYZE",
              post(ADMIN + "/tables/" + PROBE + "/analyze"),
              "VACUUM FULL",
              post(ADMIN + "/tables/" + PROBE + "/vacuum").param("full", "true"));
      for (Map.Entry<String, MockHttpServletRequestBuilder> command : commands.entrySet()) {
        long start = System.nanoTime();
        assertTimeoutPreemptively(
            Duration.ofSeconds(LockTestSupport.WAIT_SECONDS),
            () ->
                mockMvc
                    .perform(command.getValue())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADM_TABLE_LOCKED"))
                    .andExpect(
                        jsonPath("$.detail")
                            .value(
                                "%s '%s' đã chờ khóa của bảng 2 giây và bỏ — transaction khác"
                                        .formatted(command.getKey(), PROBE)
                                    + " đang giữ bảng, thử lại sau.")));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .as(command.getKey())
            .isGreaterThanOrEqualTo(Duration.ofSeconds(AdminService.LOCK_TIMEOUT_SECONDS));
      }
    }

    mockMvc
        .perform(post(ADMIN + "/tables/" + PROBE + "/analyze"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableName").value(PROBE))
        .andExpect(jsonPath("$.liveRows").value(1000))
        .andExpect(jsonPath("$.lastAnalyze").isNotEmpty());
    assertThat(output)
        .contains(
            "Business 409 [ADM_TABLE_LOCKED] at /api/v1/admin/tables/admin_http_probe/analyze")
        .doesNotContain("marked as broken");
  }

  // ================================================================
  // AC-12 — EXPLAIN
  // ================================================================

  @Test
  @DisplayName(
      "AC-12: EXPLAIN chạy đúng câu của lần tìm trong body (SQL, tham số, ngưỡng) — plan JSON của"
          + " Postgres + từng node; Limit trả đúng số sản phẩm GET trả, WindowAgg đếm đúng tổng")
  void explainShowsThePlanOfTheRealSearch() throws Exception {
    ProductSearchPageResponse searched = search("iphone");

    ExplainResponse explain =
        read(explain(MatchMode.STRICT, searchBody("iphone")), ExplainResponse.class);

    assertThat(explain.sql())
        .isEqualTo(
            searchService
                .pageStatement(
                    new ProductSearchCriteria("iphone", category, null, null, null, null),
                    MatchMode.STRICT,
                    PageRequest.of(0, 20))
                .sql());
    assertThat(explain.parameters())
        .isEqualTo(
            Map.of("categoryId", category.toString(), "limit", 20, "offset", 0, "q", "iphone"));
    assertThat(explain.settings())
        .isEqualTo(
            Map.of(
                "pg_trgm.word_similarity_threshold",
                "0.6",
                "plan_cache_mode",
                "force_custom_plan"));
    assertThat(explain.nodes().get(0))
        .extracting(
            PlanNodeResponse::depth, PlanNodeResponse::nodeType, PlanNodeResponse::actualRows)
        .containsExactly(0, "Limit", (long) searched.content().size());
    assertThat(node(explain, "WindowAgg").actualRows())
        .isEqualTo(searched.page().totalElements())
        .isEqualTo(3);
    assertThat(explain.nodes()).contains(explain.bottleneck());
    assertThat(explain.plan().isArray()).isTrue();
    assertThat(explain.plan().path(0).path("Plan").path("Node Type").asString()).isEqualTo("Limit");
  }

  @Test
  @DisplayName(
      "AC-12: EXPLAIN chạy đúng mức được hỏi, không tự nới — 'iphne' ở STRICT: 0 row, ở RELAXED"
          + " (0,4): 3 row như tìm thật; body sai → 400 của service như GET")
  void explainRunsTheRequestedThresholdOnly() throws Exception {
    ExplainResponse strict =
        read(explain(MatchMode.STRICT, searchBody("iphne")), ExplainResponse.class);
    ExplainResponse relaxed =
        read(explain(MatchMode.RELAXED, searchBody("iphne")), ExplainResponse.class);

    assertThat(node(strict, "WindowAgg").actualRows()).isZero();
    assertThat(relaxed.settings()).containsEntry("pg_trgm.word_similarity_threshold", "0.4");
    assertThat(node(relaxed, "WindowAgg").actualRows())
        .isEqualTo(search("iphne").page().totalElements())
        .isEqualTo(3);
    mockMvc
        .perform(explain(MatchMode.STRICT, "{\"q\":\" a \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.detail")
                .value("Từ khoá phải dài 2 – 80 ký tự (không tính khoảng trắng hai đầu), nhận 1."));
  }

  @Test
  @DisplayName(
      "503 qua chuỗi thật: EXPLAIN đứng chờ khóa ACCESS EXCLUSIVE của products → statement_timeout"
          + " 10 s → 503 ADM_QUERY_TIMEOUT; nhả khóa, EXPLAIN chạy lại được — connection còn dùng"
          + " được")
  void explainWaitingForALockTimesOut(CapturedOutput output) throws Exception {
    ExecutorService client = Executors.newSingleThreadExecutor();
    try (Connection otherTransaction =
        LockTestSupport.holdTableLock(
            dataSource, "LOCK TABLE app.products IN ACCESS EXCLUSIVE MODE")) {
      long start = System.nanoTime();
      Future<MvcResult> request =
          client.submit(
              () -> mockMvc.perform(explain(MatchMode.STRICT, searchBody("iphone"))).andReturn());
      // EXPLAIN đứng ở khóa của products — không phải chậm vì lý do khác
      LockTestSupport.awaitLockWaiters(jdbcTemplate, 1);
      MvcResult result = request.get(LockTestSupport.WAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(Duration.ofNanos(System.nanoTime() - start))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(AdminService.EXPLAIN_TIMEOUT_SECONDS));
      assertThat(result.getResponse().getStatus()).isEqualTo(503);
      assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
          .contains("\"code\":\"ADM_QUERY_TIMEOUT\"")
          .contains("hoặc phải chờ khóa của bảng quá lâu");
    } finally {
      client.shutdownNow();
    }

    assertThat(read(explain(MatchMode.STRICT, searchBody("iphone")), ExplainResponse.class).nodes())
        .isNotEmpty();
    assertThat(output)
        .contains("Business 5xx [ADM_QUERY_TIMEOUT] at /api/v1/admin/search/products/explain")
        .doesNotContain("marked as broken");
  }

  // ================================================================
  // AC-13 — benchmark
  // ================================================================

  @Test
  @DisplayName(
      "AC-13: benchmark gọi đúng lần tìm của body — 10 lần khởi động + 5 lần đo; min ≤ p50 ≤ p95 ≤"
          + " p99 ≤ max, mean ở giữa; matchMode và tổng như GET ('iphone' STRICT, 'iphne' RELAXED)")
  void benchmarkMeasuresTheRealSearch() throws Exception {
    for (String q : List.of("iphone", "iphne")) {
      ProductSearchPageResponse searched = search(q);

      BenchmarkResponse result =
          read(
              post(BENCHMARK)
                  .param("iterations", "5")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(searchBody(q)),
              BenchmarkResponse.class);

      assertThat(result.warmUp()).isEqualTo(AdminService.BENCHMARK_WARM_UP);
      assertThat(result.requestedIterations()).isEqualTo(5);
      assertThat(result.iterations()).isEqualTo(5);
      assertThat(
              List.of(
                  result.minMs(), result.p50Ms(), result.p95Ms(), result.p99Ms(), result.maxMs()))
          .isSortedAccordingTo(BigDecimal::compareTo);
      assertThat(result.minMs()).isPositive();
      assertThat(result.meanMs()).isBetween(result.minMs(), result.maxMs());
      assertThat(result.matchMode()).isEqualTo(searched.matchMode());
      assertThat(result.totalElements()).isEqualTo(searched.page().totalElements());
    }
  }
}
