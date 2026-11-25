package com.tungph7.learnpostgres.feature.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;

/**
 * Admin chuyển trạng thái đơn.
 *
 * <p>{@code to = CANCELLED} đi đúng luồng hủy (nhả hàng đã giữ, hoàn lượt coupon), không phải chỉ
 * đổi cột status. {@code to = EXPIRED} bị từ chối: trạng thái đó chỉ job hết hạn thanh toán được
 * đặt.
 */
public record TransitionStatusRequest(@NotNull OrderStatus to, @Size(max = 255) String reason) {}
