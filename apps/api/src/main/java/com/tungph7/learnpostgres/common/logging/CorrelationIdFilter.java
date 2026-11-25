package com.tungph7.learnpostgres.common.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

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
 * Filter chạy sớm nhất: 1) Lấy correlationId từ header request (nếu client gửi) hoặc generate UUID
 * v4. 2) Sanitize: chỉ chấp nhận [A-Za-z0-9._-], cắt theo maxLength. 3) Set vào MDC + response
 * header. 4) Cleanup MDC trong finally (bắt buộc — thread pool tái dùng).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Cho phép UUID, hex, snake/kebab/dot — đủ cho correlationId từ upstream service. */
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

  private final LoggingProperties props;

  public CorrelationIdFilter(LoggingProperties props) {
    this.props = props;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = props.correlationIdHeader();
    String mdcKey = props.correlationIdMdcKey();

    String incoming = request.getHeader(header);
    String correlationId = sanitize(incoming, props.maxCorrelationIdLength());
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    MDC.put(mdcKey, correlationId);
    response.setHeader(header, correlationId); // echo lại ngay khi response chưa commit

    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(mdcKey);
    }
  }

  private static String sanitize(String raw, int maxLength) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > maxLength) {
      trimmed = trimmed.substring(0, maxLength);
    }
    return SAFE.matcher(trimmed).matches() ? trimmed : null; // không match → drop, gen mới
  }
}
