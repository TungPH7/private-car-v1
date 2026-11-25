package com.tungph7.learnpostgres.feature.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tóm tắt một đơn — trả từ place / get / transition / list.
 *
 * <p>{@code status} là {@code String} chứ không phải enum, cùng lý do {@code CouponResponse.type}:
 * JSON ổn định khi enum thêm hằng mới. Không có {@code internalNote}: ghi chú nội bộ không ra API.
 * Items, shipping snapshot và lịch sử trạng thái nằm ở {@link OrderDetailResponse}.
 */
public record OrderResponse(
    UUID id,
    String orderNumber,
    String status,
    UUID customerId,
    UUID shippingAddressId,
    UUID couponId,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal shippingFee,
    BigDecimal total,
    String customerNote,
    String cancelReason,
    Instant paidAt,
    Instant shippedAt,
    Instant deliveredAt,
    Instant cancelledAt,
    Instant createdAt,
    long version) {}
