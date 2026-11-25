package com.tungph7.learnpostgres.feature.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Hủy đơn. Lý do bắt buộc — nó được ghi vào {@code orders.cancel_reason} và lịch sử trạng thái (cả
 * hai {@code VARCHAR(255)}).
 */
public record CancelOrderRequest(@NotBlank @Size(max = 255) String reason) {}
