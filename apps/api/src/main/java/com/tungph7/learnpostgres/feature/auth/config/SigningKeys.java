package com.tungph7.learnpostgres.feature.auth.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Khóa HMAC-SHA256 ký access token. Có {@code APP_AUTH_JWT_SECRET} thì dùng nó; thiếu thì profile
 * {@code prod} không khởi động, còn các profile khác (máy dev, test, Development và Staging trên
 * VPS) sinh một khóa ngẫu nhiên sống bằng tiến trình: access token cũ hết dùng được sau mỗi lần
 * khởi động lại, client làm mới bằng refresh token (lưu trong DB, không phụ thuộc khóa này).
 */
public final class SigningKeys {

  /** HS256 cần khóa ít nhất bằng độ dài đầu ra của SHA-256 (RFC 7518 § 3.2). */
  static final int MIN_BYTES = 32;

  private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
  private static final String ALGORITHM = "HmacSHA256";

  private SigningKeys() {}

  /**
   * @param secret giá trị của {@code app.auth.jwt.secret}, có thể null hoặc rỗng
   * @param production profile {@code prod} đang bật
   */
  public static SecretKey of(String secret, boolean production) {
    if (secret != null && !secret.isBlank()) {
      byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
      if (bytes.length < MIN_BYTES) {
        throw new IllegalStateException(
            "APP_AUTH_JWT_SECRET must be at least %d bytes, it has %d."
                .formatted(MIN_BYTES, bytes.length));
      }
      return new SecretKeySpec(bytes, ALGORITHM);
    }
    if (production) {
      throw new IllegalStateException(
          "APP_AUTH_JWT_SECRET is required in the prod profile: set it in the secrets file of the"
              + " environment.");
    }
    log.warn(
        "APP_AUTH_JWT_SECRET is not set: access tokens are signed with a random key that lives as"
            + " long as this process, so they stop working at the next restart.");
    byte[] random = new byte[MIN_BYTES];
    new SecureRandom().nextBytes(random);
    return new SecretKeySpec(random, ALGORITHM);
  }
}
