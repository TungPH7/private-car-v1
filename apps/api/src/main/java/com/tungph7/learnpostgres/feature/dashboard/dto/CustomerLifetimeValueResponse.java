package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Giá trị trọn đời của một khách — mọi đơn được tính doanh thu, từ trước tới nay.
 *
 * <p>Khách chưa có đơn được tính vẫn có row: số đơn 0, tổng 0, các field còn lại {@code null}.
 *
 * @param totalSpent {@code SUM(total)} — cùng định nghĩa với doanh thu, chưa trừ hoàn tiền
 * @param avgOrderValue làm tròn 2 chữ số; {@code null} khi chưa có đơn
 */
public record CustomerLifetimeValueResponse(
    UUID customerId,
    String email,
    long totalOrders,
    BigDecimal totalSpent,
    BigDecimal avgOrderValue,
    Instant firstOrderAt,
    Instant lastOrderAt) {}
