package com.tungph7.learnpostgres.feature.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterCustomerRequest(
    @NotBlank @Email @Size(max = 255) @Schema(example = "john@example.com") String email,
    @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$", message = "must be E.164 format")
        @Schema(example = "+84901234567")
        String phone,
    @NotBlank @Size(max = 80) @Schema(example = "John") String firstName,
    @NotBlank @Size(max = 80) @Schema(example = "Doe") String lastName) {}
