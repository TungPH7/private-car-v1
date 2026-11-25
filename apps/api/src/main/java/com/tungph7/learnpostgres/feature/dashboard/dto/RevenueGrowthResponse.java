package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tăng trưởng doanh thu của một tháng so với tháng trước (MoM) và cùng tháng năm trước (YoY).
 *
 * <p>So sánh chạy trên lịch tháng liền mạch: tháng không có đơn có doanh thu 0, nên tháng 1 so với
 * tháng 12 năm trước, và "năm trước" luôn đúng 12 tháng trước. Doanh thu như {@link
 * PeriodRevenueResponse} ({@code netRevenue}).
 *
 * @param month ngày 1 của tháng
 * @param pctChange phần trăm so với tháng trước, làm tròn 2 chữ số; {@code null} khi tháng trước
 *     không có doanh thu
 * @param yoyPct phần trăm so với cùng tháng năm trước, làm tròn 2 chữ số; {@code null} khi tháng đó
 *     không có doanh thu
 */
public record RevenueGrowthResponse(
    LocalDate month,
    BigDecimal revenue,
    BigDecimal prevMonthRevenue,
    BigDecimal absChange,
    BigDecimal pctChange,
    BigDecimal sameMonthLastYear,
    BigDecimal yoyPct) {}
