package com.tungph7.learnpostgres.feature.product.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Tài liệu OpenAPI chung của các endpoint ghi sản phẩm: cần access token, và quyền product:* mà
 * {@code SecurityConfig} kiểm theo method và path.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "accessToken")
@ApiResponse(
    responseCode = "401",
    description = "No access token, or an invalid one",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
@ApiResponse(
    responseCode = "403",
    description = "The account lacks the product permission",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
@interface ProductWrite {}
