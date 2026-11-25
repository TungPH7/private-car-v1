package com.tungph7.learnpostgres.feature.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cặp token vừa phát.
 *
 * @param accessToken JWT HS256, gửi ở {@code Authorization: Bearer} hoặc {@code X-Access-Token}
 * @param refreshToken chuỗi ngẫu nhiên, dùng một lần để lấy cặp mới
 * @param tokenType luôn {@code Bearer}
 * @param expiresIn số giây access token còn dùng được
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    @Schema(example = "Bearer") String tokenType,
    @Schema(example = "900") long expiresIn) {

  @Override
  public String toString() {
    return "TokenResponse[tokenType=%s, expiresIn=%d]".formatted(tokenType, expiresIn);
  }
}
