package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 404 Not Found — resource yêu cầu không tồn tại.
 *
 * <p>Dùng cho: - findById(...) trả empty. - Lookup theo slug/business key không match.
 *
 * <p>Detail message ĐƯỢC echo cho client → có thể chứa id/slug client gửi (KHÔNG leak gì internal).
 */
public class NotFoundException extends BusinessException {

  public NotFoundException(ErrorCode code, String detail) {
    super(code, HttpStatus.NOT_FOUND, detail, null);
  }

  public NotFoundException(ErrorCode code, String detail, Throwable cause) {
    super(code, HttpStatus.NOT_FOUND, detail, cause);
  }
}
