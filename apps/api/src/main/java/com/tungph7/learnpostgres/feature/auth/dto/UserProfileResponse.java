package com.tungph7.learnpostgres.feature.auth.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ của tài khoản đang đăng nhập, với vai trò và quyền để client ẩn hay hiện chức năng.
 *
 * @param avatar URL ảnh đại diện, hoặc null
 */
public record UserProfileResponse(
    UUID id,
    @Schema(example = "admin@example.com") String email,
    @Schema(example = "Quản trị") String name,
    @Schema(nullable = true, example = "+84901234567") String phoneNumber,
    @Schema(nullable = true) String avatar,
    RoleResponse role) {

  /**
   * @param name mã vai trò, như {@code ADMIN}
   */
  public record RoleResponse(
      @Schema(example = "ADMIN") String name, List<PermissionResponse> permissions) {}

  /**
   * @param name tên authority, như {@code product:delete}
   * @param module nhóm, như {@code product}
   * @param method method của endpoint mà quyền mở
   * @param path mẫu path của endpoint đó
   */
  public record PermissionResponse(
      @Schema(example = "product:delete") String name,
      @Schema(example = "product") String module,
      @Schema(example = "DELETE") String method,
      @Schema(example = "/api/products/{id}") String path) {}
}
