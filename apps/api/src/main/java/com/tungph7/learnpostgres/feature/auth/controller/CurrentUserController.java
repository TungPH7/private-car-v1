package com.tungph7.learnpostgres.feature.auth.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.auth.dto.ChangePasswordRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UpdateProfileRequest;
import com.tungph7.learnpostgres.feature.auth.dto.UserProfileResponse;
import com.tungph7.learnpostgres.feature.auth.service.CurrentUserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Tài khoản đang đăng nhập: {@code me} là chủ của access token, không nhận id từ client. */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "Current user", description = "The account of the access token")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "accessToken")
@ApiResponse(
    responseCode = "401",
    description = "No access token, an invalid one, or an account that is gone or disabled",
    content =
        @Content(
            mediaType = "application/problem+json",
            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
public class CurrentUserController {

  private final CurrentUserService users;

  public CurrentUserController(CurrentUserService users) {
    this.users = users;
  }

  @GetMapping
  @Operation(summary = "Profile, role and permissions of the signed-in account")
  @ApiResponse(responseCode = "200", description = "OK")
  public UserProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return users.profile(userId(jwt));
  }

  @PutMapping
  @Operation(summary = "Replace the name, phone number and avatar of the signed-in account")
  @ApiResponse(responseCode = "200", description = "OK")
  public UserProfileResponse updateProfile(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
    return users.updateProfile(userId(jwt), req);
  }

  @PutMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Change the password",
      description = "Revokes every refresh token of the account: other sessions sign in again.")
  @ApiResponse(responseCode = "204", description = "Changed")
  @ApiResponse(
      responseCode = "400",
      description = "The current password is wrong, or the new one is not valid",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public void changePassword(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
    users.changePassword(userId(jwt), req);
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
