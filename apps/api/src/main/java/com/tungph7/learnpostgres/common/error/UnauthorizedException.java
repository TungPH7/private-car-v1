package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 401 do chính ứng dụng quyết định, như đăng nhập sai hay refresh token không dùng được. Khác 401
 * của Spring Security ({@code SecurityProblemHandler}): cái đó là request thiếu hoặc sai access
 * token.
 */
public class UnauthorizedException extends BusinessException {

  public UnauthorizedException(ErrorCode code, String detail) {
    super(code, HttpStatus.UNAUTHORIZED, detail, null);
  }
}
