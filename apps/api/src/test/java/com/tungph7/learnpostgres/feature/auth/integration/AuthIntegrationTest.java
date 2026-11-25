package com.tungph7.learnpostgres.feature.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Đăng nhập end-to-end trên Postgres thật: V20, JWT, refresh token, bộ lọc của SecurityConfig. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractContainerBaseTest {

  private static final String EMAIL = "admin.it@example.com";
  private static final String PASSWORD = "correct horse battery";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired UserAccountRepository users;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired PasswordEncoder passwordEncoder;

  private UserAccount admin;

  @BeforeEach
  void createAdmin() {
    admin = AuthFixtures.saveUser(users, passwordEncoder, EMAIL, PASSWORD, "ADMIN");
  }

  @AfterEach
  void cleanup() {
    refreshTokens.deleteAll();
    users.deleteAll();
  }

  // ---- đăng nhập ---------------------------------------------------

  @Test
  void login_gives_a_token_pair_that_reads_the_profile_with_the_permissions_of_V20()
      throws Exception {
    JsonNode pair =
        tokens(Map.of("grantType", "password", "email", EMAIL, "password", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .transform(json::readTree);

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer " + access(pair)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(admin.getId().toString()))
        .andExpect(jsonPath("$.email").value(EMAIL))
        .andExpect(jsonPath("$.role.name").value("ADMIN"))
        .andExpect(jsonPath("$.role.permissions.length()").value(3))
        .andExpect(jsonPath("$.role.permissions[0].name").value("product:create"))
        .andExpect(jsonPath("$.role.permissions[1].name").value("product:delete"))
        .andExpect(jsonPath("$.role.permissions[1].method").value("DELETE"))
        .andExpect(jsonPath("$.role.permissions[1].path").value("/api/products/{id}"))
        .andExpect(jsonPath("$.role.permissions[2].name").value("product:update"));
  }

  @Test
  void the_email_is_case_insensitive() throws Exception {
    tokens(Map.of("grantType", "password", "email", "Admin.IT@Example.com", "password", PASSWORD))
        .andExpect(status().isOk());
  }

  @Test
  void a_wrong_password_and_an_unknown_email_get_the_same_401() throws Exception {
    tokens(Map.of("grantType", "password", "email", EMAIL, "password", "wrong password"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    tokens(Map.of("grantType", "password", "email", "nobody@example.com", "password", PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void a_password_grant_without_a_password_is_a_validation_error() throws Exception {
    tokens(Map.of("grantType", "password", "email", EMAIL))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ---- gửi token ---------------------------------------------------

  @Test
  void the_access_token_header_works_behind_the_basic_auth_of_an_environment() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            get("/api/users/me")
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .header("X-Access-Token", access(pair)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(EMAIL));
  }

  @Test
  void the_profile_needs_a_token() throws Exception {
    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void a_token_that_is_not_ours_is_401() throws Exception {
    // Đúng định dạng, đúng issuer, còn hạn, nhưng ký bằng một khóa khác: chữ ký phải làm nó hỏng.
    SecretKey otherKey =
        new SecretKeySpec("z".repeat(32).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("learn-postgresql-api")
            .subject(admin.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("scope", "product:delete")
            .build();
    String forged =
        new NimbusJwtEncoder(new ImmutableSecret<>(otherKey))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  // ---- làm mới, đăng xuất --------------------------------------------

  @Test
  void a_refresh_token_works_once_and_its_reuse_revokes_the_new_one_too() throws Exception {
    JsonNode first = login();
    JsonNode second =
        tokens(Map.of("grantType", "refresh_token", "refreshToken", refresh(first)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .transform(json::readTree);
    assertThat(refresh(second)).isNotEqualTo(refresh(first));

    // Dùng lại token đã đổi: 401, và token mới của cùng tài khoản cũng bị thu hồi.
    tokens(Map.of("grantType", "refresh_token", "refreshToken", refresh(first)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
    tokens(Map.of("grantType", "refresh_token", "refreshToken", refresh(second)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"));
  }

  @Test
  void a_revoked_refresh_token_no_longer_refreshes() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            post("/api/auth/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refresh(pair)))))
        .andExpect(status().isNoContent());

    tokens(Map.of("grantType", "refresh_token", "refreshToken", refresh(pair)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revoking_an_unknown_token_is_204_too() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"unknown\"}"))
        .andExpect(status().isNoContent());
  }

  // ---- hồ sơ, mật khẩu ---------------------------------------------

  @Test
  void the_profile_can_be_updated() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            put("/api/users/me")
                .header("Authorization", "Bearer " + access(pair))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Quản trị\",\"phoneNumber\":\"+84901234567\","
                        + "\"avatar\":\"https://example.com/a.png\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Quản trị"))
        .andExpect(jsonPath("$.phoneNumber").value("+84901234567"))
        .andExpect(jsonPath("$.avatar").value("https://example.com/a.png"));
  }

  @Test
  void an_avatar_that_is_not_https_is_refused() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            put("/api/users/me")
                .header("Authorization", "Bearer " + access(pair))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"avatar\":\"javascript:alert(1)\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void a_new_password_signs_in_and_ends_the_other_sessions() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            put("/api/users/me/password")
                .header("Authorization", "Bearer " + access(pair))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", "a brand new one"))))
        .andExpect(status().isNoContent());

    tokens(Map.of("grantType", "refresh_token", "refreshToken", refresh(pair)))
        .andExpect(status().isUnauthorized());
    tokens(Map.of("grantType", "password", "email", EMAIL, "password", PASSWORD))
        .andExpect(status().isUnauthorized());
    tokens(Map.of("grantType", "password", "email", EMAIL, "password", "a brand new one"))
        .andExpect(status().isOk());
  }

  @Test
  void a_wrong_current_password_is_refused() throws Exception {
    JsonNode pair = login();

    mockMvc
        .perform(
            put("/api/users/me/password")
                .header("Authorization", "Bearer " + access(pair))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"nope\",\"newPassword\":\"long enough\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_WRONG_PASSWORD"));
  }

  // ---- OpenAPI -----------------------------------------------------

  @Test
  void the_api_docs_offer_both_ways_to_send_the_token() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.accessToken.in").value("header"))
        .andExpect(
            jsonPath("$.components.securitySchemes.accessToken.name").value("X-Access-Token"))
        .andExpect(jsonPath("$.paths['/api/products'].post.security[0].bearerAuth").exists())
        .andExpect(jsonPath("$.paths['/api/products'].post.security[1].accessToken").exists())
        .andExpect(jsonPath("$.paths['/api/products'].get.security").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/users/me'].get.security[0].bearerAuth").exists());
  }

  // ---- helpers -----------------------------------------------------

  private ResultActions tokens(Map<String, String> body) throws Exception {
    return mockMvc.perform(
        post("/api/auth/tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private JsonNode login() throws Exception {
    return tokens(Map.of("grantType", "password", "email", EMAIL, "password", PASSWORD))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString()
        .transform(json::readTree);
  }

  private static String access(JsonNode pair) {
    return pair.get("accessToken").asString();
  }

  private static String refresh(JsonNode pair) {
    return pair.get("refreshToken").asString();
  }
}
