package com.tungph7.learnpostgres.feature.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.tungph7.learnpostgres.common.domain.BaseEntity;

/**
 * Một refresh token đã phát ({@code app.refresh_tokens}, V20). Chỉ lưu SHA-256 (hex) của token: lộ
 * bảng này không lộ token dùng được. Token dùng một lần: đổi token thì dòng cũ có {@code
 * revokedAt}.
 */
@Entity
@Table(name = "refresh_tokens", schema = "app")
public class RefreshToken extends BaseEntity {

  @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID userId;

  @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RefreshToken() {}

  public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** Thu hồi; lần thu hồi đầu được giữ. */
  public void revoke(Instant now) {
    if (revokedAt == null) {
      revokedAt = now;
    }
  }

  @Override
  public String toString() {
    return "RefreshToken[id=%s, userId=%s, revoked=%s]".formatted(getId(), userId, isRevoked());
  }
}
