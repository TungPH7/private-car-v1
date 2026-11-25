package com.tungph7.learnpostgres.feature.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.error.UnprocessableContentException;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.RequestActor;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus;
import com.tungph7.learnpostgres.feature.payment.domain.RefundStatus;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.mapper.PaymentMapper;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

/**
 * Web layer của 3 controller payment: routing, status code (201 / 200 của 2 thao tác idempotent),
 * header {@code Idempotency-Key}, validation, actor, mapping entity → JSON, và cách lỗi của service
 * hiện ra ngoài HTTP. Service là mock — nghiệp vụ đã có test ở bài 03.
 *
 * <p>Entity không có constructor public, không setter (bài 02 §5): dựng bằng {@link
 * BeanUtils#instantiateClass} + {@link ReflectionTestUtils}, để mapper MapStruct thật đọc qua đúng
 * getter của entity — kể cả {@code id} / {@code createdAt} nằm ở {@code BaseEntity}.
 */
@WebMvcTest({OrderPaymentController.class, PaymentController.class, RefundController.class})
@Import({
  TestSecurityConfig.class,
  GlobalExceptionHandler.class,
  ProblemDetailFactory.class,
  PaymentControllerWebMvcTest.MapperConfig.class
})
@EnableConfigurationProperties(LoggingProperties.class)
class PaymentControllerWebMvcTest {

  private static final UUID ORDER_ID = UUID.fromString("01930000-0000-7000-8000-0000000000d1");
  private static final UUID PAYMENT_ID = UUID.fromString("01930000-0000-7000-8000-0000000000a1");
  private static final UUID REFUND_ID = UUID.fromString("01930000-0000-7000-8000-0000000000b1");
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private static final String PAY_KEY = "pay_key_001";
  private static final String REFUND_KEY = "refund_key_001";
  private static final Instant CREATED_AT = Instant.parse("2026-10-04T03:00:00Z");
  private static final Instant CAPTURED_AT = Instant.parse("2026-10-04T03:05:00Z");

  @Autowired MockMvc mockMvc;

  @MockitoBean PaymentService service;

  /**
   * Mapper MapStruct thật cho slice. Phải {@code @Import} ở class level: các lớp {@code @Nested}
   * dựng context riêng và không tự nhặt {@code @TestConfiguration} của lớp bao ngoài (bẫy F04).
   */
  @TestConfiguration
  static class MapperConfig {
    @Bean
    PaymentMapper paymentMapper() {
      return Mappers.getMapper(PaymentMapper.class);
    }
  }

  // ----------------------------------------------------------------
  // Fixture
  // ----------------------------------------------------------------

  /** Payment 300.00 VND qua STRIPE của {@link #ORDER_ID}, đang ở {@code status}. */
  private static Payment payment(PaymentStatus status) {
    Payment payment = BeanUtils.instantiateClass(Payment.class);
    ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
    ReflectionTestUtils.setField(payment, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(payment, "version", 0L);
    ReflectionTestUtils.setField(payment, "orderId", ORDER_ID);
    ReflectionTestUtils.setField(payment, "idempotencyKey", PAY_KEY);
    ReflectionTestUtils.setField(payment, "amount", new BigDecimal("300.00"));
    ReflectionTestUtils.setField(payment, "currency", "VND");
    ReflectionTestUtils.setField(payment, "gateway", PaymentGateway.STRIPE);
    ReflectionTestUtils.setField(payment, "status", status);
    return payment;
  }

  /** Đã thu tiền: có mã giao dịch, {@code capturedAt} và payload gateway. */
  private static Payment captured() {
    Payment payment = payment(PaymentStatus.CAPTURED);
    ReflectionTestUtils.setField(payment, "paymentReference", "pi_abc_123");
    ReflectionTestUtils.setField(payment, "capturedAt", CAPTURED_AT);
    ReflectionTestUtils.setField(
        payment, "gatewayResponse", "{\"card\": {\"last4\": \"4242\"}, \"email\": \"a@x.com\"}");
    return payment;
  }

  private static Payment failed(String reason) {
    Payment payment = payment(PaymentStatus.FAILED);
    ReflectionTestUtils.setField(payment, "failureReason", reason);
    ReflectionTestUtils.setField(payment, "failedAt", CAPTURED_AT);
    return payment;
  }

  /** Refund 100.00 của {@link #PAYMENT_ID}, đang ở {@code status}. */
  private static PaymentRefund refund(RefundStatus status) {
    PaymentRefund refund = BeanUtils.instantiateClass(PaymentRefund.class);
    ReflectionTestUtils.setField(refund, "id", REFUND_ID);
    ReflectionTestUtils.setField(refund, "paymentId", PAYMENT_ID);
    ReflectionTestUtils.setField(refund, "idempotencyKey", REFUND_KEY);
    ReflectionTestUtils.setField(refund, "amount", new BigDecimal("100.00"));
    ReflectionTestUtils.setField(refund, "reason", "Khach tra 1 mon");
    ReflectionTestUtils.setField(refund, "actor", "ops-01");
    ReflectionTestUtils.setField(refund, "requestedAt", CREATED_AT);
    ReflectionTestUtils.setField(refund, "updatedAt", CREATED_AT);
    ReflectionTestUtils.setField(refund, "status", status);
    if (status == RefundStatus.COMPLETED) {
      ReflectionTestUtils.setField(refund, "refundReference", "re_001");
      ReflectionTestUtils.setField(refund, "completedAt", CAPTURED_AT);
      ReflectionTestUtils.setField(refund, "gatewayResponse", "{\"status\": \"succeeded\"}");
    }
    return refund;
  }

  private static PaymentIntentResult intent(ResultKind kind) {
    return new PaymentIntentResult(
        PAYMENT_ID, "PENDING", new BigDecimal("300.00"), "VND", "STRIPE", kind);
  }

  private static RefundResult refundResult(ResultKind kind) {
    return new RefundResult(REFUND_ID, "PENDING", new BigDecimal("100.00"), kind);
  }

  private static MockHttpServletRequestBuilder jsonPost(String uri, Object... vars) {
    return post(uri, vars).contentType(MediaType.APPLICATION_JSON);
  }

  // ================================================================
  // POST /api/v1/orders/{orderId}/payments
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/orders/{orderId}/payments")
  class CreatePaymentIntent {

    private static final String STRIPE =
        """
        {"gateway":"STRIPE"}
        """;

    private MockHttpServletRequestBuilder create(String body) {
      return jsonPost("/api/v1/orders/{orderId}/payments", ORDER_ID).content(body);
    }

    /** AC-01. */
    @Test
    @DisplayName("NEW → 201 + Location /api/v1/payments/{id}; chưa xác thực → actor = anonymous")
    void newPaymentIsCreated() throws Exception {
      when(service.createPaymentIntent(any(), any(), any(), any()))
          .thenReturn(intent(ResultKind.NEW));

      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, PAY_KEY))
          .andExpect(status().isCreated())
          .andExpect(
              header()
                  .string(
                      HttpHeaders.LOCATION, Matchers.endsWith("/api/v1/payments/" + PAYMENT_ID)))
          .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.currency").value("VND"))
          .andExpect(jsonPath("$.gateway").value("STRIPE"))
          .andExpect(jsonPath("$.kind").value("NEW"))
          // tiền ra JSON nguyên scale — không thành 300.0
          .andExpect(content().string(Matchers.containsString("\"amount\":300.00")));

      verify(service)
          .createPaymentIntent(ORDER_ID, PaymentGateway.STRIPE, PAY_KEY, RequestActor.ANONYMOUS);
    }

    /** AC-02. */
    @Test
    @DisplayName("EXISTING (gửi lặp đúng key) → 200, không có Location")
    void replayReturns200() throws Exception {
      when(service.createPaymentIntent(any(), any(), any(), any()))
          .thenReturn(intent(ResultKind.EXISTING));

      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, PAY_KEY))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
          .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$.kind").value("EXISTING"));
    }

    @Test
    @DisplayName("đã xác thực → actor = tên principal")
    void actorIsThePrincipalName() throws Exception {
      when(service.createPaymentIntent(any(), any(), any(), any()))
          .thenReturn(intent(ResultKind.NEW));

      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, PAY_KEY).with(user("cashier-01")))
          .andExpect(status().isCreated());

      verify(service).createPaymentIntent(ORDER_ID, PaymentGateway.STRIPE, PAY_KEY, "cashier-01");
    }

    /** HTTP/2 hạ mọi tên header xuống chữ thường — server phải coi như nhau. */
    @Test
    @DisplayName("tên header không phân biệt hoa thường: idempotency-key")
    void headerNameIsCaseInsensitive() throws Exception {
      when(service.createPaymentIntent(any(), any(), any(), any()))
          .thenReturn(intent(ResultKind.NEW));

      mockMvc
          .perform(create(STRIPE).header("idempotency-key", PAY_KEY))
          .andExpect(status().isCreated());

      verify(service)
          .createPaymentIntent(ORDER_ID, PaymentGateway.STRIPE, PAY_KEY, RequestActor.ANONYMOUS);
    }

    /** Draft IETF §2.7: thiếu header ở operation bắt buộc có nó → 400. */
    @Test
    @DisplayName("thiếu header Idempotency-Key → 400 INVALID_PARAMETER, nêu tên header")
    void missingIdempotencyKey() throws Exception {
      mockMvc
          .perform(create(STRIPE))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
          .andExpect(jsonPath("$.header").value(IDEMPOTENCY_KEY))
          .andExpect(jsonPath("$.detail").value("Thiếu header bắt buộc 'Idempotency-Key'."));

      verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "Idempotency-Key = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("key rỗng → 400 VALIDATION_FAILED, field = tên header")
    void blankIdempotencyKey(String key) throws Exception {
      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, key))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value(IDEMPOTENCY_KEY));

      verifyNoInteractions(service);
    }

    /** {@code idempotency_key VARCHAR(80)}: 80 ký tự qua, 81 bị chặn trước khi tới DB. */
    @Test
    @DisplayName("key 81 ký tự → 400 ở field Idempotency-Key; 80 ký tự → qua")
    void idempotencyKeyLengthMatchesColumn() throws Exception {
      String key80 = "k".repeat(80);
      when(service.createPaymentIntent(any(), any(), eq(key80), any()))
          .thenReturn(intent(ResultKind.NEW));

      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, key80 + "k"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value(IDEMPOTENCY_KEY))
          .andExpect(jsonPath("$.errors[0].rejectedValue").value(key80 + "k"));
      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, key80))
          .andExpect(status().isCreated());
    }

    /** AC-14: gateway là enum — giá trị lạ làm Jackson không đọc được body. */
    @Test
    @DisplayName("gateway PAYPAL → 400 MALFORMED_REQUEST, không tới service")
    void unknownGateway() throws Exception {
      mockMvc
          .perform(create("{\"gateway\":\"PAYPAL\"}").header(IDEMPOTENCY_KEY, PAY_KEY))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("thiếu gateway → 400 VALIDATION_FAILED ở field gateway")
    void missingGateway() throws Exception {
      mockMvc
          .perform(create("{}").header(IDEMPOTENCY_KEY, PAY_KEY))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("gateway"));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("orderId không phải UUID → 400 INVALID_PARAMETER")
    void malformedOrderId() throws Exception {
      mockMvc
          .perform(
              jsonPost("/api/v1/orders/{orderId}/payments", "khong-phai-uuid")
                  .header(IDEMPOTENCY_KEY, PAY_KEY)
                  .content(STRIPE))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
          .andExpect(jsonPath("$.parameter").value("orderId"));

      verifyNoInteractions(service);
    }

    static Stream<Arguments> serviceErrors() {
      BiFunction<ErrorCode, String, BusinessException> notFound = NotFoundException::new;
      BiFunction<ErrorCode, String, BusinessException> conflict = ConflictException::new;
      BiFunction<ErrorCode, String, BusinessException> unprocessable =
          UnprocessableContentException::new;
      return Stream.of(
          Arguments.of(ErrorCode.ORD_NOT_FOUND, notFound, 404),
          Arguments.of(ErrorCode.PAY_ORDER_NOT_PAYABLE, conflict, 409),
          Arguments.of(ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT, conflict, 409),
          Arguments.of(ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED, unprocessable, 422));
    }

    /** AC-08 (PAY_ORDER_NOT_PAYABLE) và 422 của key dùng lại — controller không bắt, không dịch. */
    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("serviceErrors")
    @DisplayName("lỗi của service giữ nguyên status + code")
    void serviceErrorsKeepStatusAndCode(
        ErrorCode code, BiFunction<ErrorCode, String, BusinessException> exception, int expected)
        throws Exception {
      when(service.createPaymentIntent(any(), any(), any(), any()))
          .thenThrow(exception.apply(code, "detail"));

      mockMvc
          .perform(create(STRIPE).header(IDEMPOTENCY_KEY, PAY_KEY))
          .andExpect(status().is(expected))
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(code.code()));
    }
  }

  // ================================================================
  // GET /api/v1/orders/{orderId}/payments, GET /api/v1/payments/{id}
  // ================================================================

  @Nested
  @DisplayName("GET payment")
  class ReadPayments {

    @Test
    @DisplayName("GET /orders/{orderId}/payments — đúng thứ tự service trả, mọi trạng thái")
    void listForOrder() throws Exception {
      when(service.listPaymentsForOrder(ORDER_ID))
          .thenReturn(List.of(captured(), failed("Card declined")));

      mockMvc
          .perform(get("/api/v1/orders/{orderId}/payments", ORDER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].status").value("CAPTURED"))
          .andExpect(jsonPath("$[1].status").value("FAILED"))
          .andExpect(jsonPath("$[1].failureReason").value("Card declined"));
    }

    @Test
    @DisplayName("GET /orders/{orderId}/payments — order không có → 404, không phải []")
    void listForMissingOrder() throws Exception {
      when(service.listPaymentsForOrder(ORDER_ID))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/orders/{orderId}/payments", ORDER_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_NOT_FOUND.code()));
    }

    /**
     * Mọi field của {@code PaymentResponse}; field null vẫn có mặt. {@code gatewayResponse} (dữ
     * liệu của khách) và {@code version} (luôn 0) không ra API.
     */
    @Test
    @DisplayName("GET /payments/{id} — đủ field, không lộ gatewayResponse / version")
    void getPayment() throws Exception {
      when(service.getById(PAYMENT_ID)).thenReturn(captured());

      mockMvc
          .perform(get("/api/v1/payments/{id}", PAYMENT_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
          .andExpect(jsonPath("$.idempotencyKey").value(PAY_KEY))
          .andExpect(jsonPath("$.paymentReference").value("pi_abc_123"))
          .andExpect(jsonPath("$.currency").value("VND"))
          .andExpect(jsonPath("$.gateway").value("STRIPE"))
          .andExpect(jsonPath("$.status").value("CAPTURED"))
          .andExpect(jsonPath("$.capturedAt").value(CAPTURED_AT.toString()))
          .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
          .andExpect(jsonPath("$").value(Matchers.hasKey("authorizedAt")))
          .andExpect(jsonPath("$.authorizedAt").value(Matchers.nullValue()))
          .andExpect(jsonPath("$.failureReason").value(Matchers.nullValue()))
          .andExpect(content().string(Matchers.containsString("\"amount\":300.00")))
          .andExpect(jsonPath("$.gatewayResponse").doesNotExist())
          .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    @DisplayName("GET /payments/{id} — không có → 404 PAY_NOT_FOUND")
    void getMissingPayment() throws Exception {
      when(service.getById(PAYMENT_ID))
          .thenThrow(new NotFoundException(ErrorCode.PAY_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/payments/{id}", PAYMENT_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_NOT_FOUND.code()));
    }
  }

  // ================================================================
  // POST /api/v1/payments/{id}/confirm
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/payments/{id}/confirm")
  class ConfirmPayment {

    private MockHttpServletRequestBuilder confirm(String body) {
      return jsonPost("/api/v1/payments/{id}/confirm", PAYMENT_ID).content(body);
    }

    /** AC-03. */
    @Test
    @DisplayName("capture → 200, alreadyCaptured = false")
    void captures() throws Exception {
      when(service.confirmPayment(eq(PAYMENT_ID), eq("pi_abc_123"), any()))
          .thenReturn(new ConfirmPaymentResult(PAYMENT_ID, "CAPTURED", false));

      mockMvc
          .perform(confirm("{\"paymentReference\":\"pi_abc_123\",\"gatewayResponse\":{}}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$.status").value("CAPTURED"))
          .andExpect(jsonPath("$.alreadyCaptured").value(false));
    }

    /** AC-04: webhook gửi lặp vẫn 200 — gateway nhận lỗi thì gửi lại mãi. */
    @Test
    @DisplayName("gửi lặp → vẫn 200, alreadyCaptured = true")
    void replayIsAcknowledged() throws Exception {
      when(service.confirmPayment(any(), any(), any()))
          .thenReturn(new ConfirmPaymentResult(PAYMENT_ID, "PARTIALLY_REFUNDED", true));

      mockMvc
          .perform(confirm("{\"paymentReference\":\"pi_abc_123\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
          .andExpect(jsonPath("$.alreadyCaptured").value(true));
    }

    /**
     * Payload tới service đúng từng chữ số. Mặc định Jackson đọc số thập phân trong {@code
     * ObjectNode} thành {@code double} — {@code 405000.00} thành {@code 405000.0}, {@code
     * 1234567.891234567891} thành {@code 1234567.8912345679}. Test này đỏ nếu bỏ {@code
     * spring.jackson.deserialization.use-big-decimal-for-floats} khỏi application.yml.
     */
    @Test
    @DisplayName("gatewayResponse tới service là JSON object, số thập phân giữ nguyên từng chữ số")
    void gatewayResponseKeepsExactNumbers() throws Exception {
      String payload =
          "{\"transactionId\":\"txn_001\",\"amount\":405000.00,"
              + "\"rate\":1234567.891234567891,\"items\":[1,2.50]}";
      when(service.confirmPayment(any(), any(), any()))
          .thenReturn(new ConfirmPaymentResult(PAYMENT_ID, "CAPTURED", false));

      mockMvc
          .perform(
              confirm(
                  "{\"paymentReference\":\"pi_abc_123\",\"gatewayResponse\":%s}"
                      .formatted(payload)))
          .andExpect(status().isOk());

      ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
      verify(service).confirmPayment(eq(PAYMENT_ID), eq("pi_abc_123"), json.capture());
      assertThat(json.getValue()).isEqualTo(payload);
    }

    @Test
    @DisplayName("không gửi gatewayResponse → service nhận null")
    void gatewayResponseIsOptional() throws Exception {
      when(service.confirmPayment(any(), any(), any()))
          .thenReturn(new ConfirmPaymentResult(PAYMENT_ID, "CAPTURED", false));

      mockMvc.perform(confirm("{\"paymentReference\":\"pi_abc_123\"}")).andExpect(status().isOk());

      verify(service).confirmPayment(eq(PAYMENT_ID), eq("pi_abc_123"), isNull());
    }

    /**
     * Chuỗi chứa JSON đã escape (cách của bản kế hoạch) cũng bị chặn: đó là kiểu lỗi CHECK {@code
     * ck_payments_gateway_response_object} của V15 canh ở DB.
     */
    @ParameterizedTest(name = "gatewayResponse = {0}")
    @ValueSource(strings = {"[1,2]", "\"{\\\"a\\\":1}\"", "123", "true"})
    @DisplayName(
        "gatewayResponse không phải JSON object → 400 MALFORMED_REQUEST, không tới service")
    void gatewayResponseMustBeAnObject(String value) throws Exception {
      mockMvc
          .perform(
              confirm(
                  "{\"paymentReference\":\"pi_abc_123\",\"gatewayResponse\":%s}".formatted(value)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));

      verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "paymentReference dài {0}")
    @ValueSource(ints = {0, 81})
    @DisplayName("paymentReference rỗng / dài hơn VARCHAR(80) → 400 VALIDATION_FAILED")
    void paymentReferenceMustFitColumn(int length) throws Exception {
      mockMvc
          .perform(confirm("{\"paymentReference\":\"%s\"}".formatted("r".repeat(length))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("paymentReference"));

      verifyNoInteractions(service);
    }

    /** AC-12. */
    @Test
    @DisplayName("payment FAILED → 409 PAY_INVALID_STATE")
    void failedPaymentConflicts() throws Exception {
      when(service.confirmPayment(any(), any(), any()))
          .thenThrow(new ConflictException(ErrorCode.PAY_INVALID_STATE, "đang FAILED"));

      mockMvc
          .perform(confirm("{\"paymentReference\":\"pi_abc_123\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_INVALID_STATE.code()));
    }
  }

  // ================================================================
  // POST /api/v1/payments/{id}/fail
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/payments/{id}/fail")
  class FailPayment {

    private MockHttpServletRequestBuilder fail(String body) {
      return jsonPost("/api/v1/payments/{id}/fail", PAYMENT_ID).content(body);
    }

    @Test
    @DisplayName("200 + payment sau khi chuyển: FAILED, failureReason, failedAt")
    void returnsTheFailedPayment() throws Exception {
      when(service.markPaymentFailed(PAYMENT_ID, "Card declined"))
          .thenReturn(failed("Card declined"));

      mockMvc
          .perform(fail("{\"reason\":\"Card declined\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("FAILED"))
          .andExpect(jsonPath("$.failureReason").value("Card declined"))
          .andExpect(jsonPath("$.failedAt").value(CAPTURED_AT.toString()));
    }

    @ParameterizedTest(name = "reason = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("reason rỗng → 400 VALIDATION_FAILED, không tới service")
    void rejectsBlankReason(String reason) throws Exception {
      mockMvc
          .perform(fail("{\"reason\":\"%s\"}".formatted(reason)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("reason"));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("reason 4001 ký tự → 400")
    void rejectsTooLongReason() throws Exception {
      mockMvc
          .perform(fail("{\"reason\":\"%s\"}".formatted("x".repeat(4001))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("reason"));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("payment đã thu tiền → 409 PAY_INVALID_STATE")
    void capturedPaymentCannotFail() throws Exception {
      when(service.markPaymentFailed(any(), anyString()))
          .thenThrow(new ConflictException(ErrorCode.PAY_INVALID_STATE, "đã thu tiền"));

      mockMvc
          .perform(fail("{\"reason\":\"Timeout\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_INVALID_STATE.code()));
    }
  }

  // ================================================================
  // POST /api/v1/payments/{id}/refunds
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/payments/{id}/refunds")
  class RequestRefund {

    private static final String HALF =
        """
        {"amount":150,"reason":"Khach tra 1 mon"}
        """;

    private MockHttpServletRequestBuilder requestRefund(String body) {
      return jsonPost("/api/v1/payments/{id}/refunds", PAYMENT_ID).content(body);
    }

    /** AC-05 (phần tạo refund). */
    @Test
    @DisplayName("NEW → 201 + Location /api/v1/refunds/{id}; actor = anonymous")
    void newRefundIsCreated() throws Exception {
      when(service.requestRefund(any(), any(), any(), any(), any()))
          .thenReturn(refundResult(ResultKind.NEW));

      mockMvc
          .perform(requestRefund(HALF).header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isCreated())
          .andExpect(
              header()
                  .string(HttpHeaders.LOCATION, Matchers.endsWith("/api/v1/refunds/" + REFUND_ID)))
          .andExpect(jsonPath("$.refundId").value(REFUND_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.kind").value("NEW"))
          .andExpect(content().string(Matchers.containsString("\"amount\":100.00")));

      ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
      verify(service)
          .requestRefund(
              eq(PAYMENT_ID),
              amount.capture(),
              eq("Khach tra 1 mon"),
              eq(REFUND_KEY),
              eq(RequestActor.ANONYMOUS));
      assertThat(amount.getValue()).isEqualByComparingTo("150");
    }

    /** AC-07. */
    @Test
    @DisplayName("EXISTING → 200, không có Location")
    void replayReturns200() throws Exception {
      when(service.requestRefund(any(), any(), any(), any(), any()))
          .thenReturn(refundResult(ResultKind.EXISTING));

      mockMvc
          .perform(requestRefund(HALF).header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
          .andExpect(jsonPath("$.kind").value("EXISTING"));
    }

    @Test
    @DisplayName("đã xác thực → actor = tên principal")
    void actorIsThePrincipalName() throws Exception {
      when(service.requestRefund(any(), any(), any(), any(), any()))
          .thenReturn(refundResult(ResultKind.NEW));

      mockMvc
          .perform(requestRefund(HALF).header(IDEMPOTENCY_KEY, REFUND_KEY).with(user("ops-01")))
          .andExpect(status().isCreated());

      verify(service)
          .requestRefund(
              eq(PAYMENT_ID), any(), eq("Khach tra 1 mon"), eq(REFUND_KEY), eq("ops-01"));
    }

    @Test
    @DisplayName("thiếu header Idempotency-Key → 400 INVALID_PARAMETER")
    void missingIdempotencyKey() throws Exception {
      mockMvc
          .perform(requestRefund(HALF))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
          .andExpect(jsonPath("$.header").value(IDEMPOTENCY_KEY));

      verifyNoInteractions(service);
    }

    /**
     * {@code @Digits} đếm chữ số lẻ đúng như gửi: {@code 100.500} bị chặn dù service nhận nó (so
     * sau {@code stripTrailingZeros}). {@code 10000000000}: 11 chữ số phần nguyên, quá {@code
     * NUMERIC(12,2)}.
     */
    @ParameterizedTest(name = "amount = {0}")
    @ValueSource(strings = {"0", "-1", "0.001", "100.005", "100.500", "10000000000"})
    @DisplayName("amount ≤ 0, quá 2 chữ số lẻ, quá 10 chữ số nguyên → 400 ở field amount")
    void rejectsAmountOutsideColumn(String amount) throws Exception {
      mockMvc
          .perform(
              requestRefund("{\"amount\":%s,\"reason\":\"x\"}".formatted(amount))
                  .header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("amount"));

      verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "amount = {0}")
    @ValueSource(strings = {"0.01", "100.5", "9999999999.99"})
    @DisplayName("amount ở biên hợp lệ → tới service")
    void acceptsAmountAtBounds(String amount) throws Exception {
      when(service.requestRefund(any(), any(), any(), any(), any()))
          .thenReturn(refundResult(ResultKind.NEW));

      mockMvc
          .perform(
              requestRefund("{\"amount\":%s,\"reason\":\"x\"}".formatted(amount))
                  .header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("thiếu amount → 400 ở field amount")
    void rejectsMissingAmount() throws Exception {
      mockMvc
          .perform(requestRefund("{\"reason\":\"x\"}").header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("amount"));

      verifyNoInteractions(service);
    }

    /** {@code payment_refunds.reason VARCHAR(255)}. */
    @ParameterizedTest(name = "reason dài {0}")
    @ValueSource(ints = {0, 256})
    @DisplayName("reason rỗng / dài hơn VARCHAR(255) → 400 ở field reason")
    void reasonMustFitColumn(int length) throws Exception {
      mockMvc
          .perform(
              requestRefund("{\"amount\":150,\"reason\":\"%s\"}".formatted("x".repeat(length)))
                  .header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("reason"));

      verifyNoInteractions(service);
    }

    static Stream<Arguments> serviceErrors() {
      BiFunction<ErrorCode, String, BusinessException> notFound = NotFoundException::new;
      BiFunction<ErrorCode, String, BusinessException> conflict = ConflictException::new;
      BiFunction<ErrorCode, String, BusinessException> unprocessable =
          UnprocessableContentException::new;
      return Stream.of(
          Arguments.of(ErrorCode.PAY_NOT_FOUND, notFound, 404),
          Arguments.of(ErrorCode.PAY_INVALID_STATE, conflict, 409),
          Arguments.of(ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT, conflict, 409),
          Arguments.of(ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED, unprocessable, 422));
    }

    /** AC-10 / AC-11 (vượt số đã thu) và 422 của key dùng lại. */
    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("serviceErrors")
    @DisplayName("lỗi của service giữ nguyên status + code")
    void serviceErrorsKeepStatusAndCode(
        ErrorCode code, BiFunction<ErrorCode, String, BusinessException> exception, int expected)
        throws Exception {
      when(service.requestRefund(any(), any(), any(), any(), any()))
          .thenThrow(exception.apply(code, "detail"));

      mockMvc
          .perform(requestRefund(HALF).header(IDEMPOTENCY_KEY, REFUND_KEY))
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.code").value(code.code()));
    }
  }

  // ================================================================
  // GET /api/v1/payments/{id}/refunds, GET /api/v1/refunds/{id}
  // ================================================================

  @Nested
  @DisplayName("GET refund")
  class ReadRefunds {

    @Test
    @DisplayName(
        "GET /payments/{id}/refunds — đủ field của RefundResponse, không có gatewayResponse")
    void listForPayment() throws Exception {
      when(service.getRefundsForPayment(PAYMENT_ID))
          .thenReturn(List.of(refund(RefundStatus.COMPLETED), refund(RefundStatus.PENDING)));

      mockMvc
          .perform(get("/api/v1/payments/{id}/refunds", PAYMENT_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].id").value(REFUND_ID.toString()))
          .andExpect(jsonPath("$[0].paymentId").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$[0].idempotencyKey").value(REFUND_KEY))
          .andExpect(jsonPath("$[0].refundReference").value("re_001"))
          .andExpect(jsonPath("$[0].reason").value("Khach tra 1 mon"))
          .andExpect(jsonPath("$[0].status").value("COMPLETED"))
          .andExpect(jsonPath("$[0].requestedAt").value(CREATED_AT.toString()))
          .andExpect(jsonPath("$[0].completedAt").value(CAPTURED_AT.toString()))
          .andExpect(jsonPath("$[0].actor").value("ops-01"))
          .andExpect(jsonPath("$[0].gatewayResponse").doesNotExist())
          .andExpect(jsonPath("$[1].status").value("PENDING"))
          .andExpect(jsonPath("$[1].completedAt").value(Matchers.nullValue()))
          .andExpect(content().string(Matchers.containsString("\"amount\":100.00")));
    }

    @Test
    @DisplayName("GET /payments/{id}/refunds — payment không có → 404, không phải []")
    void listForMissingPayment() throws Exception {
      when(service.getRefundsForPayment(PAYMENT_ID))
          .thenThrow(new NotFoundException(ErrorCode.PAY_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/payments/{id}/refunds", PAYMENT_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_NOT_FOUND.code()));
    }

    /** Đích của {@code Location} khi tạo refund. */
    @Test
    @DisplayName("GET /refunds/{id} — một refund")
    void getRefund() throws Exception {
      when(service.getRefund(REFUND_ID)).thenReturn(refund(RefundStatus.PENDING));

      mockMvc
          .perform(get("/api/v1/refunds/{id}", REFUND_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(REFUND_ID.toString()))
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$").value(Matchers.hasKey("refundReference")))
          .andExpect(jsonPath("$.refundReference").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET /refunds/{id} — không có → 404 PAY_REFUND_NOT_FOUND")
    void getMissingRefund() throws Exception {
      when(service.getRefund(REFUND_ID))
          .thenThrow(new NotFoundException(ErrorCode.PAY_REFUND_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/refunds/{id}", REFUND_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_REFUND_NOT_FOUND.code()));
    }
  }

  // ================================================================
  // POST /api/v1/refunds/{id}/confirm
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/refunds/{id}/confirm")
  class ConfirmRefund {

    private MockHttpServletRequestBuilder confirm(String body) {
      return jsonPost("/api/v1/refunds/{id}/confirm", REFUND_ID).content(body);
    }

    /** AC-05 / AC-06: body có status mới của payment, tính lại theo tổng refund COMPLETED. */
    @Test
    @DisplayName("COMPLETED → 200 kèm paymentStatus; payload tới service đúng từng chữ số")
    void completes() throws Exception {
      String payload = "{\"refundId\":\"re_001\",\"amount\":150.00}";
      when(service.confirmRefund(any(), any(), any()))
          .thenReturn(
              new ConfirmRefundResult(
                  REFUND_ID, "COMPLETED", PAYMENT_ID, "PARTIALLY_REFUNDED", false));

      mockMvc
          .perform(
              confirm("{\"refundReference\":\"re_001\",\"gatewayResponse\":%s}".formatted(payload)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.refundId").value(REFUND_ID.toString()))
          .andExpect(jsonPath("$.status").value("COMPLETED"))
          .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
          .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"))
          .andExpect(jsonPath("$.alreadyCompleted").value(false));

      verify(service).confirmRefund(REFUND_ID, "re_001", payload);
    }

    @Test
    @DisplayName("gửi lặp → vẫn 200, alreadyCompleted = true")
    void replayIsAcknowledged() throws Exception {
      when(service.confirmRefund(any(), any(), any()))
          .thenReturn(
              new ConfirmRefundResult(REFUND_ID, "COMPLETED", PAYMENT_ID, "REFUNDED", true));

      mockMvc
          .perform(confirm("{\"refundReference\":\"re_001\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.alreadyCompleted").value(true));

      verify(service).confirmRefund(eq(REFUND_ID), eq("re_001"), isNull());
    }

    @Test
    @DisplayName("refundReference rỗng → 400; gatewayResponse là mảng → 400 MALFORMED_REQUEST")
    void rejectsInvalidBody() throws Exception {
      mockMvc
          .perform(confirm("{\"refundReference\":\"\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("refundReference"));
      mockMvc
          .perform(confirm("{\"refundReference\":\"re_001\",\"gatewayResponse\":[]}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("mã refund đã thuộc refund khác → 409 PAY_REFERENCE_TAKEN")
    void referenceTaken() throws Exception {
      when(service.confirmRefund(any(), any(), any()))
          .thenThrow(new ConflictException(ErrorCode.PAY_REFERENCE_TAKEN, "đã dùng"));

      mockMvc
          .perform(confirm("{\"refundReference\":\"re_001\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.PAY_REFERENCE_TAKEN.code()));
    }
  }
}
