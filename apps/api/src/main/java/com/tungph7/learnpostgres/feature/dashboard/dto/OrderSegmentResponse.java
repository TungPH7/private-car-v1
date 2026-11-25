package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;

/**
 * Thống kê giá trị đơn của một phân khúc ({@code order_segment} của {@code app.v_completed_orders}:
 * SMALL / MEDIUM / LARGE / VIP). Chỉ có row cho phân khúc có đơn.
 *
 * @param avgValue làm tròn 2 chữ số
 * @param median trung vị nội suy ({@code percentile_cont(0.5)}), làm tròn 2 chữ số
 * @param p95 phân vị 95 nội suy, làm tròn 2 chữ số
 */
public record OrderSegmentResponse(
    String segment,
    long orderCount,
    BigDecimal avgValue,
    BigDecimal minValue,
    BigDecimal maxValue,
    BigDecimal totalValue,
    BigDecimal median,
    BigDecimal p95) {}
