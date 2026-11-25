package com.tungph7.learnpostgres.common.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Handler exception toàn cục cho REST API.
 *
 * <p>Bao phủ: 1) BusinessException + subclass (custom 4xx/5xx). 2) Spring framework exceptions phổ
 * biến. 3) JPA/Hibernate exceptions (optimistic lock, data integrity). 4) Catch-all Exception → 500
 * không leak.
 *
 * <p>Logging policy (xem file 01 mục 4): - 4xx: WARN, không stacktrace. - 5xx: ERROR, có
 * stacktrace.
 *
 * <p>Body luôn application/problem+json (Spring tự set khi return ProblemDetail).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ProblemDetailFactory problemFactory;

  public GlobalExceptionHandler(ProblemDetailFactory problemFactory) {
    this.problemFactory = problemFactory;
  }

  // =================================================================
  // 1. Business exceptions (4xx do logic; 5xx hiếm)
  // =================================================================

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ProblemDetail> handleBusiness(
      BusinessException ex, HttpServletRequest request) {
    if (ex.getStatus().is5xxServerError()) {
      log.error(
          "Business 5xx [{}] at {}: {}", ex.getCode(), request.getRequestURI(), ex.getDetail(), ex);
    } else {
      log.warn(
          "Business {} [{}] at {}: {}",
          ex.getStatus().value(),
          ex.getCode(),
          request.getRequestURI(),
          ex.getDetail());
    }
    ProblemDetail body = problemFactory.from(ex, request);
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  // =================================================================
  // 2. Spring framework exceptions
  // =================================================================

  /**
   * Body JSON không parse được (sai cú pháp, kiểu sai, missing required). Thường do client gửi
   * `price: "abc"` thay vì number.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    log.warn("Malformed request body at {}: {}", request.getRequestURI(), rootMessage(ex));
    // KHÔNG echo ex.getMessage() — Jackson message đôi khi tiết lộ field internal.
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.MALFORMED_REQUEST,
            HttpStatus.BAD_REQUEST,
            "Request body could not be read. Check JSON syntax and field types.",
            request);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * @PathVariable / @RequestParam sai kiểu (vd UUID không hợp lệ ở /products/{id}).
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    String paramName = ex.getName();
    Object rejectedValue = ex.getValue();
    String expectedType =
        ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
    String detail =
        "Parameter '%s' has invalid value '%s' (expected: %s)."
            .formatted(paramName, rejectedValue, expectedType);

    log.warn("Type mismatch at {}: {}", request.getRequestURI(), detail);

    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("parameter", paramName);
    extra.put("rejectedValue", rejectedValue);
    extra.put("expectedType", expectedType);

    ProblemDetail body =
        problemFactory.from(
            ErrorCode.INVALID_PARAMETER, HttpStatus.BAD_REQUEST, detail, request, extra);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * Thiếu một giá trị bắt buộc của request: {@code @RequestParam}, {@code @RequestHeader},
   * {@code @CookieValue}, ... Client gọi thiếu tham số là lỗi 400, không phải 500.
   *
   * <p>Bắt ở {@link MissingRequestValueException} (lớp cha) thay vì riêng {@code
   * MissingServletRequestParameterException}: mọi kiểu "thiếu giá trị" đều cùng một câu trả lời, và
   * không phải thêm handler mỗi lần dùng tới một nguồn giá trị mới.
   *
   * <p>Hai nguồn hay gặp có detail riêng kèm tên của giá trị thiếu: query param, và header (F06:
   * {@code Idempotency-Key} — draft IETF §2.7 yêu cầu đúng 400 cho trường hợp này). Message gốc của
   * Spring nhắc cả kiểu tham số Java ("for method parameter type String") — chi tiết cài đặt, không
   * đưa ra client.
   */
  @ExceptionHandler(MissingRequestValueException.class)
  public ResponseEntity<ProblemDetail> handleMissingRequestValue(
      MissingRequestValueException ex, HttpServletRequest request) {
    String detail = "Thiếu giá trị bắt buộc của request: " + ex.getMessage();

    log.warn("Missing request value at {}: {}", request.getRequestURI(), ex.getMessage());

    Map<String, Object> extra = new LinkedHashMap<>();
    if (ex instanceof MissingServletRequestParameterException missingParam) {
      extra.put("parameter", missingParam.getParameterName());
      extra.put("expectedType", missingParam.getParameterType());
      detail =
          "Thiếu tham số bắt buộc '%s' (%s)."
              .formatted(missingParam.getParameterName(), missingParam.getParameterType());
    } else if (ex instanceof MissingRequestHeaderException missingHeader) {
      extra.put("header", missingHeader.getHeaderName());
      detail = "Thiếu header bắt buộc '%s'.".formatted(missingHeader.getHeaderName());
    }

    ProblemDetail body =
        problemFactory.from(
            ErrorCode.INVALID_PARAMETER, HttpStatus.BAD_REQUEST, detail, request, extra);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * Bean Validation thất bại (folder 07 sẽ thêm @Valid + annotation). Folder 06 viết sẵn để folder
   * 07 plug-in không cần sửa lại.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<Map<String, Object>> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe -> validationError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
            .toList();

    log.warn(
        "Validation failed at {}: {} field error(s)", request.getRequestURI(), fieldErrors.size());

    return validationFailed(fieldErrors, request);
  }

  /**
   * Ràng buộc đặt thẳng lên tham số của method controller, vd {@code @Min(0) int page}.
   *
   * <p>Từ Spring 6.1, MVC tự validate các tham số đó và ném {@link
   * HandlerMethodValidationException} — một lớp KHÁC {@link MethodArgumentNotValidException}. Thiếu
   * handler này thì nó rơi xuống catch-all: {@code GET /api/v1/customers?page=-1} (F02) đã trả 500
   * cho một lỗi rõ ràng của client.
   *
   * <p>Cùng mã, cùng hình dạng {@code errors[]} với {@link #handleValidation} để client chỉ xử lý
   * một kiểu. {@link ParameterErrors} là tham số {@code @Valid @RequestBody} /
   * {@code @ModelAttribute} của một method CŨNG có ràng buộc trực tiếp: khi đó Spring gộp lỗi của
   * body vào exception này thay vì ném {@code MethodArgumentNotValidException}, nên vẫn báo theo
   * từng field.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ProblemDetail> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    List<Map<String, Object>> errors =
        Stream.concat(
                ex.getParameterValidationResults().stream()
                    .flatMap(GlobalExceptionHandler::parameterErrors),
                // Ràng buộc trên CẢ BỘ tham số (hiếm) — không gắn với field nào.
                ex.getCrossParameterValidationResults().stream()
                    .map(error -> validationError(null, null, error.getDefaultMessage())))
            .toList();

    log.warn(
        "Parameter validation failed at {}: {} error(s)", request.getRequestURI(), errors.size());

    return validationFailed(errors, request);
  }

  private static Stream<Map<String, Object>> parameterErrors(ParameterValidationResult result) {
    if (result instanceof ParameterErrors bodyErrors) {
      return bodyErrors.getFieldErrors().stream()
          .map(fe -> validationError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()));
    }
    String parameter = requestName(result.getMethodParameter());
    return result.getResolvableErrors().stream()
        .map(error -> validationError(parameter, result.getArgument(), error.getDefaultMessage()));
  }

  /**
   * Tên của giá trị như CLIENT gửi: tên khai trong {@code @RequestHeader} / {@code @RequestParam} /
   * {@code @PathVariable}, không phải tên biến Java. {@code @RequestHeader("Idempotency-Key")
   * String idempotencyKey} báo {@code Idempotency-Key} — client không có cách nào biết tên {@code
   * idempotencyKey}.
   *
   * <p>Đọc {@code name()} là đủ dù code khai bằng {@code value}: tham số của handler method là
   * {@code SynthesizingMethodParameter}, Spring điền alias {@code name} ↔ {@code value} cho nhau.
   * Annotation không khai tên thì Spring cũng lấy tên biến Java làm tên trên request, nên quay về
   * tên đó là đúng.
   */
  private static String requestName(MethodParameter parameter) {
    String declared = "";
    if (parameter.getParameterAnnotation(RequestHeader.class) instanceof RequestHeader header) {
      declared = header.name();
    } else if (parameter.getParameterAnnotation(RequestParam.class) instanceof RequestParam param) {
      declared = param.name();
    } else if (parameter.getParameterAnnotation(PathVariable.class) instanceof PathVariable path) {
      declared = path.name();
    }
    return declared.isEmpty() ? parameter.getParameterName() : declared;
  }

  /**
   * Một phần tử của {@code errors[]} — schema {@code ProblemDetail} trong OpenAPI khai đúng 3 key.
   */
  private static Map<String, Object> validationError(
      String field, Object rejectedValue, String message) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("field", field);
    error.put("rejectedValue", rejectedValue);
    error.put("message", message);
    return error;
  }

  private ResponseEntity<ProblemDetail> validationFailed(
      List<Map<String, Object>> errors, HttpServletRequest request) {
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.VALIDATION_FAILED,
            HttpStatus.BAD_REQUEST,
            "Validation failed for %d field(s).".formatted(errors.size()),
            request,
            Map.of("errors", errors));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * HTTP method không được method handler nào support (vd PATCH /api/products khi controller chỉ có
   * POST/GET/PUT/DELETE).
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    String supported =
        ex.getSupportedHttpMethods() != null ? ex.getSupportedHttpMethods().toString() : "[]";
    String detail = "Method %s not supported. Supported: %s.".formatted(ex.getMethod(), supported);

    log.warn("Method not supported at {}: {}", request.getRequestURI(), detail);

    ProblemDetail body =
        problemFactory.from(
            ErrorCode.METHOD_NOT_SUPPORTED, HttpStatus.METHOD_NOT_ALLOWED, detail, request);
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .header("Allow", supported.replaceAll("[\\[\\]]", ""))
        .body(body);
  }

  /**
   * Header {@code Accept} không nhận kiểu nào endpoint trả được — vd {@code Accept: text/csv} cho
   * một endpoint JSON. 406 (RFC 9110 §15.5.7); body vẫn là problem+json, Spring ghi {@link
   * ProblemDetail} bằng kiểu đó dù {@code Accept} không nhận.
   *
   * <p>Spring chỉ biết lỗi này lúc ghi kết quả — method của controller đã chạy xong. Thiếu handler
   * này thì exception rơi xuống catch-all: 500 kèm log ERROR cho một lỗi của client.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    String detail =
        "Không trả được kiểu nào trong header Accept. Kiểu trả được: %s."
            .formatted(MediaType.toString(ex.getSupportedMediaTypes()));

    log.warn(
        "Not acceptable at {}: Accept={}", request.getRequestURI(), request.getHeader("Accept"));

    ProblemDetail body =
        problemFactory.from(ErrorCode.NOT_ACCEPTABLE, HttpStatus.NOT_ACCEPTABLE, detail, request);
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(body);
  }

  /**
   * Body gửi bằng {@code Content-Type} mà endpoint không đọc được. Hay gặp nhất: {@code curl -d
   * '{...}'} không kèm {@code -H 'Content-Type: application/json'} — curl gửi {@code
   * application/x-www-form-urlencoded}.
   *
   * <p>415 (RFC 9110 §15.5.16) kèm header {@code Accept} liệt kê kiểu đọc được — lấy từ {@link
   * HttpMediaTypeNotSupportedException#getHeaders()}, đúng header Spring tự đặt khi không có
   * handler riêng. Thiếu handler này thì exception rơi xuống catch-all: 500 kèm log ERROR. {@code
   * getContentType()} là {@code null} khi header {@code Content-Type} không parse được.
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    String received =
        ex.getContentType() == null
            ? "Content-Type không hợp lệ."
            : "Content-Type '%s' không đọc được.".formatted(ex.getContentType());
    String detail =
        "%s Gửi body dạng: %s."
            .formatted(received, MediaType.toString(ex.getSupportedMediaTypes()));

    log.warn("Unsupported media type at {}: {}", request.getRequestURI(), detail);

    ProblemDetail body =
        problemFactory.from(
            ErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail, request);
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .headers(ex.getHeaders())
        .body(body);
  }

  /**
   * Path không match handler nào. - Spring 6.1+: throw NoResourceFoundException (khi
   * add-mappings=false). - Spring < 6.1 hoặc throw-exception-if-no-handler-found=true: throw
   * NoHandlerFoundException. Bắt cả hai để tương thích mọi cấu hình.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ProblemDetail> handleNoResource(Exception ex, HttpServletRequest request) {
    log.warn("No handler at {}: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.RESOURCE_NOT_FOUND,
            HttpStatus.NOT_FOUND,
            "No resource at %s.".formatted(request.getRequestURI()),
            request);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  // =================================================================
  // 3. JPA / Hibernate exceptions
  // =================================================================

  /**
   * Optimistic lock conflict — entity đã được sửa bởi transaction khác. Map → 409. (Đã promised ở
   * folder 05 — giờ thực hiện.)
   */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLock(
      ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
    log.warn(
        "Optimistic lock conflict at {}: entity={} id={}",
        request.getRequestURI(),
        ex.getPersistentClassName(),
        ex.getIdentifier());
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
            HttpStatus.CONFLICT,
            "Resource was modified by another transaction. " + "Please reload and retry.",
            request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * Deadlock (40P01) hoặc serialization failure (40001) — Postgres huỷ transaction để phá vòng đợi.
   *
   * <p>Không có handler thì rơi vào catch-all và client nhận <b>500</b>, tức là API nói "lỗi
   * server" cho một tình huống mà request hoàn toàn hợp lệ và <em>gửi lại là xong</em>. F04 gặp
   * thật: hai request cùng tạo campaign trùng khoảng thời gian, cả hai kịp INSERT trước khi bên nào
   * kiểm tra xong EXCLUDE, thế là đợi vòng tròn — xem {@code
   * CouponConcurrencyIntegrationTest.overlappingCouponsRaceIsDecidedByExclude}.
   *
   * <p>Bắt ở {@link ConcurrencyFailureException} (cha của {@code CannotAcquireLockException},
   * {@code DeadlockLoserDataAccessException}, {@code PessimisticLockingFailureException}) thay vì
   * từng lớp con. {@code ObjectOptimisticLockingFailureException} cũng là con của nó nhưng có
   * handler riêng ở trên — Spring chọn handler khớp gần nhất trong cây thừa kế nên không đụng nhau.
   *
   * <p>409 chứ không 503: tài nguyên đang tranh chấp, không phải service quá tải.
   */
  @ExceptionHandler(ConcurrencyFailureException.class)
  public ResponseEntity<ProblemDetail> handleConcurrencyFailure(
      ConcurrencyFailureException ex, HttpServletRequest request) {
    log.warn("Concurrency failure at {}: {}", request.getRequestURI(), rootMessage(ex), ex);
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.CONCURRENT_UPDATE_CONFLICT,
            HttpStatus.CONFLICT,
            "Request conflicted with a concurrent transaction. Please retry.",
            request);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * UNIQUE constraint violation, FK violation, NOT NULL violation, ... Thử detect cụ thể (UNIQUE →
   * 409, others → 409 generic).
   *
   * <p>KHÔNG echo ex.getMessage() — Postgres error message chứa SQL fragment + tên constraint
   * internal.
   *
   * <p>Bảng nhận diện tách theo feature ({@link #RESOLVERS}) thay vì một chuỗi if/else dài: chuỗi
   * đó dài thêm theo mỗi feature mới và đã vượt giới hạn độ dài method của Checkstyle khi F04 thêm
   * vào. Tên constraint không trùng nhau giữa các feature nên thứ tự gọi không ảnh hưởng kết quả.
   *
   * <p>Resolver chỉ nhìn DÒNG ĐẦU của message — nơi Postgres đặt tên constraint hoặc mã lỗi do
   * trigger raise. Các dòng sau ({@code Detail: Failing row contains (...)}, {@code Where: JSON
   * data, line 1: ...}) chép lại DỮ LIỆU của request: so khớp trên đó thì một payload có chữ
   * "unique slug" bị nhận thành {@code PRODUCT_SLUG_DUPLICATED}. Log vẫn ghi đủ.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    String rootMsg = rootMessage(ex).toLowerCase(Locale.ROOT);
    String primaryLine = rootMsg.lines().findFirst().orElse("");

    ConstraintMapping mapping =
        RESOLVERS.stream()
            .map(resolver -> resolver.apply(primaryLine))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(UNRECOGNISED_CONSTRAINT);

    // Status lấy từ ErrorCode: VALIDATION_FAILED → 400, còn lại → 409.
    HttpStatus status = mapping.code().defaultStatus();

    log.warn(
        "Data integrity violation at {}: code={} root={}",
        request.getRequestURI(),
        mapping.code(),
        rootMsg);

    ProblemDetail body = problemFactory.from(mapping.code(), status, mapping.detail(), request);
    return ResponseEntity.status(status).body(body);
  }

  /** Constraint nhận diện được → mã lỗi + câu giải thích cho client. */
  private record ConstraintMapping(ErrorCode code, String detail) {}

  private static final ConstraintMapping UNRECOGNISED_CONSTRAINT =
      new ConstraintMapping(ErrorCode.DATA_INTEGRITY_VIOLATION, "Data integrity violation.");

  /** Thêm feature mới: viết một resolver rồi thêm một dòng vào đây. */
  private static final List<Function<String, ConstraintMapping>> RESOLVERS =
      List.of(
          GlobalExceptionHandler::resolveRequestCharacter,
          GlobalExceptionHandler::resolveCatalogConstraint,
          GlobalExceptionHandler::resolveCustomerConstraint,
          GlobalExceptionHandler::resolveInventoryConstraint,
          GlobalExceptionHandler::resolveCouponConstraint,
          GlobalExceptionHandler::resolveOrderConstraint,
          GlobalExceptionHandler::resolvePaymentConstraint);

  /**
   * Ký tự NUL (mã 0) trong dữ liệu request — không thuộc feature nào.
   *
   * <p>JSON hợp lệ được phép chứa NUL trong chuỗi (escape u0000) và Jackson đọc qua bình thường,
   * nhưng PostgreSQL không lưu được ký tự này: cột văn bản báo {@code invalid byte sequence for
   * encoding "UTF8": 0x00} (22021), cột {@code jsonb} báo {@code unsupported Unicode escape
   * sequence} (22P05). Cả hai là lỗi của dữ liệu client gửi → 400, không phải 409 chung chung.
   */
  private static ConstraintMapping resolveRequestCharacter(String rootMsg) {
    if (rootMsg.contains("invalid byte sequence for encoding")
        || rootMsg.contains("unsupported unicode escape sequence")) {
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED,
          "Dữ liệu gửi lên chứa ký tự NUL (mã 0) — PostgreSQL không lưu được ký tự này.");
    }
    return null;
  }

  /** F01 Catalog + Product. */
  private static ConstraintMapping resolveCatalogConstraint(String rootMsg) {
    if (rootMsg.contains("cat_cycle_detected")) {
      return new ConstraintMapping(
          ErrorCode.CAT_CYCLE_DETECTED, "Move này tạo cycle trong cây category.");
    }
    if (rootMsg.contains("categories_slug_unique")) {
      return new ConstraintMapping(ErrorCode.CAT_SLUG_TAKEN, "Slug category đã tồn tại.");
    }
    if (rootMsg.contains("ck_categories_self_parent")) {
      return new ConstraintMapping(
          ErrorCode.CAT_SELF_PARENT, "Category không thể là parent của chính nó.");
    }
    if (rootMsg.contains("products_category_id_fkey")
        || rootMsg.contains("categories_parent_id_fkey")) {
      return new ConstraintMapping(
          ErrorCode.CAT_HAS_REFERENCES,
          "Không xóa được — còn product hoặc category con tham chiếu.");
    }
    if (rootMsg.contains("products_slug_unique")
        || (rootMsg.contains("unique") && rootMsg.contains("slug"))) {
      return new ConstraintMapping(
          ErrorCode.PRODUCT_SLUG_DUPLICATED, "Product slug already exists.");
    }
    return null;
  }

  /** F02 Customer + Address. */
  private static ConstraintMapping resolveCustomerConstraint(String rootMsg) {
    if (rootMsg.contains("customers_email_unique")) {
      return new ConstraintMapping(ErrorCode.CUS_EMAIL_TAKEN, "Email đã được đăng ký.");
    }
    if (rootMsg.contains("uq_customers_phone_active")) {
      return new ConstraintMapping(
          ErrorCode.CUS_PHONE_TAKEN, "Số điện thoại đã được dùng bởi tài khoản khác.");
    }
    if (rootMsg.contains("uq_addresses_default_per_type")) {
      // Internal error — service phải đi qua swapDefault, không set is_default trực tiếp.
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION, "Đã có địa chỉ default cho type này.");
    }
    if (rootMsg.contains("ck_customers_email_format")) {
      return new ConstraintMapping(ErrorCode.VALIDATION_FAILED, "Email không đúng định dạng.");
    }
    if (rootMsg.contains("ck_customers_phone_e164")
        || rootMsg.contains("ck_addresses_phone_e164")) {
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED, "Phone phải theo định dạng E.164 (+CC...).");
    }
    return null;
  }

  /**
   * F03 Inventory.
   *
   * <p>Các CHECK này là safety net cuối. Đường đi bình thường (atomic UPDATE có WHERE) đã chặn hết
   * rồi, nên chạm tới đây nghĩa là request hợp lệ về cú pháp nhưng xung đột với tồn kho hiện tại —
   * điển hình là adjust() truyền delta quá tay.
   *
   * <p>Vì là xung đột STATE (đổi số lượng đi thì thành công) nên trả 409, không phải 400. 400
   * INV_NEGATIVE_QUANTITY để dành cho input tự nó đã vô nghĩa: qty không dương.
   */
  private static ConstraintMapping resolveInventoryConstraint(String rootMsg) {
    if (rootMsg.contains("ck_inventory_reserved_le_on_hand")) {
      return new ConstraintMapping(
          ErrorCode.INV_INSUFFICIENT_STOCK, "Vi phạm invariant reserved <= on_hand.");
    }
    if (rootMsg.contains("ck_inventory_on_hand_non_neg")
        || rootMsg.contains("ck_inventory_reserved_non_neg")) {
      return new ConstraintMapping(
          ErrorCode.INV_INSUFFICIENT_STOCK,
          "Thao tác vượt quá tồn kho hiện có — on_hand/reserved sẽ bị âm.");
    }
    if (rootMsg.contains("ck_inventory_sold_non_neg")
        || rootMsg.contains("ck_inventory_reorder_non_neg")) {
      // Khác nhóm trên: giá trị truyền vào tự nó vô nghĩa, không phải do state.
      return new ConstraintMapping(
          ErrorCode.INV_NEGATIVE_QUANTITY,
          "Giá trị không hợp lệ — sold và reorder_point không được âm.");
    }
    if (rootMsg.contains("ck_inv_mov_type")
        || rootMsg.contains("ck_inv_mov_snapshot_non_neg")
        || rootMsg.contains("ck_inv_mov_actor_non_blank")) {
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION, "Movement log không hợp lệ.");
    }
    if (rootMsg.contains("inventory_movements_product_id_fkey")) {
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION,
          "Không xóa được product — còn movement log tham chiếu.");
    }
    return null;
  }

  /**
   * F04 Coupon.
   *
   * <p>EXCLUDE constraint là đường duy nhất phát hiện overlap: service KHÔNG pre-check bằng SELECT
   * vì giữa SELECT và INSERT luôn có khoảng hở cho request khác chen vào. Để DB quyết rồi dịch lỗi
   * ở đây là cách duy nhất không có race.
   */
  private static ConstraintMapping resolveCouponConstraint(String rootMsg) {
    if (rootMsg.contains("ex_coupons_code_no_overlap")) {
      return new ConstraintMapping(
          ErrorCode.CPN_PERIOD_OVERLAPS,
          "Đã có coupon cùng code đang active trùng khoảng thời gian này.");
    }
    if (rootMsg.contains("ck_coupons_value")) {
      return new ConstraintMapping(
          ErrorCode.CPN_INVALID_VALUE,
          "Value không hợp lệ cho type: PERCENT trong (0,100], AMOUNT dương, FREE_SHIPPING bằng"
              + " 0.");
    }
    if (rootMsg.contains("ck_coupons_code_format")) {
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED,
          "Code phải viết hoa, chỉ gồm A-Z 0-9 _ - và dài 3-40 ký tự.");
    }
    if (rootMsg.contains("ck_coupons_period_bounded")) {
      // Bean Validation + TstzRange đã chặn from >= to trước khi tới DB, nên chạm nhánh
      // này nghĩa là range rỗng hoặc vô hạn — chỉ xảy ra khi ghi bằng SQL thô.
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED,
          "Khoảng hiệu lực phải có đủ 2 đầu và bắt đầu trước khi kết thúc.");
    }
    if (rootMsg.contains("ck_coupons_used_le_max") || rootMsg.contains("ck_coupons_used_count")) {
      return new ConstraintMapping(
          ErrorCode.CPN_EXHAUSTED, "Counter lượt dùng vượt quá giới hạn của coupon.");
    }
    if (rootMsg.contains("ck_coupons_max_discount")
        || rootMsg.contains("ck_coupons_min_order")
        || rootMsg.contains("ck_coupons_max_uses")
        || rootMsg.contains("ck_coupons_max_per_customer")
        || rootMsg.contains("ck_coupons_type")) {
      return new ConstraintMapping(ErrorCode.VALIDATION_FAILED, "Tham số coupon không hợp lệ.");
    }
    if (rootMsg.contains("uq_coupon_redemption_order")) {
      // Đường đi bình thường dùng insertIfAbsent (ON CONFLICT DO NOTHING) nên không bao
      // giờ chạm tới đây. Tới được = có đường ghi khác bỏ qua idempotency → lỗi của app.
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION,
          "Redemption trùng (coupon, order) — đường ghi này đã bỏ qua idempotency.");
    }
    if (rootMsg.contains("ck_coupon_redemption_discount_non_neg")) {
      return new ConstraintMapping(ErrorCode.VALIDATION_FAILED, "Số tiền giảm không được âm.");
    }
    if (rootMsg.contains("coupon_redemptions_coupon_id_fkey")) {
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION,
          "Không xóa được coupon — còn lịch sử redeem tham chiếu. Hãy deactivate thay vì xóa.");
    }
    if (rootMsg.contains("coupon_redemptions_customer_id_fkey")) {
      return new ConstraintMapping(
          ErrorCode.CUS_NOT_FOUND, "Customer của redemption không tồn tại.");
    }
    return null;
  }

  /**
   * F05 Order.
   *
   * <p>Hai constraint là lỗi phía client, đáng một câu giải thích riêng:
   *
   * <ul>
   *   <li>{@code order_items_product_id_fkey} — xóa product đã nằm trong đơn; FK RESTRICT giữ lịch
   *       sử đơn hàng. Ở luồng thật, product đã vào đơn luôn có movement RESERVE, và Postgres báo
   *       {@code inventory_movements_product_id_fkey} (F03) trước: trigger RI chạy theo thứ tự tạo,
   *       V9 có trước V13. Entry này bắt trường hợp còn lại — order item còn mà movement không còn.
   *   <li>{@code uq_order_items_order_product} — một đơn có 2 dòng cùng product. {@code placeOrder}
   *       đã chặn trước khi ghi, nên chạm tới đây nghĩa là có đường ghi khác bỏ qua bước kiểm đó.
   * </ul>
   *
   * <p>Các CHECK còn lại của V12–V14 (total khớp công thức, timestamp khớp status, ...) chỉ vi phạm
   * được khi code app sai — để rơi xuống {@code DATA_INTEGRITY_VIOLATION} chung.
   */
  private static ConstraintMapping resolveOrderConstraint(String rootMsg) {
    if (rootMsg.contains("order_items_product_id_fkey")) {
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION,
          "Không xóa được product — đã có đơn hàng chứa nó, lịch sử đơn phải giữ.");
    }
    if (rootMsg.contains("uq_order_items_order_product")) {
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED,
          "Mỗi product chỉ được có một dòng trong đơn — gộp số lượng vào một dòng.");
    }
    return null;
  }

  /**
   * F06 Payment.
   *
   * <ul>
   *   <li>Hai mã đầu là message do trigger {@code check_refund_against_payment} (V15) raise — cùng
   *       cách F01 bắt {@code CAT_CYCLE_DETECTED}. {@code PAY_REFUND_EXCEEDS_PAYMENT} là chốt THẬT
   *       của tổng refund: service không tự cộng. {@code PAY_INVALID_STATE} thì service đã kiểm
   *       dưới khóa nên không chạm tới — entry này cho đường ghi khác.
   *   <li>Mã giao dịch trùng (UNIQUE của reference): service không kiểm trước, vì UNIQUE mới là
   *       trọng tài — kiểm trước chỉ thêm một khe race.
   *   <li>{@code uq_payments_order_non_failed} và UNIQUE của idempotency key: service đi qua {@code
   *       INSERT ... ON CONFLICT DO NOTHING} nên không chạm tới. Tới được là có đường ghi bỏ qua
   *       idempotency.
   *   <li>Payload gateway không phải JSON object: {@code 22P02} khi không parse được, CHECK khi
   *       parse được nhưng là mảng / chuỗi. Lỗi của request → 400.
   * </ul>
   */
  private static ConstraintMapping resolvePaymentConstraint(String rootMsg) {
    if (rootMsg.contains("pay_refund_exceeds_payment")) {
      return new ConstraintMapping(
          ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT,
          "Tổng refund đang giữ tiền (PENDING + COMPLETED) vượt số tiền đã thu của payment.");
    }
    if (rootMsg.contains("pay_invalid_state")) {
      return new ConstraintMapping(
          ErrorCode.PAY_INVALID_STATE,
          "Chỉ refund được payment đã thu tiền (CAPTURED / PARTIALLY_REFUNDED).");
    }
    if (rootMsg.contains("uq_payments_reference_gateway")
        || rootMsg.contains("payment_refunds_reference_unique")) {
      return new ConstraintMapping(
          ErrorCode.PAY_REFERENCE_TAKEN,
          "Mã giao dịch của gateway đã gắn với một payment / refund khác.");
    }
    if (rootMsg.contains("uq_payments_order_non_failed")) {
      return new ConstraintMapping(
          ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT, "Order đã có một payment chưa FAILED.");
    }
    if (rootMsg.contains("payments_idempotency_key_unique")
        || rootMsg.contains("payment_refunds_idempotency_key_unique")) {
      return new ConstraintMapping(
          ErrorCode.DATA_INTEGRITY_VIOLATION,
          "Idempotency key trùng — đường ghi này đã bỏ qua INSERT ... ON CONFLICT DO NOTHING.");
    }
    if (rootMsg.contains("ck_payments_gateway_response_object")
        || rootMsg.contains("ck_refunds_gateway_response_object")
        || rootMsg.contains("invalid input syntax for type json")) {
      return new ConstraintMapping(
          ErrorCode.VALIDATION_FAILED, "gatewayResponse phải là một JSON object hợp lệ.");
    }
    return null;
  }

  // =================================================================
  // 4. Catch-all
  // =================================================================

  /**
   * Mọi exception khác — coi là bug app, trả 500 generic không leak. LUÔN log full stacktrace +
   * correlation-id để team tìm log.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleAny(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception at {}", request.getRequestURI(), ex);
    ProblemDetail body =
        problemFactory.from(
            ErrorCode.INTERNAL_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please contact support with the correlationId.",
            request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  // =================================================================
  // Helpers
  // =================================================================

  /** Tìm message của root cause — dùng cho LOG (không echo client). */
  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
  }
}
