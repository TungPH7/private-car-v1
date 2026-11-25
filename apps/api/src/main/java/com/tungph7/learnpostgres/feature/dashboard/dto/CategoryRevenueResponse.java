package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Doanh số hàng của một category trong một tháng — chỉ các ô có bán, không lấp ô trống.
 *
 * <p>Category là category <b>hiện tại</b> của sản phẩm ({@code order_items} không chụp lại category
 * lúc đặt), và là category trực tiếp — không cộng dồn lên category cha (cộng dồn: {@link
 * TopCategoryResponse}).
 *
 * @param month ngày 1 của tháng, theo lịch của cửa hàng
 * @param categoryId {@code null} = sản phẩm chưa phân loại
 * @param categoryName {@code null} cùng lúc với {@code categoryId}
 * @param orderCount số đơn có hàng của category này. Một đơn có hàng ở 2 category được đếm ở cả 2
 *     row — cộng các row không ra số đơn của tháng
 * @param grossRevenue {@code SUM(line_total)} — trước giảm giá: giảm giá tính trên cả đơn, không
 *     gắn với dòng hàng nào
 */
public record CategoryRevenueResponse(
    LocalDate month,
    UUID categoryId,
    String categoryName,
    long orderCount,
    long unitsSold,
    BigDecimal grossRevenue) {}
