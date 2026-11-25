package com.tungph7.learnpostgres.feature.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
    @NotNull Long version,
    @NotBlank @Size(max = 160) String name,
    @NotBlank @Size(max = 160) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
    @Size(max = 4000) String description,
    @PositiveOrZero Integer sortOrder) {}
