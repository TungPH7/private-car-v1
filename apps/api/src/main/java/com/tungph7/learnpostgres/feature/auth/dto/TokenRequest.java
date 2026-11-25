package com.tungph7.learnpostgres.feature.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body của {@code POST /api/auth/tokens}: {@code grantType} {@code password} cần {@code email} và
 * {@code password}; {@code refresh_token} cần {@code refreshToken}.
 */
public record TokenRequest(
    @NotNull @Schema(example = "password") GrantType grantType,
    @Email @Size(max = 254) @Schema(example = "admin@example.com", nullable = true) String email,
    @Size(max = 72) @Schema(nullable = true) String password,
    @Size(max = 128) @Schema(nullable = true) String refreshToken) {

  @JsonIgnore
  @Schema(hidden = true)
  @AssertTrue(message = "email and password are required when grantType is password")
  public boolean isPasswordGrantComplete() {
    return grantType != GrantType.PASSWORD
        || (email != null && !email.isBlank() && password != null && !password.isEmpty());
  }

  @JsonIgnore
  @Schema(hidden = true)
  @AssertTrue(message = "refreshToken is required when grantType is refresh_token")
  public boolean isRefreshGrantComplete() {
    return grantType != GrantType.REFRESH_TOKEN
        || (refreshToken != null && !refreshToken.isBlank());
  }

  /** Không bao giờ in mật khẩu hay token vào log. */
  @Override
  public String toString() {
    return "TokenRequest[grantType=%s]".formatted(grantType);
  }
}
