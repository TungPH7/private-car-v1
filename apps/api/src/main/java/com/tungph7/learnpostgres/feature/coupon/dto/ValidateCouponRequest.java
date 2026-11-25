package com.tungph7.learnpostgres.feature.coupon.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Kiểm tra coupon trước khi đặt hàng — FE gọi khi khách gõ mã ở giỏ hàng.
 *
 * <p>{@code customerId} bắt buộc vì {@code max_uses_per_customer} chỉ tính được khi biết khách là
 * ai. Không có customer thì không trả lời được câu hỏi "mã này bạn còn dùng được không".
 */
public record ValidateCouponRequest(
    @NotBlank @Size(max = 40) String code,
    @NotNull @Positive BigDecimal orderSubtotal,
    @NotNull UUID customerId) {}
