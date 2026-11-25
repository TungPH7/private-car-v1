package com.tungph7.learnpostgres.feature.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.ForbiddenException;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.domain.ShippingSnapshot;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemResponse;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.order.mapper.OrderMapper;
import com.tungph7.learnpostgres.feature.order.service.OrderService;

/**
 * Web layer của 2 controller order: routing, mapping, status code, validation, actor, và cách lỗi
 * của service hiện ra ngoài HTTP. Service là mock — nghiệp vụ đã có test ở bài 03.
 */
@WebMvcTest({OrderController.class, CustomerOrderController.class})
@Import({
  TestSecurityConfig.class,
  GlobalExceptionHandler.class,
  ProblemDetailFactory.class,
  OrderControllerWebMvcTest.MapperConfig.class
})
@EnableConfigurationProperties(LoggingProperties.class)
class OrderControllerWebMvcTest {

  private static final UUID ORDER_ID = UUID.fromString("01930000-0000-7000-8000-0000000000d1");
  private static final UUID CUSTOMER_ID = UUID.fromString("01930000-0000-7000-8000-0000000c0501");
  private static final UUID ADDRESS_ID = UUID.fromString("01930000-0000-7000-8000-0000000add01");
  private static final UUID PRODUCT_ID = UUID.fromString("01930000-0000-7000-8000-0000000b0001");
  private static final UUID COUPON_ID = UUID.fromString("01930000-0000-7000-8000-00000000c0de");
  private static final String ORDER_NUMBER = "ORD-202609-000042";
  private static final Instant CREATED_AT = Instant.parse("2026-09-28T03:00:00Z");

  @Autowired MockMvc mockMvc;

  @MockitoBean OrderService service;

  /**
   * Mapper MapStruct thật cho slice.
   *
   * <p>Phải @Import ở class level: các lớp @Nested dựng context riêng và KHÔNG tự nhặt
   * {@code @TestConfiguration} của lớp bao ngoài (bẫy đã gặp ở F04).
   */
  @TestConfiguration
  static class MapperConfig {
    @Bean
    OrderMapper orderMapper() {
      return Mappers.getMapper(OrderMapper.class);
    }
  }

  /** Đơn 300.000 − 30.000 (coupon) + 0 ship, đang ở {@code status}. */
  private static Order order(OrderStatus status) {
    Order order =
        new Order(
            ORDER_NUMBER,
            CUSTOMER_ID,
            ADDRESS_ID,
            new ShippingSnapshot("Nguyen Van A", "+84901234567", "1 Le Loi", null, "HCM", "VN"),
            new BigDecimal("300000.00"),
            new BigDecimal("30000.00"),
            BigDecimal.ZERO,
            COUPON_ID);
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(order, "version", 0L);
    ReflectionTestUtils.setField(order, "status", status);
    order.setCustomerNote("Giao giờ hành chính");
    order.setInternalNote("Khách VIP — ghi chú nội bộ");
    return order;
  }

  private static String placeBody(String items) {
    return """
           {"customerId":"%s","shippingAddressId":"%s","items":%s}
           """
        .formatted(CUSTOMER_ID, ADDRESS_ID, items);
  }

  private static String oneItem(int quantity) {
    return """
           [{"productId":"%s","quantity":%d}]
           """
        .formatted(PRODUCT_ID, quantity);
  }

  // ================================================================
  // POST /api/v1/orders
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/orders")
  class PlaceOrder {

    @Test
    @DisplayName("201 + Location, body là OrderResponse; tiền giữ đủ 2 chữ số thập phân")
    void createdWithLocation() throws Exception {
      when(service.placeOrder(any())).thenReturn(order(OrderStatus.PENDING));

      mockMvc
          .perform(
              post("/api/v1/orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(placeBody(oneItem(3))))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", Matchers.endsWith("/api/v1/orders/" + ORDER_ID)))
          .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
          .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
          // enum → String bằng chuyển đổi có sẵn của MapStruct
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.total").value(270000.00))
          .andExpect(jsonPath("$.couponId").value(COUPON_ID.toString()))
          .andExpect(jsonPath("$.customerNote").value("Giao giờ hành chính"))
          .andExpect(jsonPath("$.version").value(0))
          // BigDecimal ra JSON nguyên scale — không thành 300000.0
          .andExpect(content().string(Matchers.containsString("\"subtotal\":300000.00")))
          // Ghi chú nội bộ không ra API
          .andExpect(jsonPath("$.internalNote").doesNotExist());
    }

    /** AC-10. */
    @Test
    @DisplayName("items rỗng → 400 VALIDATION_FAILED, không tới service")
    void rejectsEmptyItems() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(placeBody("[]")))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("items"));

      verifyNoInteractions(service);
    }

    /**
     * {@code List<@NotNull OrderItemRequest>}: thiếu {@code @NotNull} ở type argument thì phần tử
     * {@code null} lọt qua {@code @Valid} và nổ NPE trong service (bài 03 §3.2).
     */
    @Test
    @DisplayName("items: [null] → 400 ở items[0], không tới service")
    void rejectsNullItem() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(placeBody("[null]")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("items[0]"));

      verifyNoInteractions(service);
    }

    /** AC-11. */
    @Test
    @DisplayName("quantity = 0 → 400 ở items[0].quantity")
    void rejectsZeroQuantity() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(placeBody(oneItem(0))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"))
          .andExpect(jsonPath("$.errors[0].rejectedValue").value(0));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("không có body → 400 MALFORMED_REQUEST")
    void rejectsMissingBody() throws Exception {
      mockMvc
          .perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));
    }

    static Stream<Arguments> placementFailures() {
      BiFunction<ErrorCode, String, BusinessException> notFound = NotFoundException::new;
      BiFunction<ErrorCode, String, BusinessException> forbidden = ForbiddenException::new;
      BiFunction<ErrorCode, String, BusinessException> badRequest = BadRequestException::new;
      BiFunction<ErrorCode, String, BusinessException> conflict = ConflictException::new;
      return Stream.of(
          Arguments.of("AC-12", ErrorCode.CUS_NOT_FOUND, notFound, 404),
          Arguments.of("AC-13", ErrorCode.ORD_ADDRESS_FORBIDDEN, forbidden, 403),
          Arguments.of("AC-14", ErrorCode.ORD_ADDRESS_INVALID, badRequest, 400),
          Arguments.of("AC-15", ErrorCode.ORD_PRODUCT_INACTIVE, badRequest, 400),
          Arguments.of("AC-16", ErrorCode.CPN_EXPIRED, badRequest, 400),
          Arguments.of("AC-17", ErrorCode.INV_INSUFFICIENT_STOCK, conflict, 409),
          Arguments.of("ACTIVE", ErrorCode.ORD_CUSTOMER_INACTIVE, forbidden, 403));
    }

    /**
     * Status HTTP là status của exception service ném, {@code code} là mã của nó — controller không
     * bắt, không dịch lại. Cách service chọn exception cho từng AC đã test ở {@code
     * OrderPlacementTest}.
     */
    @ParameterizedTest(name = "{0}: {1} → {3}")
    @MethodSource("placementFailures")
    @DisplayName("lỗi nghiệp vụ của service giữ nguyên status + code")
    void businessErrorsKeepStatusAndCode(
        String ac,
        ErrorCode code,
        BiFunction<ErrorCode, String, BusinessException> exception,
        int expectedStatus)
        throws Exception {
      when(service.placeOrder(any())).thenThrow(exception.apply(code, ac));

      mockMvc
          .perform(
              post("/api/v1/orders")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(placeBody(oneItem(1))))
          .andExpect(status().is(expectedStatus))
          .andExpect(jsonPath("$.code").value(code.code()))
          .andExpect(jsonPath("$.correlationId").exists());
    }
  }

  // ================================================================
  // GET
  // ================================================================

  @Nested
  @DisplayName("GET /api/v1/orders/...")
  class Read {

    @Test
    @DisplayName("GET /{id} — tóm tắt, cùng hình dạng với response đặt đơn")
    void getById() throws Exception {
      when(service.getById(ORDER_ID)).thenReturn(order(OrderStatus.PAID));

      mockMvc
          .perform(get("/api/v1/orders/{id}", ORDER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PAID"))
          .andExpect(jsonPath("$.shippingAddressId").value(ADDRESS_ID.toString()))
          .andExpect(jsonPath("$.internalNote").doesNotExist())
          .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id} — không có → 404 ORD_NOT_FOUND")
    void getByIdNotFound() throws Exception {
      when(service.getById(ORDER_ID))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/orders/{id}", ORDER_ID))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_NOT_FOUND.code()));
    }

    @Test
    @DisplayName("GET /{id} — id không phải UUID → 400 INVALID_PARAMETER")
    void rejectsMalformedId() throws Exception {
      mockMvc
          .perform(get("/api/v1/orders/{id}", "khong-phai-uuid"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
          .andExpect(jsonPath("$.parameter").value("id"));

      verifyNoInteractions(service);
    }

    /** Controller trả thẳng DTO của service — không qua mapper, không đổi số. */
    @Test
    @DisplayName("GET /{id}/detail — items + history có kiểu, tiền giữ scale")
    void getDetail() throws Exception {
      when(service.getDetail(ORDER_ID)).thenReturn(detail());

      mockMvc
          .perform(get("/api/v1/orders/{id}/detail", ORDER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.orderNumber").value(ORDER_NUMBER))
          .andExpect(jsonPath("$.customerEmail").value("a@example.com"))
          .andExpect(jsonPath("$.couponCode").value("SUM10"))
          .andExpect(jsonPath("$.items[0].productSku").value("SKU-1"))
          .andExpect(jsonPath("$.items[0].quantity").value(3))
          .andExpect(content().string(Matchers.containsString("\"unitPrice\":100000.00")))
          .andExpect(jsonPath("$.history[0].toStatus").value("PENDING"));
    }

    @Test
    @DisplayName("GET /by-number/{orderNumber}")
    void getByOrderNumber() throws Exception {
      when(service.getByOrderNumber(ORDER_NUMBER)).thenReturn(order(OrderStatus.PENDING));

      mockMvc
          .perform(get("/api/v1/orders/by-number/{n}", ORDER_NUMBER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
    }

    /**
     * {@code /by-number/{orderNumber}} và {@code /{id}/detail} cùng khớp {@code /by-number/detail}.
     * {@code PathPattern} chọn mẫu cụ thể hơn — test chốt rằng đó là by-number, không phải {@code
     * {id} = "by-number"} (sẽ ra 400 vì không phải UUID).
     */
    @Test
    @DisplayName("GET /by-number/detail — route by-number thắng /{id}/detail")
    void byNumberWinsOverDetailRoute() throws Exception {
      when(service.getByOrderNumber("detail"))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/orders/by-number/detail"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_NOT_FOUND.code()));

      verify(service).getByOrderNumber("detail");
      verify(service, never()).getDetail(any());
    }

    /**
     * Dòng khởi tạo có {@code fromStatus = null}. Mapper dùng chuyển đổi enum có sẵn (có
     * null-check); một {@code expression = "java(x.getFromStatus().name())"} sẽ NPE ngay đây.
     */
    @Test
    @DisplayName("GET /{id}/history — dòng khởi tạo có fromStatus = null, cũ nhất trước")
    void historyStartsWithNullFromStatus() throws Exception {
      when(service.getStatusHistory(ORDER_ID))
          .thenReturn(
              List.of(
                  history(null, OrderStatus.PENDING, "Order placed", CUSTOMER_ID.toString()),
                  history(OrderStatus.PENDING, OrderStatus.PAID, "Payment received", "anonymous")));

      mockMvc
          .perform(get("/api/v1/orders/{id}/history", ORDER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          // có mặt, giá trị null — không bị bỏ khỏi JSON
          .andExpect(jsonPath("$[0]").value(Matchers.hasKey("fromStatus")))
          .andExpect(jsonPath("$[0].fromStatus").value(Matchers.nullValue()))
          .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
          .andExpect(jsonPath("$[0].actor").value(CUSTOMER_ID.toString()))
          .andExpect(jsonPath("$[1].fromStatus").value("PENDING"))
          .andExpect(jsonPath("$[1].toStatus").value("PAID"))
          .andExpect(jsonPath("$[1].orderId").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}/history — đơn không có → 404, không phải []")
    void historyOfMissingOrder() throws Exception {
      when(service.getStatusHistory(ORDER_ID))
          .thenThrow(new NotFoundException(ErrorCode.ORD_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/orders/{id}/history", ORDER_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_NOT_FOUND.code()));
    }

    private static OrderStatusHistory history(
        OrderStatus from, OrderStatus to, String reason, String actor) {
      OrderStatusHistory row = new OrderStatusHistory(ORDER_ID, from, to, reason, actor);
      ReflectionTestUtils.setField(row, "changedAt", CREATED_AT);
      return row;
    }

    private static OrderDetailResponse detail() {
      return new OrderDetailResponse(
          ORDER_ID,
          ORDER_NUMBER,
          "PENDING",
          new BigDecimal("300000.00"),
          new BigDecimal("30000.00"),
          new BigDecimal("0.00"),
          new BigDecimal("270000.00"),
          "Nguyen Van A",
          "+84901234567",
          "1 Le Loi",
          null,
          "HCM",
          "VN",
          CREATED_AT,
          CUSTOMER_ID,
          "a@example.com",
          "Nguyen Van A",
          COUPON_ID,
          "SUM10",
          List.of(
              new OrderItemResponse(
                  UUID.randomUUID(),
                  PRODUCT_ID,
                  "Áo thun",
                  "SKU-1",
                  3,
                  new BigDecimal("100000.00"),
                  new BigDecimal("300000.00"))),
          List.of(
              new StatusHistoryResponse(
                  null, "PENDING", "Order placed", CUSTOMER_ID.toString(), CREATED_AT)));
    }
  }

  // ================================================================
  // POST /api/v1/orders/{id}/cancel
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/orders/{id}/cancel")
  class Cancel {

    private static final String BODY =
        """
        {"reason":"Khách đổi ý"}
        """;

    /**
     * Request chưa xác thực → {@code Principal} là {@code null} → actor {@code "anonymous"}. Đây là
     * giá trị của mọi request ở repo hiện tại (chưa có JWT).
     */
    @Test
    @DisplayName("204, body rỗng; chưa xác thực → actor = anonymous")
    void cancelsAnonymously() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/cancel", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));

      verify(service).cancelOrder(ORDER_ID, "Khách đổi ý", OrderController.ANONYMOUS_ACTOR);
    }

    @Test
    @DisplayName("đã xác thực → actor = tên principal, không phải hằng số")
    void actorIsThePrincipalName() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/cancel", ORDER_ID)
                  .with(user("customer-01"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isNoContent());

      verify(service).cancelOrder(ORDER_ID, "Khách đổi ý", "customer-01");
    }

    @ParameterizedTest(name = "reason = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("reason rỗng → 400 VALIDATION_FAILED, không tới service")
    void rejectsBlankReason(String reason) throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/cancel", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"%s\"}".formatted(reason)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("reason"));

      verifyNoInteractions(service);
    }

    /** {@code cancel_reason} và {@code order_status_history.reason} đều {@code VARCHAR(255)}. */
    @Test
    @DisplayName("reason 256 ký tự → 400, chặn trước khi DB báo value too long")
    void rejectsReasonLongerThanColumn() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/cancel", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"%s\"}".formatted("x".repeat(256))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("reason"));

      verifyNoInteractions(service);
    }

    /** AC-19. */
    @Test
    @DisplayName("đơn đã DELIVERED → 409 ORD_INVALID_STATUS_TRANSITION")
    void terminalOrderConflict() throws Exception {
      Mockito.doThrow(
              new ConflictException(ErrorCode.ORD_INVALID_STATUS_TRANSITION, "đang DELIVERED"))
          .when(service)
          .cancelOrder(eq(ORDER_ID), anyString(), anyString());

      mockMvc
          .perform(
              post("/api/v1/orders/{id}/cancel", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_INVALID_STATUS_TRANSITION.code()));
    }
  }

  // ================================================================
  // POST /api/v1/orders/{id}/transitions
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/orders/{id}/transitions")
  class Transition {

    private static final String TO_PAID =
        """
        {"to":"PAID","reason":"Payment received"}
        """;

    @Test
    @DisplayName("200 + đơn sau khi chuyển; chưa xác thực → actor = anonymous")
    void returnsTheTransitionedOrder() throws Exception {
      when(service.transitionStatus(
              ORDER_ID, OrderStatus.PAID, "Payment received", OrderController.ANONYMOUS_ACTOR))
          .thenReturn(order(OrderStatus.PAID));

      mockMvc
          .perform(
              post("/api/v1/orders/{id}/transitions", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(TO_PAID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("đã xác thực → actor = tên principal")
    void actorIsThePrincipalName() throws Exception {
      when(service.transitionStatus(any(), any(), any(), any()))
          .thenReturn(order(OrderStatus.PAID));

      mockMvc
          .perform(
              post("/api/v1/orders/{id}/transitions", ORDER_ID)
                  .with(user("ops-01"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(TO_PAID))
          .andExpect(status().isOk());

      verify(service).transitionStatus(ORDER_ID, OrderStatus.PAID, "Payment received", "ops-01");
    }

    /** {@code to} là enum: Jackson không đọc được giá trị lạ → body hỏng, không phải field sai. */
    @Test
    @DisplayName("to không phải OrderStatus → 400 MALFORMED_REQUEST")
    void rejectsUnknownStatus() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/transitions", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"to\":\"REFUNDED\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));

      verifyNoInteractions(service);
    }

    @Test
    @DisplayName("thiếu to → 400 VALIDATION_FAILED ở field to")
    void rejectsMissingStatus() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/orders/{id}/transitions", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"x\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("to"));

      verifyNoInteractions(service);
    }

    /** AC-18. */
    @Test
    @DisplayName("PENDING → DELIVERED (nhảy bước) → 409 ORD_INVALID_STATUS_TRANSITION")
    void skippingAStepConflicts() throws Exception {
      when(service.transitionStatus(eq(ORDER_ID), eq(OrderStatus.DELIVERED), any(), anyString()))
          .thenThrow(
              new ConflictException(
                  ErrorCode.ORD_INVALID_STATUS_TRANSITION, "PENDING → DELIVERED"));

      mockMvc
          .perform(
              post("/api/v1/orders/{id}/transitions", ORDER_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"to\":\"DELIVERED\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.ORD_INVALID_STATUS_TRANSITION.code()));
    }
  }

  // ================================================================
  // GET /api/v1/customers/{customerId}/orders
  // ================================================================

  @Nested
  @DisplayName("GET /api/v1/customers/{customerId}/orders")
  class CustomerOrders {

    @Test
    @DisplayName("200 PagedResponse: content + page metadata; page/size tới service đúng")
    void returnsPagedResponse() throws Exception {
      when(service.listCustomerOrders(eq(CUSTOMER_ID), any()))
          .thenReturn(new PageImpl<>(List.of(order(OrderStatus.PENDING)), PageRequest.of(1, 2), 3));

      mockMvc
          .perform(
              get("/api/v1/customers/{customerId}/orders", CUSTOMER_ID)
                  .param("page", "1")
                  .param("size", "2"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].orderNumber").value(ORDER_NUMBER))
          .andExpect(jsonPath("$.content[0].internalNote").doesNotExist())
          .andExpect(jsonPath("$.page.number").value(1))
          .andExpect(jsonPath("$.page.size").value(2))
          .andExpect(jsonPath("$.page.totalElements").value(3))
          .andExpect(jsonPath("$.page.totalPages").value(2))
          // hình dạng API-007, không lộ field nội bộ của PageImpl
          .andExpect(jsonPath("$.pageable").doesNotExist())
          .andExpect(jsonPath("$.totalElements").doesNotExist());

      verify(service).listCustomerOrders(CUSTOMER_ID, PageRequest.of(1, 2));
    }

    @Test
    @DisplayName("không truyền page/size → page 0, size 20, không có sort")
    void defaultsToFirstPageOfTwenty() throws Exception {
      Page<Order> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
      when(service.listCustomerOrders(eq(CUSTOMER_ID), any())).thenReturn(empty);

      mockMvc
          .perform(get("/api/v1/customers/{customerId}/orders", CUSTOMER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content").isEmpty())
          .andExpect(jsonPath("$.page.totalElements").value(0));

      ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(service).listCustomerOrders(eq(CUSTOMER_ID), pageable.capture());
      assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 20));
      assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
    }

    /**
     * Ràng buộc đặt thẳng lên {@code @RequestParam} → {@code HandlerMethodValidationException}.
     * Trước bài này handler toàn cục không bắt lớp đó → 500 (xem {@code GlobalExceptionHandler
     * .handleMethodValidation}).
     */
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidPaging")
    @DisplayName("page/size ngoài biên → 400 VALIDATION_FAILED, không phải 500")
    void rejectsOutOfRangePaging(String param, String value) throws Exception {
      mockMvc
          .perform(get("/api/v1/customers/{customerId}/orders", CUSTOMER_ID).param(param, value))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value(param))
          .andExpect(jsonPath("$.errors[0].rejectedValue").value(Integer.parseInt(value)));

      verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidPaging() {
      return Stream.of(
          Arguments.of("page", "-1"), Arguments.of("size", "0"), Arguments.of("size", "101"));
    }

    @Test
    @DisplayName("customer không tồn tại → 404 CUS_NOT_FOUND, không phải trang rỗng")
    void unknownCustomer() throws Exception {
      when(service.listCustomerOrders(eq(CUSTOMER_ID), any()))
          .thenThrow(new NotFoundException(ErrorCode.CUS_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/customers/{customerId}/orders", CUSTOMER_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.CUS_NOT_FOUND.code()));
    }

    /**
     * Bản kế hoạch khai method {@code /customers/{id}/orders} dưới class {@code /api/v1/orders} —
     * Spring ghép thành đường dẫn này. Test đỏ nếu ai đó đưa endpoint về lại chỗ cũ.
     */
    @Test
    @DisplayName("đường dẫn của bản kế hoạch /orders/customers/{id}/orders không tồn tại")
    void plannedNestedPathIsNotMapped() throws Exception {
      mockMvc
          .perform(get("/api/v1/orders/customers/{customerId}/orders", CUSTOMER_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.code()));
    }
  }
}
