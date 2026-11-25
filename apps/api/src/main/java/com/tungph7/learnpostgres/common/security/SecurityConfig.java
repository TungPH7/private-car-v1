package com.tungph7.learnpostgres.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  /** Tên quyền của V20, cũng là authority trong claim scope của access token. */
  static final String PRODUCT_CREATE = "product:create";

  static final String PRODUCT_UPDATE = "product:update";
  static final String PRODUCT_DELETE = "product:delete";

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityProperties props,
      SecurityProblemHandler problemHandler,
      JwtDecoder jwtDecoder,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      BearerTokenResolver bearerTokenResolver)
      throws Exception {
    SecurityProperties.Headers h = props.headers();
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        // Access token JWT (docs/adr/0001-api-authentication.md): Authorization: Bearer, hoặc
        // X-Access-Token sau basic auth của môi trường (AccessTokenResolver). Token sai hay hết
        // hạn là 401 problem+json, kể cả trên endpoint mở.
        .oauth2ResourceServer(
            rs ->
                rs.bearerTokenResolver(bearerTokenResolver)
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint(problemHandler)
                    .accessDeniedHandler(problemHandler))
        .authorizeHttpRequests(
            auth ->
                auth
                    // === Probe & monitoring ===
                    .requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()

                    // === API documentation ===
                    .requestMatchers(
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()

                    // === Spring error dispatch (bypass auth tránh recursion) ===
                    .requestMatchers("/error")
                    .permitAll()

                    // === Đăng nhập, làm mới, đăng xuất ===
                    .requestMatchers(HttpMethod.POST, "/api/auth/tokens", "/api/auth/revocations")
                    .permitAll()

                    // === Tài khoản đang đăng nhập ===
                    .requestMatchers("/api/users/me", "/api/users/me/**")
                    .authenticated()

                    // === Sản phẩm: đọc mở cho mọi người, ghi theo quyền ===
                    .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/products")
                    .hasAuthority(PRODUCT_CREATE)
                    .requestMatchers(HttpMethod.PUT, "/api/products/**")
                    .hasAuthority(PRODUCT_UPDATE)
                    .requestMatchers(HttpMethod.DELETE, "/api/products/**")
                    .hasAuthority(PRODUCT_DELETE)

                    // === Phần còn lại của API (/api/v1/**) ===
                    // Còn mở như trước PCAR-4; đóng bằng quyền ở một Task sau, trước khi
                    // Production bỏ basic auth (production_auth của deployer).
                    .requestMatchers("/api/**")
                    .permitAll()

                    // === Catch-all ===
                    .anyRequest()
                    .denyAll()) // an toàn: deny tất cả không match
        // 401/403 → problem+json
        .exceptionHandling(
            eh -> eh.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
        .headers(
            headers ->
                headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions(f -> f.deny())
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.includeSubDomains(h.hsts().includeSubdomains())
                                .preload(h.hsts().preload())
                                .maxAgeInSeconds(h.hsts().maxAge()))
                    .referrerPolicy(rp -> rp.policy(parseReferrerPolicy(h.referrerPolicy())))
                    .permissionsPolicyHeader(pp -> pp.policy(h.permissionsPolicy()))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(h.csp()))
                    .crossOriginOpenerPolicy(
                        coop ->
                            coop.policy(
                                CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy
                                    .SAME_ORIGIN)))
        .build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
    SecurityProperties.Cors c = props.cors();
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(c.allowedOrigins());
    cfg.setAllowedMethods(c.allowedMethods());
    cfg.setAllowedHeaders(c.allowedHeaders());
    cfg.setExposedHeaders(c.exposedHeaders());
    cfg.setAllowCredentials(c.allowCredentials());
    cfg.setMaxAge(c.maxAgeSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }

  private static ReferrerPolicyHeaderWriter.ReferrerPolicy parseReferrerPolicy(String raw) {
    if (raw == null || raw.isBlank()) {
      return ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER;
    }
    String normalized = raw.trim().toLowerCase().replace('_', '-');
    for (var p : ReferrerPolicyHeaderWriter.ReferrerPolicy.values()) {
      if (p.getPolicy().equalsIgnoreCase(normalized)) {
        return p;
      }
    }
    throw new IllegalArgumentException("Unknown referrer-policy: " + raw);
  }
}
