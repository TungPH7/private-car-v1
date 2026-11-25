package com.tungph7.learnpostgres.common.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test slim security chain — `@EnableWebSecurity` bắt buộc để register HttpSecurity bean
 * trong @WebMvcTest slice (main app dùng Spring Boot's auto-config WebSecurityEnablerConfiguration,
 * không hoạt động ở slice). Xem QnA-2 Q3.
 */
@TestConfiguration
@EnableWebSecurity
public class TestSecurityConfig {

  @Bean
  SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }
}
