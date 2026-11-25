package com.tungph7.learnpostgres.feature.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/** Trạng thái tồn kho của một product. {@code available} = onHand - reserved (không lưu DB). */
public record InventoryResponse(
    UUID productId,
    int onHand,
    int reserved,
    int available,
    long sold,
    Integer reorderPoint,
    Instant lastRestockAt) {}
