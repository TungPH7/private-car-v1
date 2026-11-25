package com.tungph7.learnpostgres.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.domain.Permission;
import com.tungph7.learnpostgres.feature.auth.domain.RefreshToken;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.TokenResponse;
import com.tungph7.learnpostgres.feature.auth.repository.PermissionRepository;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

  private static final UUID USER_ID = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead10");

  @Mock RefreshTokenRepository refreshTokens;
  @Mock PermissionRepository permissions;

  private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final NimbusJwtDecoder decoder =
      NimbusJwtDecoder.withSecretKey(AuthFixtures.KEY).macAlgorithm(MacAlgorithm.HS256).build();
  private TokenService service;

  @BeforeEach
  void setUp() {
    service =
        new TokenService(
            new NimbusJwtEncoder(new ImmutableSecret<>(AuthFixtures.KEY)),
            refreshTokens,
            permissions,
            AuthFixtures.props(),
            Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void the_access_token_names_the_user_its_role_and_its_permissions() {
    when(permissions.permissionsOf("ADMIN"))
        .thenReturn(
            List.of(
                new Permission("product:create", "product", "POST", "/api/products"),
                new Permission("product:delete", "product", "DELETE", "/api/products/{id}")));
    UserAccount admin = AuthFixtures.user(USER_ID, "ADMIN", "$2a$10$hash");

    TokenResponse tokens = service.issue(admin);

    Jwt jwt = decoder.decode(tokens.accessToken());
    assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
    assertThat(jwt.getClaimAsString("userId")).isEqualTo(USER_ID.toString());
    assertThat(jwt.getClaimAsString("roleName")).isEqualTo("ADMIN");
    assertThat(jwt.getClaimAsString("scope")).isEqualTo("product:create product:delete");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo(AuthFixtures.ISSUER);
    assertThat(jwt.getIssuedAt()).isEqualTo(now);
    assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(900));
    assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    assertThat(tokens.tokenType()).isEqualTo("Bearer");
    assertThat(tokens.expiresIn()).isEqualTo(900);
  }

  @Test
  void a_role_without_permissions_has_an_empty_scope() {
    when(permissions.permissionsOf("CUSTOMER")).thenReturn(List.of());

    TokenResponse tokens = service.issue(AuthFixtures.user(USER_ID, "CUSTOMER", "$2a$10$hash"));

    assertThat(decoder.decode(tokens.accessToken()).getClaimAsString("scope")).isEmpty();
  }

  @Test
  void only_the_hash_of_the_refresh_token_is_stored_with_its_expiry() {
    when(permissions.permissionsOf("ADMIN")).thenReturn(List.of());
    when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));

    TokenResponse tokens = service.issue(AuthFixtures.user(USER_ID, "ADMIN", "$2a$10$hash"));

    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
    assertThat(saved.getValue().getTokenHash())
        .isEqualTo(TokenService.hash(tokens.refreshToken()))
        .doesNotContain(tokens.refreshToken());
    assertThat(saved.getValue().getExpiresAt()).isEqualTo(now.plusSeconds(7 * 24 * 3600));
    assertThat(tokens.refreshToken()).matches("^[A-Za-z0-9_-]{43}$");
  }

  @Test
  void two_issues_give_two_different_refresh_tokens() {
    when(permissions.permissionsOf("ADMIN")).thenReturn(List.of());
    UserAccount admin = AuthFixtures.user(USER_ID, "ADMIN", "$2a$10$hash");

    assertThat(service.issue(admin).refreshToken())
        .isNotEqualTo(service.issue(admin).refreshToken());
  }

  @Test
  void the_hash_is_the_sha256_hex_of_the_token() {
    // echo -n abc | sha256sum
    assertThat(TokenService.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
