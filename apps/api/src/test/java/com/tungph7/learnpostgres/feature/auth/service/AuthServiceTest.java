package com.tungph7.learnpostgres.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.UnauthorizedException;
import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.domain.RefreshToken;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.TokenResponse;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

  private static final UUID USER_ID = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead20");
  private static final Instant NOW = Instant.parse("2026-11-26T01:00:00Z");
  private static final TokenResponse ISSUED = new TokenResponse("access", "refresh", "Bearer", 900);

  @Mock UserAccountRepository users;
  @Mock RefreshTokenRepository refreshTokens;
  @Mock TokenService tokens;
  @Mock PasswordEncoder passwordEncoder;

  private AuthService service;
  private UserAccount user;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$dummy");
    service =
        new AuthService(
            users, refreshTokens, tokens, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    user = AuthFixtures.user(USER_ID, "ADMIN", "$2a$10$real");
    when(tokens.issue(any(UserAccount.class))).thenReturn(ISSUED);
  }

  // ---- login -------------------------------------------------------

  @Test
  void login_with_the_right_password_issues_tokens() {
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret-pass", "$2a$10$real")).thenReturn(true);

    assertThat(service.login("user@example.com", "secret-pass")).isSameAs(ISSUED);
  }

  @Test
  void login_trims_the_email() {
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret-pass", "$2a$10$real")).thenReturn(true);

    assertThat(service.login("  user@example.com ", "secret-pass")).isSameAs(ISSUED);
  }

  @Test
  void login_with_a_wrong_password_is_invalid_credentials() {
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "$2a$10$real")).thenReturn(false);

    assertThatThrownBy(() -> service.login("user@example.com", "wrong"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_CREDENTIALS);
    verify(tokens, never()).issue(any());
  }

  @Test
  void login_with_an_unknown_email_is_the_same_error_after_a_password_check() {
    when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.login("nobody@example.com", "anything"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_CREDENTIALS);
    // Cùng thời gian với email có thật: vẫn băm một lần để không lộ email nào tồn tại.
    verify(passwordEncoder).matches("anything", "$2a$10$dummy");
  }

  @Test
  void login_of_a_disabled_account_is_invalid_credentials() {
    user.disable();
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("secret-pass", "$2a$10$real")).thenReturn(true);

    assertThatThrownBy(() -> service.login("user@example.com", "secret-pass"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_CREDENTIALS);
  }

  // ---- refresh -----------------------------------------------------

  @Test
  void refresh_revokes_the_token_and_issues_a_new_pair() {
    RefreshToken stored = new RefreshToken(USER_ID, TokenService.hash("r1"), NOW.plusSeconds(60));
    when(refreshTokens.findByTokenHash(TokenService.hash("r1"))).thenReturn(Optional.of(stored));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));

    assertThat(service.refresh("r1")).isSameAs(ISSUED);
    assertThat(stored.getRevokedAt()).isEqualTo(NOW);
  }

  @Test
  void refresh_with_an_unknown_token_is_invalid() {
    when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.refresh("nope"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
  }

  @Test
  void refresh_with_an_expired_token_is_invalid() {
    RefreshToken stored = new RefreshToken(USER_ID, TokenService.hash("r1"), NOW);
    when(refreshTokens.findByTokenHash(TokenService.hash("r1"))).thenReturn(Optional.of(stored));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.refresh("r1"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    verify(tokens, never()).issue(any());
  }

  @Test
  void a_revoked_token_used_again_revokes_every_token_of_the_account() {
    RefreshToken stored = new RefreshToken(USER_ID, TokenService.hash("r1"), NOW.plusSeconds(60));
    stored.revoke(NOW.minusSeconds(5));
    when(refreshTokens.findByTokenHash(TokenService.hash("r1"))).thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> service.refresh("r1"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    verify(refreshTokens).revokeAllActive(USER_ID, NOW);
    verify(tokens, never()).issue(any());
  }

  @Test
  void refresh_for_a_disabled_account_is_invalid() {
    user.disable();
    RefreshToken stored = new RefreshToken(USER_ID, TokenService.hash("r1"), NOW.plusSeconds(60));
    when(refreshTokens.findByTokenHash(TokenService.hash("r1"))).thenReturn(Optional.of(stored));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.refresh("r1"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    verify(tokens, never()).issue(any());
  }

  // ---- revoke ------------------------------------------------------

  @Test
  void revoke_marks_a_live_token() {
    RefreshToken stored = new RefreshToken(USER_ID, TokenService.hash("r1"), NOW.plusSeconds(60));
    when(refreshTokens.findByTokenHash(TokenService.hash("r1"))).thenReturn(Optional.of(stored));

    service.revoke("r1");

    assertThat(stored.getRevokedAt()).isEqualTo(NOW);
  }

  @Test
  void revoke_of_an_unknown_token_does_nothing() {
    when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

    service.revoke("nope");

    verify(refreshTokens, never()).revokeAllActive(eq(USER_ID), any());
  }
}
