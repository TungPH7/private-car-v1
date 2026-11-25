package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 422 Unprocessable Content (RFC 9110 §15.5.21) — request đúng cú pháp, nhưng server không xử lý
 * được nội dung của nó.
 *
 * <p>Repo vẫn ưu tiên 400 cho input sai và 409 cho xung đột trạng thái ({@link ConflictException}).
 * 422 chỉ dùng khi có chuẩn chỉ định rõ: hiện là {@code PAY_IDEMPOTENCY_KEY_REUSED} — draft IETF
 * {@code Idempotency-Key} (§2.7) trả 422 khi một key được dùng lại cho request có payload khác.
 */
public class UnprocessableContentException extends BusinessException {

  public UnprocessableContentException(ErrorCode code, String detail) {
    super(code, HttpStatus.UNPROCESSABLE_CONTENT, detail, null);
  }
}
