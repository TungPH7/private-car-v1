package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Một category gốc (không có cha) trong top doanh số — cộng cả hàng của mọi category con cháu.
 *
 * <p>Sản phẩm chưa phân loại không thuộc category gốc nào nên không có trong danh sách.
 *
 * @param revenueRank {@code RANK()} theo doanh số: bằng nhau thì cùng hạng, hạng sau nhảy cóc
 * @param orderCount số đơn có hàng thuộc cây category này
 * @param revenue {@code SUM(line_total)} — trước giảm giá của đơn
 */
public record TopCategoryResponse(
    long revenueRank,
    UUID categoryId,
    String categoryName,
    long orderCount,
    long unitsSold,
    BigDecimal revenue) {}
