package com.tungph7.learnpostgres.feature.admin.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Kết quả {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} của một câu do code dựng: câu đã giải
 * thích, tóm tắt, và JSON thô của Postgres. Thời gian đơn vị ms, 3 chữ số lẻ như Postgres.
 *
 * <p>{@link Schema} ở {@code plan} là bắt buộc, không chỉ để trang trí. swagger-core (dựng schema
 * bằng Jackson 2) không biết {@code JsonNode} của Jackson 3 là "JSON bất kỳ" — nó soi lớp đó như
 * một bean, và OpenAPI mô tả {@code plan} là một object với các field boolean {@code array}, {@code
 * empty}, {@code nodeType}... {@code implementation = Object[].class} để swagger-core không đụng
 * tới {@code JsonNode}; {@code types = "array"} cho OpenAPI 3.1. Bỏ annotation thì {@code
 * AdminOpenApiIntegrationTest} đỏ.
 *
 * @param sql câu đã giải thích, tham số dạng tên ({@code :q})
 * @param parameters giá trị của từng tham số
 * @param settings tham số cấu hình đặt cho transaction trước khi chạy câu — vd ngưỡng của pg_trgm
 * @param planningMs thời gian lập plan ({@code Planning Time})
 * @param executionMs thời gian thực thi ({@code Execution Time}) — {@code ANALYZE} chạy thật câu
 * @param sharedHitBlocks số trang đọc thấy sẵn trong shared buffers, cả câu (node gốc cộng dồn node
 *     con)
 * @param sharedReadBlocks số trang phải đọc từ đĩa (hoặc page cache của hệ điều hành)
 * @param cacheHitPct {@code hit / (hit + read)} theo phần trăm, 2 chữ số; {@code null} khi câu
 *     không đọc trang nào
 * @param bottleneck node có {@code selfMs} lớn nhất — chỗ câu tốn thời gian nhất
 * @param nodes mọi node, theo thứ tự của plan dạng chữ
 * @param plan JSON thô ({@code FORMAT JSON}: một mảng, một phần tử) — dán được vào các công cụ đọc
 *     plan
 */
public record ExplainResponse(
    String sql,
    Map<String, Object> parameters,
    Map<String, String> settings,
    BigDecimal planningMs,
    BigDecimal executionMs,
    long sharedHitBlocks,
    long sharedReadBlocks,
    BigDecimal cacheHitPct,
    PlanNodeResponse bottleneck,
    List<PlanNodeResponse> nodes,
    @Schema(
            types = "array",
            implementation = Object[].class,
            description = "JSON thô của Postgres — một mảng, một phần tử")
        JsonNode plan) {}
