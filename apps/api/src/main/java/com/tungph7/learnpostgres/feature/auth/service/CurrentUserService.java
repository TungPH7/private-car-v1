package com.tungph7.learnpostgres.feature.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.UnauthorizedException;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.dto.ChangePasswordRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UpdateProfileRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UserProfileResponse;
import com.tungph7.learnpostgres.feature.auth.dto.UserProfileResponse.PermissionResponse;
import com.tungph7.learnpostgres.feature.auth.dto.UserProfileResponse.RoleResponse;
import com.tungph7.learnpostgres.feature.auth.repository.PermissionRepository;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

/**
 * Hồ sơ và mật khẩu của tài khoản đang đăng nhập. Id tài khoản là {@code sub} của access token:
 * token còn hạn mà tài khoản đã bị xoá hay khóa thì là 401, như token không hợp lệ.
 */
@Service
@Transactional(readOnly = true)
public class CurrentUserService {

  /** BCrypt chỉ đọc 72 byte đầu: dài hơn là hai mật khẩu khác nhau thành một. */
  private static final int BCRYPT_MAX_BYTES = 72;

  private final UserAccountRepository users;
  private final PermissionRepository permissions;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public CurrentUserService(
      UserAccountRepository users,
      PermissionRepository permissions,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.users = users;
    this.permissions = permissions;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  public UserProfileResponse profile(UUID userId) {
    return toProfile(activeUser(userId));
  }

  @Transactional
  public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest req) {
    UserAccount user = activeUser(userId);
    user.updateProfile(
        req.name().trim(), blankToNull(req.phoneNumber()), blankToNull(req.avatar()));
    return toProfile(users.save(user));
  }

  /** Đổi mật khẩu, rồi thu hồi mọi refresh token: các phiên khác phải đăng nhập lại. */
  @Transactional
  public void changePassword(UUID userId, ChangePasswordRequest req) {
    if (req.newPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
      throw new BadRequestException(
          ErrorCode.VALIDATION_FAILED,
          "The new password must be at most %d bytes in UTF-8.".formatted(BCRYPT_MAX_BYTES));
    }
    UserAccount user = activeUser(userId);
    if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
      throw new BadRequestException(
          ErrorCode.AUTH_WRONG_PASSWORD, "The current password is wrong.");
    }
    user.changePasswordHash(passwordEncoder.encode(req.newPassword()));
    users.save(user);
    refreshTokens.revokeAllActive(userId, clock.instant());
  }

  private UserAccount activeUser(UUID userId) {
    return users
        .findById(userId)
        .filter(UserAccount::isEnabled)
        .orElseThrow(
            () ->
                new UnauthorizedException(
                    ErrorCode.UNAUTHENTICATED,
                    "The account of this token no longer exists or is disabled."));
  }

  private UserProfileResponse toProfile(UserAccount user) {
    var granted =
        permissions.permissionsOf(user.getRoleCode()).stream()
            .map(p -> new PermissionResponse(p.code(), p.module(), p.httpMethod(), p.path()))
            .toList();
    return new UserProfileResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getPhoneNumber(),
        user.getAvatarUrl(),
        new RoleResponse(user.getRoleCode(), granted));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
