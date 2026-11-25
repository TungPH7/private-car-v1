package com.tungph7.learnpostgres.feature.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.auth.domain.RefreshToken;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Thu hồi mọi token còn sống của một tài khoản: đổi mật khẩu, hoặc một token đã thu hồi bị dùng
   * lại (dấu hiệu token bị lộ). Trả số token vừa thu hồi.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE RefreshToken t SET t.revokedAt = :now"
          + " WHERE t.userId = :userId AND t.revokedAt IS NULL")
  int revokeAllActive(UUID userId, Instant now);
}
