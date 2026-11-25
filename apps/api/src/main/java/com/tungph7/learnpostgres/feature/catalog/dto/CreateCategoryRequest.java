package com.tungph7.learnpostgres.feature.catalog.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
    UUID parentId,
    @NotBlank @Size(max = 160) String name,
    @NotBlank
        @Size(max = 160)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "lowercase alphanumeric and hyphens only")
        String slug,
    @Size(max = 4000) String description,
    @PositiveOrZero Integer sortOrder) {}
