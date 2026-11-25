package com.tungph7.learnpostgres.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.UnauthorizedException;
import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.domain.Permission;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.ChangePasswordRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UpdateProfileRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UserProfileResponse;
import com.tungph7.learnpostgres.feature.auth.repository.PermissionRepository;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

  private static final UUID USER_ID = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead30");
  private static final Instant NOW = Instant.parse("2026-11-26T01:00:00Z");

  @Mock UserAccountRepository users;
  @Mock PermissionRepository permissions;
  @Mock RefreshTokenRepository refreshTokens;
  @Mock PasswordEncoder passwordEncoder;

  private CurrentUserService service;
  private UserAccount user;

  @BeforeEach
  void setUp() {
    service =
        new CurrentUserService(
            users, permissions, refreshTokens, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    user = AuthFixtures.user(USER_ID, "ADMIN", "$2a$10$old");
  }

  @Test
  void profile_has_the_role_and_its_permissions() {
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(permissions.permissionsOf("ADMIN"))
        .thenReturn(
            List.of(new Permission("product:delete", "product", "DELETE", "/api/products/{id}")));

    UserProfileResponse profile = service.profile(USER_ID);

    assertThat(profile.id()).isEqualTo(USER_ID);
    assertThat(profile.email()).isEqualTo("user@example.com");
    assertThat(profile.role().name()).isEqualTo("ADMIN");
    assertThat(profile.role().permissions())
        .containsExactly(
            new UserProfileResponse.PermissionResponse(
                "product:delete", "product", "DELETE", "/api/products/{id}"));
  }

  @Test
  void profile_of_an_account_that_is_gone_is_unauthenticated() {
    when(users.findById(USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.profile(USER_ID))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void profile_of_a_disabled_account_is_unauthenticated() {
    user.disable();
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.profile(USER_ID))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void update_profile_replaces_name_phone_and_avatar() {
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(users.save(user)).thenReturn(user);
    when(permissions.permissionsOf("ADMIN")).thenReturn(List.of());

    UserProfileResponse profile =
        service.updateProfile(
            USER_ID,
            new UpdateProfileRequest("New Name", "+84901234567", "https://example.com/a.png"));

    assertThat(profile.name()).isEqualTo("New Name");
    assertThat(profile.phoneNumber()).isEqualTo("+84901234567");
    assertThat(profile.avatar()).isEqualTo("https://example.com/a.png");
  }

  @Test
  void change_password_stores_the_new_hash_and_revokes_every_refresh_token() {
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("old-password", "$2a$10$old")).thenReturn(true);
    when(passwordEncoder.encode("new-password")).thenReturn("$2a$10$new");

    service.changePassword(USER_ID, new ChangePasswordRequest("old-password", "new-password"));

    assertThat(user.getPasswordHash()).isEqualTo("$2a$10$new");
    verify(users).save(user);
    verify(refreshTokens).revokeAllActive(USER_ID, NOW);
  }

  @Test
  void change_password_with_a_wrong_current_password_is_refused() {
    when(users.findById(USER_ID)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "$2a$10$old")).thenReturn(false);

    assertThatThrownBy(
            () -> service.changePassword(USER_ID, new ChangePasswordRequest("wrong", "new-pass")))
        .isInstanceOf(BadRequestException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.AUTH_WRONG_PASSWORD);
    verify(refreshTokens, never()).revokeAllActive(any(), any());
  }

  @Test
  void a_new_password_longer_than_72_bytes_is_refused() {
    // 25 ký tự "ệ" = 75 byte UTF-8: qua @Size(max = 72) nhưng BCrypt chỉ đọc 72 byte.
    String tooLong = "ệ".repeat(25);

    assertThatThrownBy(
            () -> service.changePassword(USER_ID, new ChangePasswordRequest("old", tooLong)))
        .isInstanceOf(BadRequestException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_FAILED);
  }
}
