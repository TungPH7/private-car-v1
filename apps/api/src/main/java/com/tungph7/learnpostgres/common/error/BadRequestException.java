package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 400 Bad Request — input không hợp lệ.
 *
 * <p>Khi nào dùng: - Business rule validation thất bại (vd "khối lượng > 100kg phải có code đặc
 * biệt"). Bean Validation cho field-level (folder 07) sẽ được handler
 * `MethodArgumentNotValidException` xử lý — KHÔNG cần throw class này. - State transition không hợp
 * lệ (vd order đã paid không được edit items) — nhưng nếu là state conflict thì 409 phù hợp hơn.
 */
public class BadRequestException extends BusinessException {

  public BadRequestException(ErrorCode code, String detail) {
    super(code, HttpStatus.BAD_REQUEST, detail, null);
  }

  public BadRequestException(ErrorCode code, String detail, Throwable cause) {
    super(code, HttpStatus.BAD_REQUEST, detail, cause);
  }
}
