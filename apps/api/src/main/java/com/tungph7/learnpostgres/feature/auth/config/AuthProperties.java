package com.tungph7.learnpostgres.feature.auth.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình đăng nhập ({@code app.auth.*}, docs/adr/0001-api-authentication.md).
 *
 * @param issuer claim {@code iss} của access token; decoder từ chối token của issuer khác
 * @param accessTokenTtl thời hạn access token (JWT): ngắn, vì không thu hồi được trước hạn
 * @param refreshTokenTtl thời hạn refresh token: dài, thu hồi được (lưu SHA-256 trong DB)
 * @param jwt khóa ký access token
 * @param bootstrapAdmin tài khoản quản trị đầu tiên, tạo lúc khởi động nếu chưa có
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
    @NotBlank String issuer,
    @NotNull Duration accessTokenTtl,
    @NotNull Duration refreshTokenTtl,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull BootstrapAdmin bootstrapAdmin) {

  /**
   * @param secret khóa HMAC của HS256, ít nhất 32 byte ({@code APP_AUTH_JWT_SECRET}); rỗng thì xem
   *     {@link SigningKeys#of}
   */
  public record Jwt(String secret) {}

  /**
   * @param email email của tài khoản quản trị đầu tiên ({@code APP_AUTH_BOOTSTRAP_ADMIN_EMAIL})
   * @param password mật khẩu của nó ({@code APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD}); cả hai rỗng thì
   *     không tạo
   */
  public record BootstrapAdmin(String email, String password) {

    public boolean configured() {
      return email != null && !email.isBlank() && password != null && !password.isEmpty();
    }
  }
}
