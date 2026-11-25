package com.tungph7.learnpostgres.feature.order.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Một dòng hàng khách muốn đặt.
 *
 * <p>Không có giá: đơn giá do server đọc từ {@code app.products} lúc đặt rồi chụp vào {@code
 * order_items}. Nhận giá từ client là để client tự định giá.
 */
public record OrderItemRequest(@NotNull UUID productId, @Min(1) int quantity) {}
