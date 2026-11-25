package com.tungph7.learnpostgres.common.config;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Provider trả "ai đang thao tác?" cho JPA Auditing fill @CreatedBy / @LastModifiedBy.
 *
 * <p>Request có access token: tên của Authentication, tức {@code sub} của JWT = id tài khoản (UUID,
 * 36 ký tự, vừa cột VARCHAR(64)). Không có (request ẩn danh, job nền như hết hạn đơn,
 * BootstrapAdmin lúc khởi động): {@code system}.
 */
class CurrentAuditorProvider implements AuditorAware<String> {

  private static final String SYSTEM_AUDITOR = "system";

  @Override
  public Optional<String> getCurrentAuditor() {
    return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
        .filter(Authentication::isAuthenticated)
        .filter(auth -> !(auth instanceof AnonymousAuthenticationToken))
        .map(Authentication::getName)
        .or(() -> Optional.of(SYSTEM_AUDITOR));
  }
}
