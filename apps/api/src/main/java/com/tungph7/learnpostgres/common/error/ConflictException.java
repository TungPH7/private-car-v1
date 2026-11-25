package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 409 Conflict — request hợp lệ nhưng xung đột với state hiện tại của resource.
 *
 * <p>Dùng cho: - UNIQUE violation (slug duplicated). - Optimistic lock conflict (version mismatch).
 * - Business rule conflict (vd: order đã ship không được cancel).
 *
 * <p>KHÁC 422 Unprocessable Entity: - 409 = state issue (có thể fix bằng retry / refresh). - 422 =
 * semantic invalid (input không hợp lý — vd "ngày sinh tương lai").
 *
 * <p>Project ưu tiên 400 cho semantic và 409 cho state. 422 chỉ dùng khi có chuẩn chỉ định rõ — xem
 * {@link UnprocessableContentException}.
 */
public class ConflictException extends BusinessException {

  public ConflictException(ErrorCode code, String detail) {
    super(code, HttpStatus.CONFLICT, detail, null);
  }

  public ConflictException(ErrorCode code, String detail, Throwable cause) {
    super(code, HttpStatus.CONFLICT, detail, cause);
  }
}
