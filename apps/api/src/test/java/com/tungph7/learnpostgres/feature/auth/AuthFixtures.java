package com.tungph7.learnpostgres.feature.auth;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.feature.auth.config.AuthProperties;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

/** Dữ liệu dùng chung cho test của đăng nhập. */
public final class AuthFixtures {

  public static final String ISSUER = "test-issuer";
  public static final SecretKey KEY =
      new SecretKeySpec("t".repeat(32).getBytes(StandardCharsets.UTF_8), "HmacSHA256");

  private AuthFixtures() {}

  public static AuthProperties props() {
    return new AuthProperties(
        ISSUER,
        Duration.ofMinutes(15),
        Duration.ofDays(7),
        new AuthProperties.Jwt(null),
        new AuthProperties.BootstrapAdmin(null, null));
  }

  /** Một tài khoản đã có id, như vừa đọc từ DB. */
  public static UserAccount user(UUID id, String role, String passwordHash) {
    UserAccount user = new UserAccount("user@example.com", passwordHash, "User", role);
    setId(user, id);
    return user;
  }

  /** Lưu một tài khoản thật vào DB (test tích hợp), mật khẩu băm như lúc chạy. */
  public static UserAccount saveUser(
      UserAccountRepository users,
      PasswordEncoder passwordEncoder,
      String email,
      String password,
      String role) {
    return users.save(new UserAccount(email, passwordEncoder.encode(password), "User", role));
  }

  public static void setId(BaseEntity entity, UUID id) {
    try {
      Field f = BaseEntity.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
