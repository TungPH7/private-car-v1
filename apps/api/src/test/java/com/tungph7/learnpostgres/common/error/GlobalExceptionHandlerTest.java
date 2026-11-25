package com.tungph7.learnpostgres.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.tungph7.learnpostgres.common.logging.LoggingProperties;

class GlobalExceptionHandlerTest {

  private final ProblemDetailFactory factory =
      new ProblemDetailFactory("about:blank", new LoggingProperties(null, null, 0));
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler(factory);

  private HttpServletRequest request() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/api/test");
    return req;
  }

  static class FakeBusinessException extends BusinessException {
    FakeBusinessException(ErrorCode code, HttpStatus status, String detail) {
      super(code, status, detail, null);
    }
  }

  @Test
  void handleBusiness_5xx_returns_status_from_exception() {
    BusinessException ex =
        new FakeBusinessException(
            ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "boom");

    ResponseEntity<ProblemDetail> resp = handler.handleBusiness(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
  }

  @Test
  void handleBusiness_4xx_returns_status_from_exception() {
    BusinessException ex = new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "missing");

    ResponseEntity<ProblemDetail> resp = handler.handleBusiness(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "PRODUCT_NOT_FOUND");
  }

  @Test
  void handleNotReadable_returns_400_malformed() {
    HttpInputMessage msg = mock(HttpInputMessage.class);
    HttpMessageNotReadableException ex =
        new HttpMessageNotReadableException(
            "bad json", new RuntimeException("root cause msg"), msg);

    ResponseEntity<ProblemDetail> resp = handler.handleNotReadable(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "MALFORMED_REQUEST");
  }

  @Test
  void handleNotReadable_with_null_cause() {
    HttpInputMessage msg = mock(HttpInputMessage.class);
    HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json", msg);

    ResponseEntity<ProblemDetail> resp = handler.handleNotReadable(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void handleTypeMismatch_returns_400_with_parameter_info() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException(
            "abc", UUID.class, "id", null, new IllegalArgumentException());

    ResponseEntity<ProblemDetail> resp = handler.handleTypeMismatch(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "INVALID_PARAMETER");
    assertThat(resp.getBody().getProperties()).containsEntry("parameter", "id");
  }

  @Test
  void handleTypeMismatch_returns_400_when_requiredType_null() {
    MethodArgumentTypeMismatchException ex =
        new MethodArgumentTypeMismatchException("abc", null, "id", null, new RuntimeException());

    ResponseEntity<ProblemDetail> resp = handler.handleTypeMismatch(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody().getProperties()).containsEntry("expectedType", "unknown");
  }

  @Test
  void handleMethodNotSupported_returns_405_with_Allow_header() {
    HttpRequestMethodNotSupportedException ex =
        new HttpRequestMethodNotSupportedException(
            "PATCH", Set.of("POST", "GET").stream().toList());

    ResponseEntity<ProblemDetail> resp = handler.handleMethodNotSupported(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(resp.getHeaders().get("Allow")).isNotEmpty();
    assertThat(resp.getBody().getProperties()).containsEntry("code", "METHOD_NOT_SUPPORTED");
  }

  @Test
  void handleMethodNotSupported_returns_405_when_supported_null() {
    HttpRequestMethodNotSupportedException ex =
        new HttpRequestMethodNotSupportedException("PATCH") {
          @Override
          public Set<HttpMethod> getSupportedHttpMethods() {
            return null;
          }
        };

    ResponseEntity<ProblemDetail> resp = handler.handleMethodNotSupported(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
  }

  /**
   * Header {@code Content-Type} không parse được: Spring dựng exception không có {@code
   * contentType} — detail không được in "'null'". Trường hợp có {@code Content-Type} chạy trên
   * exception thật ở {@code MediaTypeWebMvcTest}.
   */
  @Test
  void handleMediaTypeNotSupported_when_content_type_is_unparseable() {
    HttpMediaTypeNotSupportedException ex =
        new HttpMediaTypeNotSupportedException(
            "Invalid mime type \";;;\"", List.of(MediaType.APPLICATION_JSON));

    ResponseEntity<ProblemDetail> resp = handler.handleMediaTypeNotSupported(ex, request());

    assertProblem(resp, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    assertThat(resp.getBody().getDetail())
        .isEqualTo("Content-Type không hợp lệ. Gửi body dạng: application/json.");
    assertThat(resp.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
  }

  /** PATCH: header của Spring có thêm {@code Accept-Patch} (RFC 5789 §3.1) — handler giữ nguyên. */
  @Test
  void handleMediaTypeNotSupported_keeps_accept_patch_header() {
    HttpMediaTypeNotSupportedException ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON), HttpMethod.PATCH);

    ResponseEntity<ProblemDetail> resp = handler.handleMediaTypeNotSupported(ex, request());

    assertThat(resp.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    assertThat(resp.getHeaders().getAcceptPatch()).containsExactly(MediaType.APPLICATION_JSON);
  }

  @Test
  void handleMediaTypeNotAcceptable_returns_406_listing_producible_types() {
    HttpMediaTypeNotAcceptableException ex =
        new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));

    ResponseEntity<ProblemDetail> resp = handler.handleMediaTypeNotAcceptable(ex, request());

    assertProblem(resp, HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE);
    assertThat(resp.getBody().getDetail()).endsWith("Kiểu trả được: application/json.");
  }

  @Test
  void handleNoResource_returns_404() throws Exception {
    NoResourceFoundException ex =
        new NoResourceFoundException(HttpMethod.GET, "/api/typo", "/api/typo");

    ResponseEntity<ProblemDetail> resp = handler.handleNoResource(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "RESOURCE_NOT_FOUND");
  }

  @Test
  void handleOptimisticLock_returns_409() {
    ObjectOptimisticLockingFailureException ex =
        new ObjectOptimisticLockingFailureException("Product", "abc");

    ResponseEntity<ProblemDetail> resp = handler.handleOptimisticLock(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "OPTIMISTIC_LOCK_CONFLICT");
  }

  /**
   * Deadlock của Postgres (40P01) tới Spring dưới dạng {@link CannotAcquireLockException}. Không có
   * handler thì nó rơi vào catch-all và client nhận 500 cho một request hợp lệ, gửi lại là xong.
   */
  @Test
  void handleConcurrencyFailure_returns_409() {
    ConcurrencyFailureException ex = new CannotAcquireLockException("deadlock detected");

    ResponseEntity<ProblemDetail> resp = handler.handleConcurrencyFailure(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "CONCURRENT_UPDATE_CONFLICT");
  }

  @Test
  void handleDataIntegrity_returns_409_with_slug_code_when_unique_slug() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper",
            new RuntimeException(
                "duplicate key value violates unique constraint products_slug_unique"));

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrity(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "PRODUCT_SLUG_DUPLICATED");
  }

  @Test
  void handleDataIntegrity_returns_409_with_generic_code_when_other_constraint() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper", new RuntimeException("null value in column violates not-null constraint"));

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrity(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "DATA_INTEGRITY_VIOLATION");
  }

  /** Message nguyên văn của Postgres 17 (SQLSTATE 23503) khi xóa product còn order item. */
  @Test
  void handleDataIntegrity_maps_order_item_product_fk_to_409_with_order_message() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper",
            new RuntimeException(
                "ERROR: update or delete on table \"products\" violates foreign key constraint"
                    + " \"order_items_product_id_fkey\" on table \"order_items\""));

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrity(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "DATA_INTEGRITY_VIOLATION");
    assertThat(resp.getBody().getDetail()).contains("đã có đơn hàng");
  }

  /** 2 dòng cùng product trong một đơn là lỗi của request → 400, không phải 409. */
  @Test
  void handleDataIntegrity_maps_duplicate_order_line_to_400_validation() {
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException(
            "wrapper",
            new RuntimeException(
                "ERROR: duplicate key value violates unique constraint"
                    + " \"uq_order_items_order_product\""));

    ResponseEntity<ProblemDetail> resp = handler.handleDataIntegrity(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
  }

  // ----------------------------------------------------------------
  // F06 Payment — message nguyên văn của Postgres 17 qua pgjdbc ("ERROR: ...\n  Detail: ...")
  // ----------------------------------------------------------------

  private ResponseEntity<ProblemDetail> dataIntegrity(String rootMessage) {
    return handler.handleDataIntegrity(
        new DataIntegrityViolationException("wrapper", new RuntimeException(rootMessage)),
        request());
  }

  private static void assertProblem(
      ResponseEntity<ProblemDetail> resp, HttpStatus status, ErrorCode code) {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(resp.getBody().getProperties()).containsEntry("code", code.code());
  }

  /** 2 lỗi do trigger {@code check_refund_against_payment} (V15) raise, SQLSTATE 23514. */
  @Test
  void handleDataIntegrity_maps_refund_trigger_errors_to_payment_codes() {
    assertProblem(
        dataIntegrity(
            "ERROR: PAY_REFUND_EXCEEDS_PAYMENT: refunds 350.00 > payment amount 300.00"
                + " (payment 01a10098-3a1f-79aa-aa07-cdf867932b11)\n"
                + "  Where: PL/pgSQL function check_refund_against_payment() line 39 at RAISE"),
        HttpStatus.CONFLICT,
        ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT);
    assertProblem(
        dataIntegrity(
            "ERROR: PAY_INVALID_STATE: payment 01a10099-00a0-7e1b-a76b-9c27546dd3b8 is PENDING,"
                + " only CAPTURED or PARTIALLY_REFUNDED can be refunded\n"
                + "  Where: PL/pgSQL function check_refund_against_payment() line 25 at RAISE"),
        HttpStatus.CONFLICT,
        ErrorCode.PAY_INVALID_STATE);
  }

  @Test
  void handleDataIntegrity_maps_payment_unique_constraints() {
    assertProblem(
        dataIntegrity(
            "ERROR: duplicate key value violates unique constraint"
                + " \"uq_payments_reference_gateway\"\n"
                + "  Detail: Key (payment_reference, gateway)=(pi_it_dup, STRIPE) already exists."),
        HttpStatus.CONFLICT,
        ErrorCode.PAY_REFERENCE_TAKEN);
    assertProblem(
        dataIntegrity(
            "ERROR: duplicate key value violates unique constraint"
                + " \"payment_refunds_reference_unique\"\n"
                + "  Detail: Key (refund_reference)=(re_msg_1) already exists."),
        HttpStatus.CONFLICT,
        ErrorCode.PAY_REFERENCE_TAKEN);
    assertProblem(
        dataIntegrity(
            "ERROR: duplicate key value violates unique constraint"
                + " \"uq_payments_order_non_failed\"\n"
                + "  Detail: Key (order_id)=(01a10099-009c-77d9-9c3d-1cc744b8b5a3) already"
                + " exists."),
        HttpStatus.CONFLICT,
        ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT);
  }

  /**
   * Service đi qua ON CONFLICT nên không chạm tới — tới được là có đường ghi bỏ qua idempotency.
   */
  @Test
  void handleDataIntegrity_maps_idempotency_key_duplicates_to_generic_409() {
    for (String constraint :
        List.of("payments_idempotency_key_unique", "payment_refunds_idempotency_key_unique")) {
      ResponseEntity<ProblemDetail> resp =
          dataIntegrity(
              "ERROR: duplicate key value violates unique constraint \""
                  + constraint
                  + "\"\n"
                  + "  Detail: Key (idempotency_key)=(MSG-1) already exists.");

      assertProblem(resp, HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION);
      assertThat(resp.getBody().getDetail()).contains("ON CONFLICT");
    }
  }

  /** Payload gateway: parse được nhưng không phải object (CHECK), hoặc không parse được (22P02). */
  @Test
  void handleDataIntegrity_maps_bad_gateway_payload_to_400() {
    for (String message :
        List.of(
            "ERROR: new row for relation \"payments\" violates check constraint"
                + " \"ck_payments_gateway_response_object\"\n"
                + "  Detail: Failing row contains (01a10099, MSG-PAY-1, [1, 2], PENDING).",
            "ERROR: new row for relation \"payment_refunds\" violates check constraint"
                + " \"ck_refunds_gateway_response_object\"\n"
                + "  Detail: Failing row contains (01a10099, MSG-REF-3, \"{\\\"a\\\": 1}\").",
            "ERROR: invalid input syntax for type json\n"
                + "  Detail: Token \"oops\" is invalid.\n"
                + "  Where: JSON data, line 1: {oops...")) {
      assertProblem(dataIntegrity(message), HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED);
    }
  }

  /**
   * Chỉ dòng đầu được so khớp. Các dòng Detail / Where chép DỮ LIỆU của request; một payload hoặc
   * lý do refund có chữ "unique slug" từng bị resolver catalog ({@code unique && slug}) nhận thành
   * {@code PRODUCT_SLUG_DUPLICATED}.
   */
  @Test
  void handleDataIntegrity_ignores_request_data_after_first_line() {
    assertProblem(
        dataIntegrity(
            "ERROR: invalid input syntax for type json\n"
                + "  Detail: Token \"oops\" is invalid.\n"
                + "  Where: JSON data, line 1: {\"note\": \"unique slug\" oops..."),
        HttpStatus.BAD_REQUEST,
        ErrorCode.VALIDATION_FAILED);
    assertProblem(
        dataIntegrity(
            "ERROR: new row for relation \"payment_refunds\" violates check constraint"
                + " \"ck_refunds_amount_positive\"\n"
                + "  Detail: Failing row contains (01a10099, MSG-REF-4, null, 0.00, unique slug,"
                + " null, PENDING)."),
        HttpStatus.CONFLICT,
        ErrorCode.DATA_INTEGRITY_VIOLATION);
  }

  /**
   * Ký tự NUL trong request: JSON hợp lệ, Jackson đọc qua, nhưng PostgreSQL không lưu được. Hai
   * message nguyên văn lấy từ app chạy trên DB dev (F06 bài 04): cột text ({@code failure_reason})
   * và cột jsonb ({@code gateway_response}). Trước đó cả hai ra 409 {@code
   * DATA_INTEGRITY_VIOLATION}.
   */
  @Test
  void handleDataIntegrity_maps_nul_character_to_400() {
    for (String message :
        List.of(
            "ERROR: invalid byte sequence for encoding \"UTF8\": 0x00\n"
                + "  Where: unnamed portal parameter $1",
            "ERROR: unsupported Unicode escape sequence\n"
                + "  Detail: \\u0000 cannot be converted to text.\n"
                + "  Where: JSON data, line 1: {\"note\":\"a\\u0000...")) {
      ResponseEntity<ProblemDetail> resp = dataIntegrity(message);

      assertProblem(resp, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED);
      assertThat(resp.getBody().getDetail()).contains("NUL");
    }
  }

  /** Draft IETF Idempotency-Key §2.7: key dùng lại cho payload khác → 422. */
  @Test
  void handleBusiness_returns_422_for_reused_idempotency_key() {
    BusinessException ex =
        new UnprocessableContentException(ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED, "key K1");

    ResponseEntity<ProblemDetail> resp = handler.handleBusiness(ex, request());

    assertProblem(resp, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED);
  }

  /**
   * Ràng buộc trên CẢ BỘ tham số không gắn với tham số nào — vẫn phải ra một phần tử {@code
   * errors[]} (field null), không được mất lỗi. Dựng exception bằng tay vì MVC chỉ sinh loại lỗi
   * này khi có constraint cross-parameter tự viết; hai loại còn lại test trên exception thật ở
   * {@code MethodValidationWebMvcTest}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void handleMethodValidation_keeps_cross_parameter_errors_without_field() throws Exception {
    MethodValidationResult result =
        MethodValidationResult.create(
            this,
            Object.class.getMethod("toString"),
            List.of(),
            List.of(
                new DefaultMessageSourceResolvable(
                    new String[] {"ValidRange"}, "from phải trước to")));

    ResponseEntity<ProblemDetail> resp =
        handler.handleMethodValidation(new HandlerMethodValidationException(result), request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    List<Map<String, Object>> errors =
        (List<Map<String, Object>>) resp.getBody().getProperties().get("errors");
    assertThat(errors)
        .singleElement()
        .satisfies(
            error ->
                assertThat(error)
                    .containsEntry("field", null)
                    .containsEntry("message", "from phải trước to"));
  }

  @Test
  void handleAny_returns_500_generic() {
    Exception ex = new RuntimeException("unexpected");

    ResponseEntity<ProblemDetail> resp = handler.handleAny(ex, request());

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(resp.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
  }
}
