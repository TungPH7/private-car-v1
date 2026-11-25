package com.tungph7.learnpostgres.feature.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
    UUID id,
    String email,
    String phone,
    String firstName,
    String lastName,
    String status,
    boolean emailVerified,
    Instant lastLoginAt,
    Instant createdAt,
    long version) {}
