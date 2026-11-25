package com.tungph7.learnpostgres.feature.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.auth.dto.RevocationRequest;
import com.tungph7.learnpostgres.feature.auth.dto.TokenRequest;
import com.tungph7.learnpostgres.feature.auth.dto.TokenResponse;
import com.tungph7.learnpostgres.feature.auth.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Token của API: lấy cặp token (đăng nhập, làm mới) và thu hồi (đăng xuất). Tên tài nguyên là danh
 * từ (quy ước API-002): tạo một cặp token, tạo một lần thu hồi.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Sign in, refresh and sign out")
public class AuthController {

  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/tokens")
  @Operation(
      summary = "Get a token pair",
      description =
          "grantType password signs in with email and password; refresh_token exchanges a refresh"
              + " token, once, for a new pair. A wrong email and a wrong password get the same"
              + " 401.")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(
      responseCode = "401",
      description = "Wrong credentials, or a refresh token that is unknown, expired or revoked",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ResponseEntity<TokenResponse> tokens(@Valid @RequestBody TokenRequest req) {
    TokenResponse pair =
        switch (req.grantType()) {
          case PASSWORD -> auth.login(req.email(), req.password());
          case REFRESH_TOKEN -> auth.refresh(req.refreshToken());
        };
    // RFC 6749 § 5.1: không proxy hay trình duyệt nào được giữ token.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .contentType(MediaType.APPLICATION_JSON)
        .body(pair);
  }

  @PostMapping("/revocations")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Revoke a refresh token (sign out)",
      description = "Always 204, also for a token that is unknown or revoked already.")
  @ApiResponse(responseCode = "204", description = "Revoked")
  public void revoke(@Valid @RequestBody RevocationRequest req) {
    auth.revoke(req.refreshToken());
  }
}
