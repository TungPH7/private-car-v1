package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một khách trong top chi tiêu của khoảng thời gian — chỉ tính đơn được tính doanh thu.
 *
 * @param orderCount số đơn được tính của khách trong khoảng
 * @param totalSpent {@code SUM(total)} — cùng định nghĩa với doanh thu, chưa trừ hoàn tiền
 * @param avgOrderValue làm tròn 2 chữ số
 * @param lastOrderAt thời điểm đặt đơn được tính gần nhất trong khoảng
 */
public record TopCustomerResponse(
    UUID customerId,
    String email,
    String fullName,
    long orderCount,
    BigDecimal totalSpent,
    BigDecimal avgOrderValue,
    Instant lastOrderAt) {}
