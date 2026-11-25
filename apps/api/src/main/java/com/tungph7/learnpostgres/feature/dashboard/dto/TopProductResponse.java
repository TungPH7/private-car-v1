package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Một sản phẩm trong top bán chạy — xếp theo số lượng bán, hòa thì theo doanh số.
 *
 * <p>{@code productName} / {@code sku} là giá trị hiện tại của sản phẩm, không phải bản chụp trên
 * {@code order_items}: gộp theo sản phẩm, đổi tên không tách thành hai dòng.
 *
 * @param revenue {@code SUM(line_total)} — trước giảm giá của đơn
 * @param orderCount số đơn có sản phẩm này
 */
public record TopProductResponse(
    UUID productId,
    String productName,
    String sku,
    long unitsSold,
    BigDecimal revenue,
    long orderCount) {}
