package com.tungph7.learnpostgres.feature.admin.dto;

import java.math.BigDecimal;

/**
 * Một node của plan {@code EXPLAIN ANALYZE}, theo đúng thứ tự Postgres in plan dạng chữ (cha trước,
 * con sau). Thời gian đơn vị ms, 3 chữ số lẻ như Postgres.
 *
 * @param depth độ sâu trong cây plan — node gốc là 0
 * @param relationName bảng node đọc; {@code null} với node không đọc bảng (Sort, Limit...)
 * @param indexName index node dùng; {@code null} khi không dùng index
 * @param estimatedRows số row planner ước lượng cho MỘT lần chạy node ({@code Plan Rows})
 * @param actualRows số row thật, trung bình MỘT lần chạy ({@code Actual Rows})
 * @param loops số lần node chạy ({@code Actual Loops}); 0 = không chạy lần nào
 * @param inclusiveMs thời gian của node gồm cả các node con, cộng mọi lần chạy ({@code Actual Total
 *     Time × loops}). Node chạy song song: cộng của mọi process — có thể lớn hơn thời gian thực thi
 *     của cả câu
 * @param selfMs thời gian của riêng node: {@code inclusiveMs} trừ {@code inclusiveMs} của các node
 *     con, không âm
 */
public record PlanNodeResponse(
    int depth,
    String nodeType,
    String relationName,
    String indexName,
    long estimatedRows,
    long actualRows,
    long loops,
    BigDecimal inclusiveMs,
    BigDecimal selfMs) {}
