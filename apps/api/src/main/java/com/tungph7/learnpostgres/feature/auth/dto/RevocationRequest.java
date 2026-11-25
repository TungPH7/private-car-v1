package com.tungph7.learnpostgres.feature.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body của {@code POST /api/auth/revocations}: refresh token cần thu hồi (đăng xuất). */
public record RevocationRequest(@NotBlank @Size(max = 128) String refreshToken) {

  @Override
  public String toString() {
    return "RevocationRequest[]";
  }
}
