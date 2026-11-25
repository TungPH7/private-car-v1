package com.tungph7.learnpostgres.feature.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerProfileResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String phone,
    String status,
    Instant createdAt,
    long ordersCount,
    BigDecimal totalSpent,
    Instant lastOrderAt) {}
