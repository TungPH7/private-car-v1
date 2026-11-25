package com.tungph7.learnpostgres.feature.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Một dòng hàng của đơn. Tên, sku, đơn giá là snapshot lúc đặt — không đổi theo product hiện tại.
 *
 * <p>Tên field trùng khóa JSON mà {@code OrderRepository.findDetailById} dựng bằng {@code
 * json_build_object} — service parse thẳng mảng JSON đó thành list record này.
 */
public record OrderItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String productSku,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal) {}
