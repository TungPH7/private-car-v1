package com.tungph7.learnpostgres.feature.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PUT /api/users/me/password}. Mật khẩu mới 8 đến 72 ký tự: BCrypt chỉ đọc 72 byte
 * đầu, nên service còn kiểm số byte UTF-8.
 */
public record ChangePasswordRequest(
    @NotEmpty @Size(max = 72) String currentPassword,
    @NotEmpty @Size(min = 8, max = 72) String newPassword) {

  @Override
  public String toString() {
    return "ChangePasswordRequest[]";
  }
}
