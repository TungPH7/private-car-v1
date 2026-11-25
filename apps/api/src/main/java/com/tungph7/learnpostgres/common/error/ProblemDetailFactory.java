package com.tungph7.learnpostgres.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.tungph7.learnpostgres.common.logging.LoggingProperties;

// [09] BỎ: import java.util.UUID;   ← không còn PLACEHOLDER_CORRELATION_ID

/**
 * Build ProblemDetail (RFC 7807) thống nhất.
 *
 * <p>Mọi @ExceptionHandler đi qua đây để đảm bảo body có cùng schema: - 5 field chuẩn RFC 7807
 * (type, title, status, detail, instance) - 4 extension: code, timestamp, correlationId, properties
 * (lazy)
 *
 * <p>[08] `type` được build từ `app.errors.base-url` + `ErrorCode.code()`. Nếu base-url ==
 * "about:blank" (vd unit test) thì fallback ABOUT_BLANK.
 *
 * <p>[09] correlationId đọc từ MDC theo key cấu hình trong `LoggingProperties`
 * (`app.logging.correlation-id-mdc-key`). Fallback "no-correlation-id" khi gọi ngoài request thread
 * (vd: scheduled task, async pool).
 */
@Component
public class ProblemDetailFactory {

  private static final URI ABOUT_BLANK = URI.create("about:blank");

  // [09] BỎ: MDC_CORRELATION_ID_KEY  ← key giờ đọc từ loggingProps.correlationIdMdcKey()
  // [09] BỎ: PLACEHOLDER_CORRELATION_ID (UUID toàn 0)  ← thay bằng chuỗi rõ ràng dưới đây

  /** [09] Khi MDC chưa có (gọi ngoài request thread, vd thread pool / scheduler). */
  private static final String NO_CORRELATION = "no-correlation-id";

  // [08] Base URL của doc errors, inject từ application.yml (`app.errors.base-url`).
  //      Default "about:blank" để test/local không cần config vẫn chạy được.
  private final URI errorBaseUri;

  // [09] Inject LoggingProperties để biết MDC key động.
  private final LoggingProperties loggingProps;

  // [09] Constructor thêm tham số LoggingProperties (Spring auto-wire).
  public ProblemDetailFactory(
      @Value("${app.errors.base-url:about:blank}") String baseUrl, LoggingProperties loggingProps) {
    this.errorBaseUri = "about:blank".equals(baseUrl) ? ABOUT_BLANK : URI.create(baseUrl);
    this.loggingProps = loggingProps; // [09]
  }

  /** Build từ BusinessException (4xx & business 5xx). */
  public ProblemDetail from(BusinessException ex, HttpServletRequest request) {
    ProblemDetail pd =
        newProblemDetail(
            ex.getStatus(),
            ex.getCode().defaultTitle(),
            ex.getDetail(),
            request,
            ex.getCode()); // [08] truyền code để build type URI
    pd.setProperty("code", ex.getCode().code());
    ex.getProperties().forEach(pd::setProperty);
    return pd;
  }

  /**
   * Build với code + status tường minh (cho framework exceptions không extend BusinessException, vd
   * HttpMessageNotReadableException).
   */
  public ProblemDetail from(
      ErrorCode code, HttpStatus status, String detail, HttpServletRequest request) {
    ProblemDetail pd =
        newProblemDetail(status, code.defaultTitle(), detail, request, code); // [08] truyền code
    pd.setProperty("code", code.code());
    return pd;
  }

  /** Build với extra extension properties (vd field errors cho 400 validation). */
  public ProblemDetail from(
      ErrorCode code,
      HttpStatus status,
      String detail,
      HttpServletRequest request,
      Map<String, Object> extraProperties) {
    ProblemDetail pd = from(code, status, detail, request);
    if (extraProperties != null) {
      extraProperties.forEach(pd::setProperty);
    }
    return pd;
  }

  // -----------------------------------------------------------------
  // Internals
  // -----------------------------------------------------------------

  // [08] Thêm tham số `ErrorCode code` để biết build type URI nào.
  private ProblemDetail newProblemDetail(
      HttpStatus status, String title, String detail, HttpServletRequest request, ErrorCode code) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(typeUriFor(code)); // [08] thay ABOUT_BLANK bằng URI thật
    pd.setTitle(title);
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("timestamp", Instant.now().toString());
    pd.setProperty("correlationId", currentCorrelationId());
    return pd;
  }

  // [08] Build URI doc cho 1 ErrorCode.
  //      base-url = "about:blank"            → ABOUT_BLANK (giữ hành vi cũ).
  //      base-url = "https://docs.../errors" → "https://docs.../errors/PRODUCT_NOT_FOUND".
  private URI typeUriFor(ErrorCode code) {
    if (ABOUT_BLANK.equals(errorBaseUri)) {
      return ABOUT_BLANK;
    }
    return URI.create(errorBaseUri + "/" + code.code());
  }

  /**
   * [09] Đọc correlationId từ MDC theo key cấu hình. - In-request: CorrelationIdFilter (file 03) đã
   * `MDC.put(...)` → trả ID thật. - Out-of-request (scheduler, async pool): MDC rỗng → trả
   * "no-correlation-id".
   */
  private String currentCorrelationId() {
    return Optional.ofNullable(MDC.get(loggingProps.correlationIdMdcKey())) // [09] key động
        .orElse(NO_CORRELATION); // [09] chuỗi rõ ràng
  }
}
