package com.tungph7.learnpostgres.feature.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test cho {@link ExplainPlanReader} trên JSON {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)}
 * thật của DB lab ({@code F08__search-performance/03-service-and-error-codes.md}) — chỉ giữ các
 * khóa reader đọc, giá trị giữ nguyên.
 */
class ExplainPlanReaderTest {

  private static final SqlStatement STATEMENT =
      new SqlStatement(
          "SELECT count(*) FROM app.order_items",
          Map.of("q", "iphone"),
          Map.of("pg_trgm.word_similarity_threshold", "0.6"));

  /** {@code SELECT count(*) FROM app.order_items} — 1,5 triệu dòng, 2 worker song song. */
  private static final String PARALLEL =
      """
      [{"Plan": {"Node Type": "Aggregate", "Plan Rows": 1, "Actual Total Time": 1666.226,
                 "Actual Rows": 1, "Actual Loops": 1,
                 "Shared Hit Blocks": 2338, "Shared Read Blocks": 19091,
        "Plans": [{"Node Type": "Gather", "Plan Rows": 2, "Actual Total Time": 1666.219,
                   "Actual Rows": 3, "Actual Loops": 1,
          "Plans": [{"Node Type": "Aggregate", "Plan Rows": 1, "Actual Total Time": 1650.210,
                     "Actual Rows": 1, "Actual Loops": 3,
            "Plans": [{"Node Type": "Seq Scan", "Relation Name": "order_items",
                       "Plan Rows": 625000, "Actual Total Time": 1627.145,
                       "Actual Rows": 500000, "Actual Loops": 3}]}]}]},
        "Planning Time": 14.410, "Execution Time": 1667.011}]
      """;

  /** Sản phẩm không có thật JOIN dòng hàng — vòng ngoài 0 row, nhánh trong không chạy lần nào. */
  private static final String NEVER_EXECUTED =
      """
      [{"Plan": {"Node Type": "Nested Loop", "Plan Rows": 16, "Actual Total Time": 0.017,
                 "Actual Rows": 0, "Actual Loops": 1,
                 "Shared Hit Blocks": 3, "Shared Read Blocks": 0,
        "Plans": [{"Node Type": "Index Only Scan", "Index Name": "products_pkey",
                   "Relation Name": "products", "Plan Rows": 1, "Actual Total Time": 0.016,
                   "Actual Rows": 0, "Actual Loops": 1},
                  {"Node Type": "Bitmap Heap Scan", "Relation Name": "order_items",
                   "Plan Rows": 16, "Actual Total Time": 0.000, "Actual Rows": 0,
                   "Actual Loops": 0,
                   "Plans": [{"Node Type": "Bitmap Index Scan",
                              "Index Name": "idx_order_items_product", "Plan Rows": 16,
                              "Actual Total Time": 0.000, "Actual Rows": 0,
                              "Actual Loops": 0}]}]},
        "Planning Time": 1.331, "Execution Time": 0.107}]
      """;

  private static ExplainResponse read(String json, boolean bigDecimalFloats) {
    JsonMapper mapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, bigDecimalFloats)
            .build();
    JsonNode plan = mapper.readTree(json);
    return ExplainPlanReader.read(STATEMENT, plan);
  }

  private static BigDecimal ms(String value) {
    return new BigDecimal(value);
  }

  /**
   * App bật {@code use-big-decimal-for-floats} (application.yml); unit test, hay ai dùng mapper
   * khác, thì số thực thành {@code double}. Kết quả phải như nhau — luôn 3 chữ số lẻ như Postgres.
   */
  @ParameterizedTest(name = "BigDecimal cho số thực: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "plan song song: từng node theo thứ tự plan; thời gian gồm mọi loop; node dưới Gather cộng"
          + " thời gian của mọi process — Gather tự nó không âm")
  void parallelPlan(boolean bigDecimalFloats) {
    ExplainResponse explain = read(PARALLEL, bigDecimalFloats);

    assertThat(explain.planningMs()).isEqualTo(ms("14.410"));
    assertThat(explain.executionMs()).isEqualTo(ms("1667.011"));
    assertThat(explain.nodes())
        .extracting(
            PlanNodeResponse::depth,
            PlanNodeResponse::nodeType,
            PlanNodeResponse::relationName,
            PlanNodeResponse::estimatedRows,
            PlanNodeResponse::actualRows,
            PlanNodeResponse::loops,
            PlanNodeResponse::inclusiveMs,
            PlanNodeResponse::selfMs)
        .containsExactly(
            tuple(0, "Aggregate", null, 1L, 1L, 1L, ms("1666.226"), ms("0.007")),
            tuple(1, "Gather", null, 2L, 3L, 1L, ms("1666.219"), ms("0.000")),
            tuple(2, "Aggregate", null, 1L, 1L, 3L, ms("4950.630"), ms("69.195")),
            tuple(
                3,
                "Seq Scan",
                "order_items",
                625000L,
                500000L,
                3L,
                ms("4881.435"),
                ms("4881.435")));
    assertThat(explain.bottleneck()).isSameAs(explain.nodes().get(3));
  }

  @Test
  @DisplayName("buffer của cả câu lấy ở node gốc; tỷ lệ cache = hit / (hit + read), 2 chữ số")
  void buffersComeFromTheRootNode() {
    ExplainResponse explain = read(PARALLEL, true);

    assertThat(explain.sharedHitBlocks()).isEqualTo(2338);
    assertThat(explain.sharedReadBlocks()).isEqualTo(19091);
    assertThat(explain.cacheHitPct()).isEqualTo(ms("10.91"));
  }

  @Test
  @DisplayName(
      "node không chạy lần nào (loops 0) → 0 ms, không làm âm node cha; index không kèm bảng ghi"
          + " đúng như Postgres in")
  void neverExecutedNodes() {
    ExplainResponse explain = read(NEVER_EXECUTED, true);

    assertThat(explain.nodes())
        .extracting(
            PlanNodeResponse::depth,
            PlanNodeResponse::nodeType,
            PlanNodeResponse::relationName,
            PlanNodeResponse::indexName,
            PlanNodeResponse::loops,
            PlanNodeResponse::selfMs)
        .containsExactly(
            tuple(0, "Nested Loop", null, null, 1L, ms("0.001")),
            tuple(1, "Index Only Scan", "products", "products_pkey", 1L, ms("0.016")),
            tuple(1, "Bitmap Heap Scan", "order_items", null, 0L, ms("0.000")),
            tuple(2, "Bitmap Index Scan", null, "idx_order_items_product", 0L, ms("0.000")));
    assertThat(explain.bottleneck().nodeType()).isEqualTo("Index Only Scan");
    assertThat(explain.cacheHitPct()).isEqualTo(ms("100.00"));
  }

  @Test
  @DisplayName("câu không đọc trang nào → tỷ lệ cache null, không phải 0 hay 100")
  void noBuffersMeansNoCacheRatio() {
    ExplainResponse explain =
        read(
            """
            [{"Plan": {"Node Type": "Result", "Plan Rows": 1, "Actual Total Time": 0.001,
                       "Actual Rows": 1, "Actual Loops": 1,
                       "Shared Hit Blocks": 0, "Shared Read Blocks": 0},
              "Planning Time": 0.020, "Execution Time": 0.008}]
            """,
            true);

    assertThat(explain.cacheHitPct()).isNull();
    assertThat(explain.bottleneck().nodeType()).isEqualTo("Result");
  }

  @Test
  @DisplayName(
      "kết quả mang theo câu đã giải thích (SQL, tham số, setting) và JSON thô của Postgres")
  void carriesTheStatementAndTheRawPlan() {
    JsonNode plan = JsonMapper.builder().build().readTree(NEVER_EXECUTED);

    ExplainResponse explain = ExplainPlanReader.read(STATEMENT, plan);

    assertThat(explain.sql()).isEqualTo(STATEMENT.sql());
    assertThat(explain.parameters()).isEqualTo(STATEMENT.params());
    assertThat(explain.settings()).isEqualTo(STATEMENT.settings());
    assertThat(explain.plan()).isSameAs(plan);
  }
}
