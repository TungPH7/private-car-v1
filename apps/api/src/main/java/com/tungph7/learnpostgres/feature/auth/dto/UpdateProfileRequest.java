package com.tungph7.learnpostgres.feature.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.URL;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body của {@code PUT /api/users/me}: thay ba trường hồ sơ. Email và vai trò không đổi ở đây. */
public record UpdateProfileRequest(
    @NotBlank @Size(max = 120) @Schema(example = "Quản trị") String name,
    @Pattern(
            regexp = "^\\+?[0-9 ().-]{6,31}$",
            message = "must be digits, spaces, dots, dashes or brackets, with an optional +")
        @Schema(nullable = true, example = "+84901234567")
        String phoneNumber,
    @Size(max = 2048)
        @URL(protocol = "https", message = "must be an https URL")
        @Schema(nullable = true, example = "https://example.com/avatar.png")
        String avatar) {}
