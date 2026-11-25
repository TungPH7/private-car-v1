package com.tungph7.learnpostgres.feature.coupon.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Coupon trả về cho admin API.
 *
 * <p>{@code validFrom} / {@code validTo} phẳng ra 2 field thay vì lộ {@code TstzRange}: client
 * không cần biết DB lưu bằng một kiểu range, và đổi mapping sau này không phá hợp đồng API.
 *
 * <p>{@code type} là {@code String} chứ không phải enum để JSON ổn định khi enum được thêm hằng mới
 * — xem mapper ở bài 04.
 */
public record CouponResponse(
    UUID id,
    String code,
    String description,
    String type,
    BigDecimal value,
    BigDecimal maxDiscount,
    BigDecimal minOrderAmount,
    Integer maxUses,
    Integer maxUsesPerCustomer,
    int usedCount,
    boolean active,
    OffsetDateTime validFrom,
    OffsetDateTime validTo,
    Instant createdAt,
    long version) {}
