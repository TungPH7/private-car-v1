package com.tungph7.learnpostgres.feature.inventory.dto;

import java.util.UUID;

/** Một dòng báo cáo low-stock — kèm tên/sku để admin không phải tra thêm. */
public record LowStockResponse(
    UUID productId,
    String productName,
    String sku,
    int onHand,
    int reserved,
    int available,
    Integer reorderPoint) {}
