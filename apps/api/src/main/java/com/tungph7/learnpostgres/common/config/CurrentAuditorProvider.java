package com.tungph7.learnpostgres.common.config;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;

/**
 * Provider trả "ai đang thao tác?" cho JPA Auditing fill @CreatedBy / @LastModifiedBy.
 *
 * <p>Folder 05: trả về "system" cố định (chưa có Security context). Folder 10: thay bằng: return
 * Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
 * .filter(Authentication::isAuthenticated) .map(Authentication::getName) .or(() ->
 * Optional.of("system")); // fallback cho background job
 *
 * <p>Trả Optional thay vì String để folder 10 dễ điều chỉnh logic (vd: anonymous request → return
 * Optional.empty() để @CreatedBy = null).
 */
class CurrentAuditorProvider implements AuditorAware<String> {

  private static final String SYSTEM_AUDITOR = "system";

  @Override
  public Optional<String> getCurrentAuditor() {
    return Optional.of(SYSTEM_AUDITOR);
  }
}
