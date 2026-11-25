package com.tungph7.learnpostgres.feature.auth.config;

import javax.crypto.SecretKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tungph7.learnpostgres.feature.auth.security.AccessTokenResolver;

/**
 * Các bean của đăng nhập mà {@code SecurityConfig} dùng: mật khẩu băm BCrypt, access token JWT ký
 * và kiểm bằng một khóa HS256, quyền đọc từ claim {@code scope}.
 */
@Configuration
public class AuthConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    // BCrypt thuần ($2a$...), không tiền tố {bcrypt}: khớp CHECK ck_users_password_bcrypt (V20).
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecretKey jwtSigningKey(AuthProperties props, Environment environment) {
    return SigningKeys.of(props.jwt().secret(), environment.matchesProfiles("prod"));
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
  }

  /** Chỉ HS256, chỉ issuer của mình, và kiểm {@code exp}, {@code nbf} (validator mặc định). */
  @Bean
  JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties props) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
    return decoder;
  }

  /**
   * Authority là chính tên quyền trong {@code scope}, như {@code product:delete}, không tiền tố.
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("scope");
    authorities.setAuthorityPrefix("");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  @Bean
  BearerTokenResolver bearerTokenResolver() {
    return new AccessTokenResolver();
  }
}
