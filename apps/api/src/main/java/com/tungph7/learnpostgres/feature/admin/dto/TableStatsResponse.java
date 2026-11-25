package com.tungph7.learnpostgres.feature.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kích thước và thống kê của một bảng (hoặc materialized view) của schema {@code app}. Số row là
 * ước lượng của bộ thống kê ({@code pg_stat_user_tables}), không phải {@code count(*)}.
 *
 * @param totalBytes dữ liệu + index + TOAST
 * @param tableBytes phần dữ liệu chính, không gồm index và TOAST
 * @param deadRows row chết chờ VACUUM dọn — mỗi UPDATE / DELETE để lại một
 * @param deadPct {@code deadRows / (liveRows + deadRows)} theo phần trăm, 2 chữ số; {@code null}
 *     khi cả hai bằng 0
 * @param modifiedSinceAnalyze row thêm / sửa / xoá từ lần ANALYZE gần nhất — vượt ngưỡng thì
 *     autovacuum chạy ANALYZE
 * @param lastVacuum VACUUM chạy tay gần nhất; {@code lastAutovacuum} của autovacuum. {@code null} =
 *     chưa lần nào từ lúc reset thống kê
 */
public record TableStatsResponse(
    String tableName,
    long totalBytes,
    String totalSize,
    long tableBytes,
    String tableSize,
    long indexesBytes,
    String indexesSize,
    long liveRows,
    long deadRows,
    BigDecimal deadPct,
    long modifiedSinceAnalyze,
    long seqScans,
    long indexScans,
    Instant lastVacuum,
    Instant lastAutovacuum,
    Instant lastAnalyze,
    Instant lastAutoanalyze) {}
