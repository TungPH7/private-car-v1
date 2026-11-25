package com.tungph7.learnpostgres.feature.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.domain.CitextType;

/**
 * Tài khoản đăng nhập vào web và admin ({@code app.users}, V20). Khác {@code Customer}: customer là
 * đối tượng nghiệp vụ của đơn hàng, tài khoản là người đăng nhập.
 *
 * <p>Vai trò giữ bằng mã ({@code role_code}), không map quan hệ: vai trò và quyền là dữ liệu tham
 * chiếu, đọc bằng JDBC ({@code PermissionRepository}).
 */
@Entity
@Table(name = "users", schema = "app")
public class UserAccount extends BaseEntity {

  /** citext: so sánh không phân biệt hoa thường ở DB, như slug của Product. */
  @Type(CitextType.class)
  @Column(name = "email", nullable = false, columnDefinition = "public.citext")
  private String email;

  /** BCrypt. Không bao giờ ra khỏi service: DTO không có trường này. */
  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "phone_number", length = 32)
  private String phoneNumber;

  @Column(name = "avatar_url", length = 2048)
  private String avatarUrl;

  @Column(name = "role_code", nullable = false, length = 32)
  private String roleCode;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  protected UserAccount() {}

  public UserAccount(String email, String passwordHash, String name, String roleCode) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.name = name;
    this.roleCode = roleCode;
    this.enabled = true;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void changePasswordHash(String newHash) {
    this.passwordHash = newHash;
  }

  public String getName() {
    return name;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getAvatarUrl() {
    return avatarUrl;
  }

  /** Thay ba trường hồ sơ người dùng tự sửa được. */
  public void updateProfile(String newName, String newPhoneNumber, String newAvatarUrl) {
    this.name = newName;
    this.phoneNumber = newPhoneNumber;
    this.avatarUrl = newAvatarUrl;
  }

  public String getRoleCode() {
    return roleCode;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void disable() {
    this.enabled = false;
  }

  @Override
  public String toString() {
    return "UserAccount[id=%s, role=%s, enabled=%s]".formatted(getId(), roleCode, enabled);
  }
}
