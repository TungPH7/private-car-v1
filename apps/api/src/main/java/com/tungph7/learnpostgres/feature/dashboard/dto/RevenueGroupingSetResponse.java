package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Một row của báo cáo doanh số nhiều cấp (GROUPING SETS): chi tiết tháng × category, tổng theo
 * tháng, tổng theo category, tổng cộng.
 *
 * <p>Loại row đọc từ {@code groupingLevel} — không đọc từ {@code null}: {@code categoryId = null}
 * vừa có thể là row đã gộp mọi category, vừa có thể là sản phẩm chưa phân loại.
 *
 * <ul>
 *   <li>{@code 0} — chi tiết: {@code month} + category (category có thể {@code null} = chưa phân
 *       loại)
 *   <li>{@code 1} — tổng của tháng, mọi category
 *   <li>{@code 2} — tổng của category, mọi tháng
 *   <li>{@code 3} — tổng cộng
 * </ul>
 *
 * <p>Khoảng không có đơn: đúng một row — cấp 3, doanh số {@code 0.00}, 0 đơn.
 *
 * @param orderCount số đơn khác nhau trong row. Không cộng dồn được: đơn có hàng ở 2 category nằm ở
 *     2 row chi tiết nhưng chỉ 1 lần trong row tổng của tháng
 * @param groupingLevel {@code GROUPING(month, category_id)} — bit 1: đã gộp tháng, bit 0: đã gộp
 *     category
 */
public record RevenueGroupingSetResponse(
    LocalDate month,
    UUID categoryId,
    String categoryName,
    BigDecimal revenue,
    long orderCount,
    int groupingLevel) {}
