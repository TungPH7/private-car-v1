package com.tungph7.learnpostgres.common.config;

import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Bật JPA Auditing trên toàn project.
 *
 * <p>3 thành phần: 1) @EnableJpaAuditing — register AuditingEntityListener vào JPA lifecycle. 2)
 * auditorProvider — trả "ai đang thao tác" cho @CreatedBy / @LastModifiedBy. Folder 05: hardcoded
 * "system". Folder 10: SecurityContext. 3) utcDateTimeProvider — trả Instant.now() UTC
 * cho @CreatedDate / @LastModifiedDate, KHÔNG phụ thuộc system timezone.
 *
 * <p>Tên bean được tham chiếu tường minh trong @EnableJpaAuditing để tránh Spring tự ý chọn bean
 * khác cùng kiểu (vd: bean DateTimeProvider khác trong tương lai).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "utcDateTimeProvider")
public class JpaAuditingConfig {

  /**
   * Cung cấp giá trị fill cho @CreatedBy / @LastModifiedBy.
   *
   * <p>Folder 05: fixed "system". Folder 10: replace bằng implementation đọc SecurityContextHolder.
   */
  @Bean
  AuditorAware<String> auditorProvider() {
    return new CurrentAuditorProvider();
  }

  /**
   * Cung cấp giá trị fill cho @CreatedDate / @LastModifiedDate.
   *
   * <p>Trả Instant.now() — UTC pure, match Postgres timestamptz. Không dùng LocalDateTime.now()
   * (system timezone) để tránh ambiguity khi container chạy ở timezone khác máy dev.
   */
  @Bean
  DateTimeProvider utcDateTimeProvider() {
    return () -> Optional.of(Instant.now());
  }
}
