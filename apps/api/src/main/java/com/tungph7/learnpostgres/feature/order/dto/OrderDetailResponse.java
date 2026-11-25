package com.tungph7.learnpostgres.feature.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Toàn bộ một đơn, dựng từ đúng 1 query ({@code OrderRepository.findDetailById}): header, shipping
 * snapshot, customer, coupon, items và lịch sử trạng thái.
 *
 * <p>{@code items} / {@code history} là list có kiểu, không phải {@code List<Map<String, Object>>}.
 * Parse JSON vào {@code Map} thì Jackson đọc số thập phân thành {@code Double}: {@code 0.30} ra
 * {@code 0.3} — tiền mất scale, và mọi phép tính sau đó chạy trên số nhị phân gần đúng.
 *
 * <p>{@code couponId} / {@code couponCode} là {@code null} khi đơn không dùng coupon.
 */
public record OrderDetailResponse(
    UUID id,
    String orderNumber,
    String status,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal shippingFee,
    BigDecimal total,
    String shippingRecipient,
    String shippingPhone,
    String shippingLine1,
    String shippingLine2,
    String shippingCity,
    String shippingCountry,
    Instant createdAt,
    UUID customerId,
    String customerEmail,
    String customerName,
    UUID couponId,
    String couponCode,
    List<OrderItemResponse> items,
    List<StatusHistoryResponse> history) {}
