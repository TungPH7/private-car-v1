package com.tungph7.learnpostgres.feature.admin.dto;

/**
 * Một index của schema {@code app} — cấu trúc và kích thước. Mức dùng: {@link IndexUsageResponse}.
 *
 * @param tableName bảng hoặc materialized view chứa index
 * @param method phương thức: {@code btree}, {@code gin}, {@code gist}, {@code brin}...
 * @param definition phần sau {@code USING} của {@code CREATE INDEX}: phương thức, cột / biểu thức,
 *     điều kiện {@code WHERE} của partial index
 * @param unique UNIQUE, kể cả khóa chính
 * @param valid {@code false} = index hỏng, thường do {@code CREATE INDEX CONCURRENTLY} lỗi giữa
 *     chừng — planner không dùng
 * @param partial có điều kiện {@code WHERE}
 * @param size {@code sizeBytes} dạng dễ đọc ({@code pg_size_pretty})
 */
public record IndexResponse(
    String tableName,
    String indexName,
    String method,
    String definition,
    boolean unique,
    boolean primary,
    boolean valid,
    boolean partial,
    long sizeBytes,
    String size) {}
