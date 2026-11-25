package com.tungph7.learnpostgres.feature.admin.dto;

import java.time.Instant;

import com.tungph7.learnpostgres.feature.admin.domain.IndexUsage;

/**
 * Mức dùng của một index của schema {@code app} ({@code pg_stat_user_indexes}), đếm từ lần reset
 * thống kê gần nhất.
 *
 * @param scans số lần index được quét — mọi câu đọc qua nó, kể cả lookup của khóa ngoại
 * @param lastScanAt lần quét gần nhất; {@code null} = chưa lần nào từ lúc reset
 * @param tuplesRead số entry đọc từ index
 * @param tuplesFetched số row lấy từ bảng qua Index Scan (Bitmap Scan không tính vào đây)
 * @param size {@code sizeBytes} dạng dễ đọc
 * @param enforcesConstraint index UNIQUE (kể cả khóa chính) hoặc EXCLUDE — giữ ràng buộc, nên không
 *     bỏ được dù {@code scans = 0}
 */
public record IndexUsageResponse(
    String tableName,
    String indexName,
    long scans,
    Instant lastScanAt,
    long tuplesRead,
    long tuplesFetched,
    long sizeBytes,
    String size,
    boolean enforcesConstraint,
    IndexUsage usage) {}
