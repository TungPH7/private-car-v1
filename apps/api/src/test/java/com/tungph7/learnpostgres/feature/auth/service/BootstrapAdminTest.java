package com.tungph7.learnpostgres.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.config.AuthProperties;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminTest {

  @Mock UserAccountRepository users;
  @Mock PasswordEncoder passwordEncoder;

  private static AuthProperties props(String email, String password) {
    return new AuthProperties(
        AuthFixtures.ISSUER,
        Duration.ofMinutes(15),
        Duration.ofDays(7),
        new AuthProperties.Jwt(null),
        new AuthProperties.BootstrapAdmin(email, password));
  }

  @Test
  void nothing_happens_when_it_is_not_configured() throws Exception {
    new BootstrapAdmin(props(null, null), users, passwordEncoder).run(null);

    verify(users, never()).save(any());
  }

  @Test
  void an_existing_account_is_left_as_it_is() throws Exception {
    when(users.findByEmail("admin@example.com"))
        .thenReturn(Optional.of(AuthFixtures.user(UUID.randomUUID(), "CUSTOMER", "$2a$10$x")));

    new BootstrapAdmin(props("admin@example.com", "long-enough-password"), users, passwordEncoder)
        .run(null);

    verify(users, never()).save(any());
  }

  @Test
  void a_missing_account_is_created_as_admin_with_the_hash_of_the_password() throws Exception {
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("long-enough-password")).thenReturn("$2a$10$hash");

    new BootstrapAdmin(props(" admin@example.com ", "long-enough-password"), users, passwordEncoder)
        .run(null);

    ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
    assertThat(saved.getValue().getRoleCode()).isEqualTo("ADMIN");
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
    assertThat(saved.getValue().isEnabled()).isTrue();
  }

  @Test
  void a_password_shorter_than_12_characters_stops_the_start() {
    assertThatThrownBy(
            () ->
                new BootstrapAdmin(props("admin@example.com", "short"), users, passwordEncoder)
                    .run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 12");
    verify(users, never()).save(any());
  }
}
