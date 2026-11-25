package com.tungph7.learnpostgres.feature.inventory.dto;

import java.time.Instant;
import java.util.UUID;

/** Một dòng trong movement log (append-only). */
public record MovementResponse(
    UUID id,
    UUID productId,
    String movementType,
    int deltaOnHand,
    int deltaReserved,
    int deltaSold,
    int onHandAfter,
    int reservedAfter,
    String reason,
    UUID orderId,
    String actor,
    Instant occurredAt) {}
