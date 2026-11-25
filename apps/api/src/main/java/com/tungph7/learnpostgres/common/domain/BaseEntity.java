package com.tungph7.learnpostgres.common.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base class cho mọi entity nghiệp vụ.
 *
 * <p>Cung cấp: - PK kiểu UUIDv7 (sinh Java-side ở @PrePersist; DB cũng có DEFAULT app.gen_uuid_v7()
 * làm fallback cho INSERT không-qua-JPA). - 4 audit field (created_at, updated_at, created_by,
 * updated_by) tự fill bởi Spring Data JPA Auditing — xem JpaAuditingConfig. - @Version cho
 * optimistic locking — Hibernate tự inject WHERE version=? trong UPDATE statement, throw
 * OptimisticLockException nếu mismatch.
 *
 * <p>Inheritance: @MappedSuperclass — field merge vào table của child entity, KHÔNG có table riêng
 * cho BaseEntity.
 *
 * <p>Equals/hashCode: dùng id (tránh hashCode đổi khi entity được save lần đầu).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @CreatedBy
  @Column(name = "created_by", nullable = false, updatable = false, length = 64)
  private String createdBy;

  @LastModifiedBy
  @Column(name = "updated_by", nullable = false, length = 64)
  private String updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /**
   * Sinh UUIDv7 ngay trước INSERT nếu app code chưa set id. @PrePersist gọi 1 lần, chỉ khi INSERT
   * (không phải UPDATE).
   */
  @PrePersist
  protected void onPrePersist() {
    if (id == null) {
      id = UuidV7.generate();
    }
  }

  // -----------------------------------------------------------------
  // Getters / setters — viết tay để dễ kiểm soát (Lombok có thể dùng
  // ở entity con; BaseEntity nên minh bạch)
  // -----------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  /**
   * Setter id chủ ý package-private — chỉ test/migration code đặt ID thủ công. Code business không
   * nên gọi.
   */
  void setId(UUID id) {
    this.id = id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public Long getVersion() {
    return version;
  }

  // -----------------------------------------------------------------
  // equals / hashCode dựa trên id (Vlad Mihalcea pattern)
  // -----------------------------------------------------------------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BaseEntity that)) {
      return false;
    }
    // 2 entity chưa save (id=null) — coi là khác nhau (kể cả cùng class)
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    // Constant per concrete class — không thay đổi khi id được assigned.
    return getClass().hashCode();
  }

  @Override
  public String toString() {
    return "%s{id=%s}".formatted(getClass().getSimpleName(), id);
  }
}
