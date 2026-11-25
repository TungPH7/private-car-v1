package com.tungph7.learnpostgres.feature.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nhập kho. {@code quantity} luôn dương — muốn giảm thì dùng {@link AdjustRequest}. */
public record StockInRequest(
    @Min(1) int quantity,
    @NotBlank @Size(max = 255) String reason,
    @NotBlank @Size(max = 64) String actor) {}
