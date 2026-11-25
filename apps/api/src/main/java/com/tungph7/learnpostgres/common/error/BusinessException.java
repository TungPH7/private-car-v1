package com.tungph7.learnpostgres.common.error;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Abstract base cho mọi business exception.
 *
 * <p>Subclass: - NotFoundException (404) - ConflictException (409) - BadRequestException (400) -
 * ForbiddenException (403) - UnprocessableContentException (422) - ServiceUnavailableException
 * (503) - (mở rộng khi cần)
 *
 * <p>Carries: - `code` (ErrorCode) — machine-readable. - `status` (HttpStatus) — mặc định lấy từ
 * `code.defaultStatus()`, có thể override (rare). - `detail` — message human-readable, ĐƯỢC echo
 * trong response (4xx an toàn). - `properties` — extension members tuỳ ý (vd `{ "field": "slug",
 * "rejectedValue": "..." }`).
 *
 * <p>Extends RuntimeException — không bắt unchecked. Phát hiện ở compile khi quên handle = false
 * positive (Spring bắt qua @ExceptionHandler), nên unchecked OK.
 */
public abstract class BusinessException extends RuntimeException {

  private final ErrorCode code;
  private final HttpStatus status;
  private final Map<String, Object> properties;

  protected BusinessException(ErrorCode code, String detail) {
    this(code, code.defaultStatus(), detail, null);
  }

  protected BusinessException(ErrorCode code, String detail, Throwable cause) {
    this(code, code.defaultStatus(), detail, cause);
  }

  protected BusinessException(ErrorCode code, HttpStatus status, String detail, Throwable cause) {
    super(detail, cause);
    this.code = code;
    this.status = status;
    this.properties = new HashMap<>();
  }

  /**
   * Add extra property — sẽ được copy vào ProblemDetail extension members. Vd:
   * putProperty("rejectedSlug", "abc-123").
   */
  public BusinessException putProperty(String key, Object value) {
    properties.put(key, value);
    return this;
  }

  public ErrorCode getCode() {
    return code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getDetail() {
    return getMessage();
  }

  public Map<String, Object> getProperties() {
    return Collections.unmodifiableMap(properties);
  }
}
