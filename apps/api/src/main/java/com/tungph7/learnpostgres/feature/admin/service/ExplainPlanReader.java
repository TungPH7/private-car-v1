package com.tungph7.learnpostgres.feature.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;

import tools.jackson.databind.JsonNode;

/**
 * Đọc JSON của {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} thành {@link ExplainResponse}: tổng
 * thời gian, buffer, từng node, node tốn thời gian nhất.
 *
 * <p>Chỉ đọc những con số Postgres in ra, cộng hai phép suy: thời gian gồm cả node con của mọi lần
 * chạy ({@code Actual Total Time × Actual Loops}), và thời gian của riêng node (trừ phần của node
 * con). Không kết luận gì từ việc ước lượng số row lệch số thật: lệch có thể do thống kê cũ, do
 * điều kiện chưa có thống kê (index biểu thức mới tạo, chưa ANALYZE — {@code %>} của pg_trgm bị
 * đoán 1% bảng cho mọi từ khoá), do ước lượng theo mẫu vốn thô (cũng {@code %>} đó, ngay sau
 * ANALYZE vẫn có thể lệch hơn 10 lần), hay do node dừng sớm dưới {@code Limit}.
 */
final class ExplainPlanReader {

  private static final int TIME_SCALE = 3;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private ExplainPlanReader() {}

  /**
   * @param statement câu đã giải thích
   * @param explain JSON Postgres trả về — một mảng, một phần tử
   */
  static ExplainResponse read(SqlStatement statement, JsonNode explain) {
    JsonNode result = explain.path(0);
    JsonNode root = result.path("Plan");
    List<PlanNodeResponse> nodes = new ArrayList<>();
    collect(root, 0, nodes);

    long hit = root.path("Shared Hit Blocks").asLong(0);
    long read = root.path("Shared Read Blocks").asLong(0);
    BigDecimal cacheHitPct =
        hit + read == 0
            ? null
            : BigDecimal.valueOf(hit)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(hit + read), 2, RoundingMode.HALF_UP);

    return new ExplainResponse(
        statement.sql(),
        sortedByKey(statement.params()),
        sortedByKey(statement.settings()),
        millis(result.path("Planning Time")),
        millis(result.path("Execution Time")),
        hit,
        read,
        cacheHitPct,
        nodes.stream().max(Comparator.comparing(PlanNodeResponse::selfMs)).orElseThrow(),
        List.copyOf(nodes),
        explain);
  }

  /**
   * Thêm {@code node} rồi các node con vào {@code nodes} — đúng thứ tự plan dạng chữ.
   *
   * @return {@code inclusiveMs} của {@code node}, để node cha trừ ra
   */
  private static BigDecimal collect(JsonNode node, int depth, List<PlanNodeResponse> nodes) {
    long loops = node.path("Actual Loops").asLong(0);
    BigDecimal inclusive =
        millis(node.path("Actual Total Time")).multiply(BigDecimal.valueOf(loops));
    int position = nodes.size();
    nodes.add(null);

    BigDecimal children = BigDecimal.ZERO;
    for (JsonNode child : node.path("Plans").values()) {
      children = children.add(collect(child, depth + 1, nodes));
    }

    nodes.set(
        position,
        new PlanNodeResponse(
            depth,
            node.path("Node Type").asString(),
            node.path("Relation Name").asString(null),
            node.path("Index Name").asString(null),
            node.path("Plan Rows").asLong(0),
            node.path("Actual Rows").asLong(0),
            loops,
            inclusive,
            inclusive.subtract(children).max(BigDecimal.ZERO.setScale(TIME_SCALE))));
    return inclusive;
  }

  /** Số ms của Postgres, 3 chữ số lẻ — như Postgres in, dù Jackson đọc số đó thành double. */
  private static BigDecimal millis(JsonNode value) {
    return value.decimalValue(BigDecimal.ZERO).setScale(TIME_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Bản sao theo thứ tự khóa — JSON trả về giống nhau giữa các lần gọi ({@code Map.copyOf} thì
   * không).
   */
  private static <V> Map<String, V> sortedByKey(Map<String, V> map) {
    return Collections.unmodifiableMap(new TreeMap<>(map));
  }
}
