package com.tungph7.learnpostgres.common.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bơm thêm metadata request vào MDC (sau CorrelationIdFilter). Tách riêng để dễ tắt (test) hoặc
 * chèn field mới.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcRequestContextFilter extends OncePerRequestFilter {

  private static final String K_METHOD = "requestMethod";
  private static final String K_URI = "requestUri";
  private static final String K_IP = "clientIp";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    MDC.put(K_METHOD, request.getMethod());
    MDC.put(K_URI, request.getRequestURI());
    MDC.put(K_IP, resolveClientIp(request));

    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(K_METHOD);
      MDC.remove(K_URI);
      MDC.remove(K_IP);
    }
  }

  /**
   * Đọc client IP, ưu tiên X-Forwarded-For (lấy IP đầu tiên) khi app sau LB / reverse proxy. Khi
   * prod, cân nhắc chỉ trust X-Forwarded-For nếu remote là proxy của mình.
   */
  private static String resolveClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      int comma = xff.indexOf(',');
      return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }
    return request.getRemoteAddr();
  }
}
