package com.tungph7.learnpostgres.feature.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.UnauthorizedException;
import com.tungph7.learnpostgres.feature.auth.domain.RefreshToken;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.TokenResponse;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

/**
 * Đăng nhập, làm mới và thu hồi token (docs/adr/0001-api-authentication.md).
 *
 * <ul>
 *   <li>Đăng nhập sai email hay sai mật khẩu ra cùng một lỗi, và cùng một lần băm BCrypt: không lộ
 *       email nào có tài khoản, kể cả qua thời gian trả lời.
 *   <li>Refresh token dùng một lần. Token đã thu hồi mà bị dùng lại là dấu hiệu bị lộ: mọi token
 *       còn sống của tài khoản bị thu hồi, người dùng đăng nhập lại.
 * </ul>
 */
@Service
public class AuthService {

  private final UserAccountRepository users;
  private final RefreshTokenRepository refreshTokens;
  private final TokenService tokens;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  /** Hash của một chuỗi ngẫu nhiên, để email không có cũng tốn một lần BCrypt. */
  private final String dummyHash;

  public AuthService(
      UserAccountRepository users,
      RefreshTokenRepository refreshTokens,
      TokenService tokens,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.tokens = tokens;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public TokenResponse login(String email, String password) {
    Optional<UserAccount> found = users.findByEmail(email.trim());
    if (found.isEmpty()) {
      passwordEncoder.matches(password, dummyHash);
      throw invalidCredentials();
    }
    UserAccount user = found.get();
    if (!passwordEncoder.matches(password, user.getPasswordHash()) || !user.isEnabled()) {
      throw invalidCredentials();
    }
    return tokens.issue(user);
  }

  /**
   * Đổi một refresh token lấy cặp mới. {@code noRollbackFor}: khi token bị dùng lại, việc thu hồi
   * mọi token phải được ghi dù request trả 401.
   */
  @Transactional(noRollbackFor = UnauthorizedException.class)
  public TokenResponse refresh(String refreshToken) {
    Instant now = clock.instant();
    RefreshToken stored =
        refreshTokens
            .findByTokenHash(TokenService.hash(refreshToken))
            .orElseThrow(AuthService::invalidRefreshToken);
    if (stored.isRevoked()) {
      refreshTokens.revokeAllActive(stored.getUserId(), now);
      throw invalidRefreshToken();
    }
    if (stored.isExpired(now)) {
      throw invalidRefreshToken();
    }
    UserAccount user =
        users
            .findById(stored.getUserId())
            .filter(UserAccount::isEnabled)
            .orElseThrow(AuthService::invalidRefreshToken);
    stored.revoke(now);
    return tokens.issue(user);
  }

  /** Thu hồi một refresh token (đăng xuất). Token không có: không làm gì, không báo lỗi. */
  @Transactional
  public void revoke(String refreshToken) {
    refreshTokens
        .findByTokenHash(TokenService.hash(refreshToken))
        .ifPresent(stored -> stored.revoke(clock.instant()));
  }

  private static UnauthorizedException invalidCredentials() {
    return new UnauthorizedException(
        ErrorCode.AUTH_INVALID_CREDENTIALS, "The email or the password is wrong.");
  }

  private static UnauthorizedException invalidRefreshToken() {
    return new UnauthorizedException(
        ErrorCode.AUTH_INVALID_REFRESH_TOKEN,
        "The refresh token is unknown, expired or revoked: sign in again.");
  }
}
