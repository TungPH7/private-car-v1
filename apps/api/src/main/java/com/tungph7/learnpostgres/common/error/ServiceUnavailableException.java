package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * 503 Service Unavailable (RFC 9110 §15.6.4) — server biết vì sao chưa phục vụ được, và tình trạng
 * đó tạm thời: gửi lại y nguyên sau một lúc là được.
 *
 * <p>Khác 500: không phải bug — vd số liệu báo cáo đang chờ được nạp lại ({@code
 * DASH_REVENUE_NOT_READY}, F07), hay một câu bị hủy vì chạy quá giới hạn thời gian của server
 * ({@code ADM_QUERY_TIMEOUT}, F08 — cùng cách Spring trả 503 cho {@code
 * AsyncRequestTimeoutException}). Khác 409: không xung đột với request nào khác. Luôn kèm lỗi gốc
 * ({@code cause}) để log còn đủ chuỗi nguyên nhân.
 */
public class ServiceUnavailableException extends BusinessException {

  public ServiceUnavailableException(ErrorCode code, String detail, Throwable cause) {
    super(code, HttpStatus.SERVICE_UNAVAILABLE, detail, cause);
  }
}
