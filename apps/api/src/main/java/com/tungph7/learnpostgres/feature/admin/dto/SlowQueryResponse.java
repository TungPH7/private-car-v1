package com.tungph7.learnpostgres.feature.admin.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một câu trong {@code pg_stat_statements}: mọi lần chạy của cùng một câu đã chuẩn hoá (hằng số
 * thay bằng {@code $1}, {@code $2}...), cộng dồn từ lần reset gần nhất. Thời gian là thời gian thực
 * thi, không gồm lập plan; đơn vị ms, làm tròn 2 chữ số.
 *
 * <p>{@code queryId} ra JSON dạng <b>chuỗi</b>: mã là số 64 bit, gần như luôn vượt 2^53 — {@code
 * JSON.parse} của JavaScript (Swagger UI, frontend) đọc thành {@code number} chỉ giữ được 15–16 chữ
 * số đầu ({@code 4028545980519127792} thành {@code 4028545980519127600}), mã không còn trỏ đúng câu
 * nào. Cùng cách Google API và protobuf JSON ghi số nguyên 64 bit. Trong Java vẫn là {@code long}.
 *
 * @param queryId mã câu do Postgres tính — cùng câu thì cùng mã
 * @param pctOfTotal phần trăm trong tổng thời gian thực thi của mọi câu được đếm (cùng database,
 *     cùng role)
 * @param rows tổng số row trả về hoặc bị ghi
 * @param cacheHitPct phần trăm trang đọc thấy sẵn trong shared buffers; {@code null} khi câu không
 *     đọc trang nào
 */
public record SlowQueryResponse(
    @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(types = "string", format = "int64", example = "4028545980519127792")
        long queryId,
    String query,
    long calls,
    BigDecimal totalMs,
    BigDecimal meanMs,
    BigDecimal maxMs,
    BigDecimal pctOfTotal,
    long rows,
    BigDecimal cacheHitPct) {}
