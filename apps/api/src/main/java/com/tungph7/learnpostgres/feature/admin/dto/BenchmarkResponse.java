package com.tungph7.learnpostgres.feature.admin.dto;

import java.math.BigDecimal;

import com.tungph7.learnpostgres.feature.search.domain.MatchMode;

/**
 * Kết quả benchmark tìm sản phẩm: thời gian của từng lần gọi, đo bằng đồng hồ đơn điệu ({@code
 * System.nanoTime}), sau {@code warmUp} lần chạy bỏ. Đơn vị ms, 2 chữ số lẻ.
 *
 * <p>Percentile theo nearest-rank: p-th = lần đo thứ ⌈p × iterations / 100⌉ của dãy đã sắp tăng dần
 * — luôn là một lần đo có thật, không nội suy. 100 lần đo: p95 là lần thứ 95, p99 lần thứ 99.
 *
 * @param warmUp số lần chạy khởi động đã chạy (không tính vào số đo)
 * @param requestedIterations số lần đo được yêu cầu
 * @param iterations số lần đo thật — nhỏ hơn {@code requestedIterations} khi benchmark chạm giới
 *     hạn thời gian
 * @param matchMode mức so tên của lần chạy cuối ({@code null} khi không có từ khoá)
 * @param totalElements số sản phẩm khớp của lần chạy cuối
 */
public record BenchmarkResponse(
    int warmUp,
    int requestedIterations,
    int iterations,
    BigDecimal minMs,
    BigDecimal p50Ms,
    BigDecimal p95Ms,
    BigDecimal p99Ms,
    BigDecimal maxMs,
    BigDecimal meanMs,
    MatchMode matchMode,
    long totalElements) {}
