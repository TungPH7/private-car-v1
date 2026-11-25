package com.tungph7.learnpostgres.feature.auth.security;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Lấy access token của request: {@code Authorization: Bearer <token>} như quy ước API-012, hoặc
 * header {@link #HEADER} khi {@code Authorization} không chở Bearer.
 *
 * <p>Vì sao có header thứ hai: trên Development và Staging, Caddy của VPS đòi basic auth trên mọi
 * host, nên trình duyệt đã dùng {@code Authorization} cho {@code Basic ...}. Admin (SPA) và Swagger
 * UI gọi API từ trình duyệt sau lớp đó, nên gửi token ở {@link #HEADER}. Web gọi API từ server, qua
 * mạng nội bộ không có Caddy, nên vẫn dùng {@code Authorization: Bearer}. Xem
 * docs/adr/0001-api-authentication.md.
 */
public class AccessTokenResolver implements BearerTokenResolver {

  /** Header phụ chở access token, không có tiền tố {@code Bearer}. */
  public static final String HEADER = "X-Access-Token";

  /** Cùng bộ ký tự b64token của RFC 6750 § 2.1 mà {@link DefaultBearerTokenResolver} chấp nhận. */
  private static final Pattern TOKEN = Pattern.compile("^[a-zA-Z0-9\\-._~+/]+=*$");

  private static final Pattern BEARER = Pattern.compile("^Bearer\\s", Pattern.CASE_INSENSITIVE);

  private final DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();

  @Override
  public String resolve(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && BEARER.matcher(authorization).find()) {
      return standard.resolve(request);
    }
    String token = request.getHeader(HEADER);
    if (token == null || token.isEmpty()) {
      return null;
    }
    if (!TOKEN.matcher(token).matches()) {
      BearerTokenError error = BearerTokenErrors.invalidToken("Access token is malformed");
      throw new OAuth2AuthenticationException(error);
    }
    return token;
  }
}
