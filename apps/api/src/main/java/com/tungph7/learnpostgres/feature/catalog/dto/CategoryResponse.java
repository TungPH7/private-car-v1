package com.tungph7.learnpostgres.feature.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
    UUID id,
    UUID parentId,
    String name,
    String slug,
    String description,
    boolean active,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
