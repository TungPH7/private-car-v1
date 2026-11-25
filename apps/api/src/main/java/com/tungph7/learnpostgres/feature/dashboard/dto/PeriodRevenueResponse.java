package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Doanh thu của một kỳ — ngày, tuần (thứ Hai) hoặc tháng, theo lịch của cửa hàng.
 *
 * <p>Cả ba độ chia đọc từ {@code app.mv_daily_revenue} bằng cùng một câu SQL, nên cộng các ngày
 * luôn ra đúng tuần / tháng. Kỳ không có đơn vẫn có row, mọi số = 0.
 *
 * @param periodStart ngày đầu kỳ. Kỳ ở hai đầu khoảng có thể bắt đầu trước {@code from} (tuần,
 *     tháng) nhưng chỉ cộng các ngày nằm trong khoảng được hỏi
 * @param orderCount mọi đơn đặt trong kỳ, kể cả PENDING / CANCELLED / EXPIRED
 * @param completedCount đơn được tính doanh thu (PAID / SHIPPED / DELIVERED)
 * @param netRevenue {@code grossSubtotal - totalDiscount + totalShipping} của đơn được tính — chưa
 *     trừ hoàn tiền; sau hoàn tiền = {@code netRevenue - refundedAmount}
 * @param refundedAmount refund COMPLETED của các đơn được tính, theo ngày đặt đơn
 * @param avgOrderValue {@code netRevenue / completedCount}, làm tròn 2 chữ số; {@code null} khi kỳ
 *     không có đơn được tính
 */
public record PeriodRevenueResponse(
    LocalDate periodStart,
    long orderCount,
    long completedCount,
    BigDecimal grossSubtotal,
    BigDecimal totalDiscount,
    BigDecimal totalShipping,
    BigDecimal netRevenue,
    BigDecimal refundedAmount,
    BigDecimal avgOrderValue) {}
