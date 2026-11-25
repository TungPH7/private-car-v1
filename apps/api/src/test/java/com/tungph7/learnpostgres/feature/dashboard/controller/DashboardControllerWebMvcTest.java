package com.tungph7.learnpostgres.feature.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.dashboard.dto.CustomerLifetimeValueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderFunnelResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.RevenueGroupingSetResponse;
import com.tungph7.learnpostgres.feature.dashboard.service.DashboardService;

/**
 * Web layer của 14 báo cáo dashboard: routing, tham số tới service đúng kiểu (ngày ISO, {@code
 * yyyy-MM}, giá trị mặc định), Bean Validation bằng hằng số của {@link DashboardService}, JSON trả
 * ra, và lỗi của service hiện ra ngoài HTTP.
 *
 * <p>Service là mock — kiểm đầu vào, 503 khi MV chưa có dữ liệu và báo cáo thật đã có test ở bài
 * 03. Service mock trả giá trị mặc định của Mockito (list rỗng, {@code null}) khi không dựng stub.
 */
@WebMvcTest({DashboardController.class, CustomerLifetimeValueController.class})
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class, ProblemDetailFactory.class})
@EnableConfigurationProperties(LoggingProperties.class)
class DashboardControllerWebMvcTest {

  private static final String BASE = "/api/v1/dashboard";
  private static final String LTV = "/api/v1/customers/{customerId}/ltv";
  private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
  private static final LocalDate TO = LocalDate.of(2025, 1, 31);
  private static final UUID CUSTOMER_ID = UUID.fromString("01930000-0000-7000-8000-0000000000c1");

  @Autowired MockMvc mockMvc;

  @MockitoBean DashboardService service;

  /** GET {@code BASE + path} với {@code from} / {@code to} hợp lệ. */
  private static MockHttpServletRequestBuilder ranged(String path) {
    return get(BASE + path).param("from", FROM.toString()).param("to", TO.toString());
  }

  private static Arguments call(
      String name, MockHttpServletRequestBuilder request, Consumer<DashboardService> expected) {
    return Arguments.of(name, request, expected);
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  // ================================================================
  // Tham số tới service
  // ================================================================

  /** Mỗi endpoint một request đủ tham số, và lời gọi service mà request đó phải sinh ra. */
  static Stream<Arguments> endpoints() {
    return Stream.of(
        call("revenue/daily", ranged("/revenue/daily"), s -> s.dailyRevenue(FROM, TO)),
        call("revenue/weekly", ranged("/revenue/weekly"), s -> s.weeklyRevenue(FROM, TO)),
        call(
            "revenue/monthly",
            get(BASE + "/revenue/monthly").param("year", "2025"),
            s -> s.monthlyRevenue(2025)),
        call(
            "revenue/by-category",
            get(BASE + "/revenue/by-category").param("year", "2025"),
            s -> s.monthlyRevenueByCategory(2025)),
        call(
            "top/products",
            ranged("/top/products").param("n", "5"),
            s -> s.topNBestSellers(5, FROM, TO)),
        call(
            "top/customers",
            ranged("/top/customers").param("n", "5").param("minTotalSpent", "500000.50"),
            s -> s.topNCustomersBySpend(5, new BigDecimal("500000.50"), FROM, TO)),
        call("funnel", ranged("/funnel"), s -> s.orderFunnel(FROM, TO)),
        call(
            "cohort",
            get(BASE + "/cohort").param("cohortMonth", "2025-01").param("months", "6"),
            s -> s.customerCohortRetention(YearMonth.of(2025, 1), 6)),
        call(
            "inventory/turnover",
            ranged("/inventory/turnover").param("n", "5"),
            s -> s.inventoryTurnover(5, FROM, TO)),
        call("aov", ranged("/aov"), s -> s.avgOrderValueBySegment(FROM, TO)),
        call(
            "growth",
            get(BASE + "/growth").param("year", "2025"),
            s -> s.monthOverMonthGrowth(2025)),
        call(
            "top/categories",
            ranged("/top/categories").param("n", "5"),
            s -> s.topCategoriesByRevenue(5, FROM, TO)),
        call(
            "revenue/grouping-sets",
            ranged("/revenue/grouping-sets"),
            s -> s.revenueGroupingSets(FROM, TO)),
        call(
            "customers/{id}/ltv",
            get(LTV, CUSTOMER_ID),
            s -> s.customerLifetimeValue(CUSTOMER_ID)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  @DisplayName("14 endpoint: 200, service nhận đúng tham số đã parse — một lời gọi, không gì khác")
  void everyEndpointPassesParsedParameters(
      String name, MockHttpServletRequestBuilder request, Consumer<DashboardService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
    verifyNoMoreInteractions(service);
  }

  static Stream<Arguments> defaults() {
    return Stream.of(
        call("top/products: n = 10", ranged("/top/products"), s -> s.topNBestSellers(10, FROM, TO)),
        call(
            "top/customers: n = 10, minTotalSpent = 0",
            ranged("/top/customers"),
            s -> s.topNCustomersBySpend(10, BigDecimal.ZERO, FROM, TO)),
        call(
            "top/categories: n = 10",
            ranged("/top/categories"),
            s -> s.topCategoriesByRevenue(10, FROM, TO)),
        call(
            "inventory/turnover: n = 10",
            ranged("/inventory/turnover"),
            s -> s.inventoryTurnover(10, FROM, TO)),
        call(
            "cohort: months = 12",
            get(BASE + "/cohort").param("cohortMonth", "2025-01"),
            s -> s.customerCohortRetention(YearMonth.of(2025, 1), 12)),
        call(
            "n gửi rỗng (?n=) → như không gửi",
            ranged("/top/products").param("n", ""),
            s -> s.topNBestSellers(10, FROM, TO)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaults")
  @DisplayName("tham số tùy chọn không gửi → giá trị mặc định")
  void optionalParametersHaveDefaults(
      String name, MockHttpServletRequestBuilder request, Consumer<DashboardService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
  }

  /** Mép của mỗi giới hạn đi qua — cùng hằng số mà service kiểm. */
  static Stream<Arguments> limitsInclusive() {
    return Stream.of(
        call("n = 1", ranged("/top/products").param("n", "1"), s -> s.topNBestSellers(1, FROM, TO)),
        call(
            "n = MAX_TOP_N",
            ranged("/top/products").param("n", String.valueOf(DashboardService.MAX_TOP_N)),
            s -> s.topNBestSellers(DashboardService.MAX_TOP_N, FROM, TO)),
        call(
            "year = MIN_YEAR",
            get(BASE + "/revenue/monthly").param("year", String.valueOf(DashboardService.MIN_YEAR)),
            s -> s.monthlyRevenue(DashboardService.MIN_YEAR)),
        call(
            "year = MAX_YEAR",
            get(BASE + "/growth").param("year", String.valueOf(DashboardService.MAX_YEAR)),
            s -> s.monthOverMonthGrowth(DashboardService.MAX_YEAR)),
        call(
            "months = MAX_COHORT_MONTHS",
            get(BASE + "/cohort")
                .param("cohortMonth", "2025-01")
                .param("months", String.valueOf(DashboardService.MAX_COHORT_MONTHS)),
            s ->
                s.customerCohortRetention(
                    YearMonth.of(2025, 1), DashboardService.MAX_COHORT_MONTHS)),
        call(
            "minTotalSpent: 15 chữ số phần nguyên, 2 chữ số lẻ",
            ranged("/top/customers").param("minTotalSpent", "999999999999999.99"),
            s -> s.topNCustomersBySpend(10, new BigDecimal("999999999999999.99"), FROM, TO)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limitsInclusive")
  @DisplayName("giá trị ở đúng mép giới hạn → tới service")
  void limitsAreInclusive(
      String name, MockHttpServletRequestBuilder request, Consumer<DashboardService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
  }

  /**
   * {@code +999999999-12-31} ({@code %2B} trên URL) parse ra đúng {@link LocalDate#MAX} — không lỗi
   * ở tầng HTTP. Service chặn nó (bài 03 §4.2), mock ở đây đóng vai đó: ra 400 {@code
   * VALIDATION_FAILED}, không tới DB.
   */
  @Test
  @DisplayName("to=+999999999-12-31 → service nhận LocalDate.MAX, lỗi của service ra 400")
  void extremeDateReachesTheServiceAsLocalDateMax() throws Exception {
    when(service.dailyRevenue(FROM, LocalDate.MAX))
        .thenThrow(
            new BadRequestException(
                ErrorCode.VALIDATION_FAILED, "from, to phải nằm trong 2000-01-01 – 2099-12-31"));

    mockMvc
        .perform(
            get(BASE + "/revenue/daily")
                .param("from", "2025-01-01")
                .param("to", "+999999999-12-31"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

    verify(service).dailyRevenue(FROM, LocalDate.MAX);
  }

  // ================================================================
  // Bean Validation → 400 VALIDATION_FAILED + errors[]
  // ================================================================

  static Stream<Arguments> constraintViolations() {
    return Stream.of(
        Arguments.of("n = 0", ranged("/top/products").param("n", "0"), "n"),
        Arguments.of("n = 101", ranged("/top/categories").param("n", "101"), "n"),
        Arguments.of("n = -1", ranged("/inventory/turnover").param("n", "-1"), "n"),
        Arguments.of("year = 1999", get(BASE + "/revenue/monthly").param("year", "1999"), "year"),
        Arguments.of(
            "year = 2100", get(BASE + "/revenue/by-category").param("year", "2100"), "year"),
        Arguments.of("year = 1", get(BASE + "/growth").param("year", "1"), "year"),
        Arguments.of(
            "months = 0",
            get(BASE + "/cohort").param("cohortMonth", "2025-01").param("months", "0"),
            "months"),
        Arguments.of(
            "months = 25",
            get(BASE + "/cohort").param("cohortMonth", "2025-01").param("months", "25"),
            "months"),
        Arguments.of(
            "minTotalSpent âm",
            ranged("/top/customers").param("minTotalSpent", "-0.01"),
            "minTotalSpent"),
        Arguments.of(
            "minTotalSpent 3 chữ số lẻ như client gửi (100.500)",
            ranged("/top/customers").param("minTotalSpent", "100.500"),
            "minTotalSpent"),
        Arguments.of(
            "minTotalSpent 16 chữ số phần nguyên",
            ranged("/top/customers").param("minTotalSpent", "1000000000000000"),
            "minTotalSpent"),
        Arguments.of(
            "minTotalSpent 1e999999 — pgjdbc sẽ gửi thành 0",
            ranged("/top/customers").param("minTotalSpent", "1e999999"),
            "minTotalSpent"),
        Arguments.of(
            "minTotalSpent 1e-20000 — Postgres sẽ từ chối (22P03 → 409)",
            ranged("/top/customers").param("minTotalSpent", "1e-20000"),
            "minTotalSpent"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constraintViolations")
  @DisplayName(
      "vi phạm giới hạn → 400 VALIDATION_FAILED, errors[] nêu tên tham số; không tới service")
  void constraintViolationsAreBadRequests(
      String name, MockHttpServletRequestBuilder request, String field) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].field").value(field));

    verifyNoInteractions(service);
  }

  // ================================================================
  // Không đọc được tham số → 400 INVALID_PARAMETER
  // ================================================================

  static Stream<Arguments> unreadableParameters() {
    return Stream.of(
        Arguments.of("thiếu to", get(BASE + "/revenue/daily").param("from", "2025-01-01"), "to"),
        Arguments.of(
            "from gửi rỗng",
            get(BASE + "/funnel").param("from", "").param("to", "2025-01-31"),
            "from"),
        Arguments.of("thiếu year", get(BASE + "/growth"), "year"),
        Arguments.of("thiếu cohortMonth", get(BASE + "/cohort"), "cohortMonth"),
        Arguments.of(
            "ngày không có thật (30/02)",
            get(BASE + "/aov").param("from", "2025-02-30").param("to", "2025-03-03"),
            "from"),
        Arguments.of(
            "ngày thiếu số 0 (2025-1-1)",
            get(BASE + "/aov").param("from", "2025-1-1").param("to", "2025-03-03"),
            "from"),
        // Thiếu @DateTimeFormat(iso = DATE) thì Spring parse theo định dạng ngắn của locale trong
        // Accept-Language: en-US nhận 1/1/25, vi-VN nhận 01/02/2025 là ngày 1 tháng 2.
        Arguments.of(
            "ngày theo locale en-US (1/1/25)",
            get(BASE + "/aov")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US")
                .param("from", "1/1/25")
                .param("to", "1/31/25"),
            "from"),
        Arguments.of(
            "ngày theo locale vi-VN (01/01/2025)",
            get(BASE + "/aov")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN")
                .param("from", "01/01/2025")
                .param("to", "31/01/2025"),
            "from"),
        Arguments.of(
            "dấu cách trước ngày — '+' không mã hóa trên URL thành dấu cách",
            get(BASE + "/aov").param("from", "2025-01-01").param("to", " 999999999-12-31"),
            "to"),
        Arguments.of(
            "year không phải số", get(BASE + "/revenue/monthly").param("year", "abc"), "year"),
        Arguments.of(
            "year tràn int", get(BASE + "/revenue/monthly").param("year", "99999999999"), "year"),
        Arguments.of("n không phải số", ranged("/top/products").param("n", "ten"), "n"),
        Arguments.of(
            "minTotalSpent không phải số",
            ranged("/top/customers").param("minTotalSpent", "abc"),
            "minTotalSpent"),
        Arguments.of(
            "cohortMonth tháng 13",
            get(BASE + "/cohort").param("cohortMonth", "2025-13"),
            "cohortMonth"),
        Arguments.of(
            "cohortMonth thiếu số 0 (2025-1)",
            get(BASE + "/cohort").param("cohortMonth", "2025-1"),
            "cohortMonth"),
        Arguments.of(
            "cohortMonth là một ngày (2025-01-15)",
            get(BASE + "/cohort").param("cohortMonth", "2025-01-15"),
            "cohortMonth"),
        Arguments.of("customerId không phải UUID", get(LTV, "not-a-uuid"), "customerId"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableParameters")
  @DisplayName("thiếu / sai định dạng → 400 INVALID_PARAMETER nêu tên tham số; không tới service")
  void unreadableParametersAreBadRequests(
      String name, MockHttpServletRequestBuilder request, String parameter) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
        .andExpect(jsonPath("$.parameter").value(parameter));

    verifyNoInteractions(service);
  }

  // ================================================================
  // JSON trả ra
  // ================================================================

  @Nested
  @DisplayName("JSON trả ra")
  class ResponseBody {

    @Test
    @DisplayName("ngày ra yyyy-MM-dd, tiền giữ 2 chữ số lẻ, field null vẫn có mặt")
    void periodRevenue() throws Exception {
      BigDecimal zero = new BigDecimal("0.00");
      when(service.dailyRevenue(FROM, TO))
          .thenReturn(
              List.of(
                  new PeriodRevenueResponse(
                      LocalDate.of(2025, 1, 10),
                      1,
                      1,
                      new BigDecimal("250000.00"),
                      new BigDecimal("50000.00"),
                      zero,
                      new BigDecimal("200000.00"),
                      zero,
                      new BigDecimal("200000.00")),
                  new PeriodRevenueResponse(
                      LocalDate.of(2025, 1, 11), 0, 0, zero, zero, zero, zero, zero, null)));

      String json = body(ranged("/revenue/daily"));

      assertThat(json)
          .startsWith("[{\"periodStart\":\"2025-01-10\",\"orderCount\":1,")
          .contains("\"netRevenue\":200000.00,\"refundedAmount\":0.00,")
          .endsWith("\"refundedAmount\":0.00,\"avgOrderValue\":null}]");
    }

    /** AC-08 ở tầng HTTP: khoảng không có đơn vẫn là một object — mọi số đếm 0, tỷ lệ null. */
    @Test
    @DisplayName("phễu là một object, không phải mảng; khoảng không có đơn: 0 và tỷ lệ null")
    void funnelIsASingleObject() throws Exception {
      when(service.orderFunnel(FROM, TO))
          .thenReturn(
              new OrderFunnelResponse(0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null));

      assertThat(body(ranged("/funnel")))
          .isEqualTo(
              "{\"placed\":0,\"paid\":0,\"shipped\":0,\"delivered\":0,\"pending\":0,"
                  + "\"cancelled\":0,\"cancelledAfterPaid\":0,\"expired\":0,\"paidRate\":null,"
                  + "\"shippedRate\":null,\"deliveredRate\":null,\"cancelRate\":null,"
                  + "\"expireRate\":null}");
    }

    @Test
    @DisplayName("GROUPING SETS: categoryId null ở row chi tiết và row tổng — phân biệt bằng level")
    void groupingSetsKeepNullColumns() throws Exception {
      when(service.revenueGroupingSets(FROM, TO))
          .thenReturn(
              List.of(
                  new RevenueGroupingSetResponse(
                      LocalDate.of(2025, 1, 1), null, null, new BigDecimal("90000.00"), 1, 0),
                  new RevenueGroupingSetResponse(
                      null, null, null, new BigDecimal("7340000.00"), 7, 3)));

      mockMvc
          .perform(ranged("/revenue/grouping-sets"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].month").value("2025-01-01"))
          .andExpect(jsonPath("$[0].categoryId").value((Object) null))
          .andExpect(jsonPath("$[0].groupingLevel").value(0))
          .andExpect(jsonPath("$[1].month").value((Object) null))
          .andExpect(jsonPath("$[1].groupingLevel").value(3));
    }

    @Test
    @DisplayName("LTV: thời điểm ra ISO-8601 UTC; khách chưa có đơn: 0.00 và null")
    void lifetimeValue() throws Exception {
      when(service.customerLifetimeValue(CUSTOMER_ID))
          .thenReturn(
              new CustomerLifetimeValueResponse(
                  CUSTOMER_ID,
                  "dash-c1@example.com",
                  4,
                  new BigDecimal("700000.00"),
                  new BigDecimal("175000.00"),
                  Instant.parse("2025-01-10T02:00:00Z"),
                  Instant.parse("2025-06-10T03:00:00Z")));

      mockMvc
          .perform(get(LTV, CUSTOMER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
          .andExpect(jsonPath("$.totalOrders").value(4))
          .andExpect(jsonPath("$.firstOrderAt").value("2025-01-10T02:00:00Z"))
          .andExpect(jsonPath("$.lastOrderAt").value("2025-06-10T03:00:00Z"));

      when(service.customerLifetimeValue(CUSTOMER_ID))
          .thenReturn(
              new CustomerLifetimeValueResponse(
                  CUSTOMER_ID, "dash-c4@example.com", 0, new BigDecimal("0.00"), null, null, null));

      assertThat(body(get(LTV, CUSTOMER_ID)))
          .contains("\"totalOrders\":0,\"totalSpent\":0.00,\"avgOrderValue\":null,")
          .endsWith("\"firstOrderAt\":null,\"lastOrderAt\":null}");
    }
  }

  // ================================================================
  // Lỗi của service ra HTTP
  // ================================================================

  @Nested
  @DisplayName("lỗi của service ra HTTP")
  class ServiceErrors {

    @Test
    @DisplayName("400 của service (from > to) giữ mã và detail, không có errors[]")
    void validationFailedFromService() throws Exception {
      when(service.orderFunnel(TO, FROM))
          .thenThrow(
              new BadRequestException(
                  ErrorCode.VALIDATION_FAILED,
                  "from (2025-01-31) phải trước hoặc bằng to (2025-01-01)."));

      mockMvc
          .perform(get(BASE + "/funnel").param("from", TO.toString()).param("to", FROM.toString()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(
              jsonPath("$.detail").value("from (2025-01-31) phải trước hoặc bằng to (2025-01-01)."))
          .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @DisplayName("LTV của khách không tồn tại → 404 CUS_NOT_FOUND")
    void unknownCustomer() throws Exception {
      when(service.customerLifetimeValue(CUSTOMER_ID))
          .thenThrow(
              new NotFoundException(
                  ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(CUSTOMER_ID)));

      mockMvc
          .perform(get(LTV, CUSTOMER_ID))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value(ErrorCode.CUS_NOT_FOUND.code()));
    }

    /** 4 endpoint đọc materialized view, và lời gọi service tương ứng. */
    static Stream<Arguments> materializedViewEndpoints() {
      return Stream.of(
          Arguments.of(
              "revenue/daily",
              ranged("/revenue/daily"),
              (Function<DashboardService, Object>) s -> s.dailyRevenue(FROM, TO)),
          Arguments.of(
              "revenue/weekly",
              ranged("/revenue/weekly"),
              (Function<DashboardService, Object>) s -> s.weeklyRevenue(FROM, TO)),
          Arguments.of(
              "revenue/monthly",
              get(BASE + "/revenue/monthly").param("year", "2025"),
              (Function<DashboardService, Object>) s -> s.monthlyRevenue(2025)),
          Arguments.of(
              "growth",
              get(BASE + "/growth").param("year", "2025"),
              (Function<DashboardService, Object>) s -> s.monthOverMonthGrowth(2025)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("materializedViewEndpoints")
    @DisplayName("MV chưa có dữ liệu → 503 DASH_REVENUE_NOT_READY, problem+json")
    void revenueNotReady(
        String name,
        MockHttpServletRequestBuilder request,
        Function<DashboardService, Object> report)
        throws Exception {
      when(report.apply(service))
          .thenThrow(
              new ServiceUnavailableException(
                  ErrorCode.DASH_REVENUE_NOT_READY,
                  "Số liệu doanh thu chưa sẵn sàng — đang chờ lần làm mới kế tiếp, thử lại sau.",
                  new IllegalStateException("55000")));

      mockMvc
          .perform(request)
          .andExpect(status().isServiceUnavailable())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(503))
          .andExpect(jsonPath("$.code").value(ErrorCode.DASH_REVENUE_NOT_READY.code()))
          .andExpect(jsonPath("$.title").value("Revenue data is not ready yet"));
    }
  }

  @Test
  @DisplayName("chỉ đọc: POST → 405, Allow: GET")
  void readOnly() throws Exception {
    mockMvc
        .perform(post(BASE + "/revenue/daily"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string(HttpHeaders.ALLOW, "GET"));

    verifyNoInteractions(service);
  }
}
