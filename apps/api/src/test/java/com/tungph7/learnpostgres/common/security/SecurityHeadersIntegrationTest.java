package com.tungph7.learnpostgres.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;

  @Test
  void response_has_owasp_security_headers() throws Exception {
    // Spring Security chỉ gửi HSTS qua kết nối HTTPS — MockMvc dùng .secure(true) để giả HTTPS.
    mockMvc
        .perform(get("/actuator/health").secure(true))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().exists("Strict-Transport-Security"))
        .andExpect(header().exists("Referrer-Policy"))
        .andExpect(header().exists("Permissions-Policy"))
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().exists("Cross-Origin-Opener-Policy"));
  }

  @Test
  void cors_preflight_from_allowed_origin_succeeds() throws Exception {
    mockMvc
        .perform(
            options("/api/products")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  /**
   * F06: tạo payment / refund bắt buộc header {@code Idempotency-Key}, và trình duyệt hỏi preflight
   * trước khi gửi. Header chưa có trong {@code allowed-headers} thì Spring vẫn trả 200 nhưng bỏ nó
   * khỏi {@code Access-Control-Allow-Headers} (chỉ trả 403 khi KHÔNG header nào được phép) — trình
   * duyệt thấy thiếu nên chặn, request thật không bao giờ được gửi. Vì vậy test kiểm header của
   * response, không chỉ status.
   */
  @Test
  void cors_preflight_allows_idempotency_key_header() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/orders/01930000-0000-7000-8000-0000000000d1/payments")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    Matchers.containsStringIgnoringCase("idempotency-key")));
  }

  @Test
  void cors_preflight_from_disallowed_origin_returns_403() throws Exception {
    mockMvc
        .perform(
            options("/api/products")
                .header("Origin", "http://evil.com")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }
}
