package com.tungph7.learnpostgres.feature.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.feature.auth.config.AuthProperties;
import com.tungph7.learnpostgres.feature.auth.domain.Permission;
import com.tungph7.learnpostgres.feature.auth.domain.RefreshToken;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.TokenResponse;
import com.tungph7.learnpostgres.feature.auth.repository.PermissionRepository;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;

/**
 * Phát cặp token cho một tài khoản.
 *
 * <ul>
 *   <li>Access token: JWT HS256 với {@code sub} và {@code userId} (id tài khoản), {@code roleName}
 *       (mã vai trò) và {@code scope} (các quyền, cách nhau một dấu cách). Web đọc {@code userId},
 *       {@code roleName}, {@code exp} để biết ai đăng nhập mà không gọi API.
 *   <li>Refresh token: 256 bit ngẫu nhiên, base64url; DB chỉ giữ SHA-256 của nó.
 * </ul>
 */
@Service
public class TokenService {

  private static final int REFRESH_TOKEN_BYTES = 32;

  private final JwtEncoder encoder;
  private final RefreshTokenRepository refreshTokens;
  private final PermissionRepository permissions;
  private final AuthProperties props;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public TokenService(
      JwtEncoder encoder,
      RefreshTokenRepository refreshTokens,
      PermissionRepository permissions,
      AuthProperties props,
      Clock clock) {
    this.encoder = encoder;
    this.refreshTokens = refreshTokens;
    this.permissions = permissions;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public TokenResponse issue(UserAccount user) {
    Instant now = clock.instant();
    String scope =
        permissions.permissionsOf(user.getRoleCode()).stream()
            .map(Permission::code)
            .collect(Collectors.joining(" "));
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(props.issuer())
            .subject(user.getId().toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.accessTokenTtl()))
            .claim("userId", user.getId().toString())
            .claim("roleName", user.getRoleCode())
            .claim("scope", scope)
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

    String refreshToken = newRefreshToken();
    refreshTokens.save(
        new RefreshToken(user.getId(), hash(refreshToken), now.plus(props.refreshTokenTtl())));

    return new TokenResponse(
        accessToken, refreshToken, "Bearer", props.accessTokenTtl().toSeconds());
  }

  /** SHA-256 hex của một refresh token: dạng lưu trong {@code app.refresh_tokens.token_hash}. */
  public static String hash(String token) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }

  private String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
