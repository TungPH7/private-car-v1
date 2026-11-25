package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 403 Forbidden — resource tồn tại nhưng caller không có quyền trên nó.
 *
 * <p>Dùng cho: - Ownership check fail (address thuộc customer khác). - Thiếu role/scope cho action.
 *
 * <p>KHÁC 404: 404 = "không thấy", 403 = "thấy nhưng không được đụng". Chỉ trả 403 khi việc lộ sự
 * tồn tại của resource là chấp nhận được; nếu không, trả 404 để tránh enumeration.
 */
public class ForbiddenException extends BusinessException {

  public ForbiddenException(ErrorCode code, String detail) {
    super(code, HttpStatus.FORBIDDEN, detail, null);
  }

  public ForbiddenException(ErrorCode code, String detail, Throwable cause) {
    super(code, HttpStatus.FORBIDDEN, detail, cause);
  }
}
