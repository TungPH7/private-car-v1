package com.tungph7.learnpostgres.feature.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchRequest;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

/**
 * Web layer của tìm sản phẩm: tham số tới service đúng kiểu và đúng mặc định, Bean Validation bằng
 * hằng số của {@link SearchService}, JSON trả ra, lỗi của service ra HTTP.
 *
 * <p>Service là mock — kiểm đầu vào của service, chính sách ngưỡng và câu SQL đã có test ở bài
 * 02–03.
 */
@WebMvcTest(SearchController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class, ProblemDetailFactory.class})
@EnableConfigurationProperties(LoggingProperties.class)
class SearchControllerWebMvcTest {

  private static final String SEARCH = "/api/v1/search/products";
  private static final UUID CATEGORY_ID = UUID.fromString("01930000-0000-7000-8000-0000000ca701");
  private static final UUID PRODUCT_ID = UUID.fromString("01930000-0000-7000-8000-0000000b0001");

  /** Không gửi gì: không điều kiện nào, sắp mới nhất ({@code sort} null → NEWEST). */
  private static final ProductSearchCriteria NO_FILTER =
      new ProductSearchCriteria(null, null, null, null, null, SearchSort.NEWEST);

  /** Trang mặc định — {@link ProductSearchRequest#DEFAULT_PAGE_SIZE} sản phẩm. */
  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Autowired MockMvc mockMvc;

  @MockitoBean SearchService service;

  private static Arguments call(
      String name,
      MockHttpServletRequestBuilder request,
      ProductSearchCriteria criteria,
      PageRequest page) {
    return Arguments.of(name, request, criteria, page);
  }

  // ================================================================
  // Tham số tới service
  // ================================================================

  @Test
  @DisplayName("đủ tham số → service nhận điều kiện và trang đã parse — một lời gọi, không gì khác")
  void passesParsedParameters() throws Exception {
    mockMvc
        .perform(
            get(SEARCH)
                .param("q", "  iPhone 15  ")
                .param("categoryId", CATEGORY_ID.toString())
                .param("priceMin", "100000.50")
                .param("priceMax", "30000000")
                .param("status", "ACTIVE")
                .param("sort", "RELEVANCE")
                .param("page", "2")
                .param("size", "50"))
        .andExpect(status().isOk());

    verify(service)
        .searchProducts(
            new ProductSearchCriteria(
                "iPhone 15",
                CATEGORY_ID,
                new BigDecimal("100000.50"),
                new BigDecimal("30000000"),
                ProductStatus.ACTIVE,
                SearchSort.RELEVANCE),
            PageRequest.of(2, 50));
    verifyNoMoreInteractions(service);
  }

  static Stream<Arguments> defaults() {
    return Stream.of(
        call("không gửi gì", get(SEARCH), NO_FILTER, FIRST_PAGE),
        call(
            "gửi rỗng (?q=&sort=&page=&size=) → như không gửi",
            get(SEARCH).param("q", "").param("sort", "").param("page", "").param("size", ""),
            NO_FILTER,
            FIRST_PAGE),
        call(
            "q chỉ có khoảng trắng → không có từ khoá",
            get(SEARCH).param("q", "   "),
            NO_FILTER,
            FIRST_PAGE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaults")
  @DisplayName("tham số không gửi → mặc định: không lọc, NEWEST, trang 0, 20 sản phẩm")
  void optionalParametersHaveDefaults(
      String name,
      MockHttpServletRequestBuilder request,
      ProductSearchCriteria criteria,
      PageRequest page)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    verify(service).searchProducts(criteria, page);
  }

  /**
   * Mép của mỗi giới hạn đi qua — cùng hằng số mà service kiểm. Độ dài từ khoá là luật của service
   * (đếm sau khi bỏ khoảng trắng hai đầu): controller chuyển nguyên, kể cả khi quá dài.
   */
  static Stream<Arguments> limitsInclusive() {
    String longest = "a".repeat(SearchService.MAX_QUERY_LENGTH);
    String tooLong = "a".repeat(SearchService.MAX_QUERY_LENGTH + 1);
    BigDecimal maxPrice = new BigDecimal("9999999999.99");
    return Stream.of(
        call(
            "q dài đúng MAX_QUERY_LENGTH, thêm khoảng trắng hai đầu",
            get(SEARCH).param("q", "  " + longest + "  "),
            new ProductSearchCriteria(longest, null, null, null, null, null),
            FIRST_PAGE),
        call(
            "q dài MAX_QUERY_LENGTH + 1 → tới service, service chặn",
            get(SEARCH).param("q", tooLong),
            new ProductSearchCriteria(tooLong, null, null, null, null, null),
            FIRST_PAGE),
        call("size = 1", get(SEARCH).param("size", "1"), NO_FILTER, PageRequest.of(0, 1)),
        call(
            "size = MAX_PAGE_SIZE",
            get(SEARCH).param("size", String.valueOf(SearchService.MAX_PAGE_SIZE)),
            NO_FILTER,
            PageRequest.of(0, SearchService.MAX_PAGE_SIZE)),
        call(
            "giá 0 và 10 chữ số phần nguyên, 2 chữ số lẻ",
            get(SEARCH).param("priceMin", "0").param("priceMax", maxPrice.toPlainString()),
            new ProductSearchCriteria(null, null, BigDecimal.ZERO, maxPrice, null, null),
            FIRST_PAGE),
        call(
            "page = Integer.MAX_VALUE — trang sau trang cuối, không tràn số",
            get(SEARCH).param("page", String.valueOf(Integer.MAX_VALUE)),
            NO_FILTER,
            PageRequest.of(Integer.MAX_VALUE, 20)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limitsInclusive")
  @DisplayName("mép giới hạn của controller, và mọi độ dài từ khoá → tới service")
  void limitsAreInclusive(
      String name,
      MockHttpServletRequestBuilder request,
      ProductSearchCriteria criteria,
      PageRequest page)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    verify(service).searchProducts(criteria, page);
  }

  // ================================================================
  // Bean Validation → 400 VALIDATION_FAILED + errors[]
  // ================================================================

  static Stream<Arguments> constraintViolations() {
    return Stream.of(
        Arguments.of("size = 0", get(SEARCH).param("size", "0"), "size"),
        Arguments.of(
            "size = MAX_PAGE_SIZE + 1",
            get(SEARCH).param("size", String.valueOf(SearchService.MAX_PAGE_SIZE + 1)),
            "size"),
        Arguments.of("page = -1", get(SEARCH).param("page", "-1"), "page"),
        Arguments.of("priceMin âm", get(SEARCH).param("priceMin", "-0.01"), "priceMin"),
        Arguments.of(
            "priceMin 3 chữ số lẻ như client gửi (100.500)",
            get(SEARCH).param("priceMin", "100.500"),
            "priceMin"),
        Arguments.of(
            "priceMax 11 chữ số phần nguyên",
            get(SEARCH).param("priceMax", "10000000000"),
            "priceMax"),
        Arguments.of(
            "priceMax 1e999999 — pgjdbc sẽ gửi thành 0",
            get(SEARCH).param("priceMax", "1e999999"),
            "priceMax"),
        Arguments.of(
            "priceMin 1e-20000 — Postgres sẽ từ chối (22P03 → 409)",
            get(SEARCH).param("priceMin", "1e-20000"),
            "priceMin"));
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
        Arguments.of(
            "categoryId không phải UUID", get(SEARCH).param("categoryId", "abc"), "categoryId"),
        Arguments.of("status viết thường", get(SEARCH).param("status", "active"), "status"),
        Arguments.of("status ngoài danh sách", get(SEARCH).param("status", "DELETED"), "status"),
        Arguments.of("sort ngoài danh sách", get(SEARCH).param("sort", "PRICE"), "sort"),
        Arguments.of("priceMin không phải số", get(SEARCH).param("priceMin", "abc"), "priceMin"),
        Arguments.of("page không phải số", get(SEARCH).param("page", "one"), "page"),
        Arguments.of("page tràn int", get(SEARCH).param("page", "2147483648"), "page"),
        Arguments.of("size không phải số", get(SEARCH).param("size", "ten"), "size"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableParameters")
  @DisplayName("sai kiểu → 400 INVALID_PARAMETER nêu tên tham số; không tới service")
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
  // JSON trả ra, lỗi của service
  // ================================================================

  /**
   * Hình dạng {@code PagedResponse} ({@code content} + {@code page}) thêm {@code matchMode}: tiền
   * giữ 2 chữ số lẻ, thời điểm ISO-8601 UTC, field {@code null} vẫn có mặt.
   */
  @Test
  @DisplayName("JSON: content + page + matchMode; tiền giữ scale, Instant UTC, null có mặt")
  void responseBody() throws Exception {
    ProductSearchResponse product =
        new ProductSearchResponse(
            PRODUCT_ID,
            "Router iPhone 15 Pro Max Đen",
            "lab-p-62740",
            null,
            new BigDecimal("49720000.00"),
            ProductStatus.ACTIVE,
            CATEGORY_ID,
            Instant.parse("2026-10-05T16:08:06.759995Z"),
            new BigDecimal("0.50"));
    when(service.searchProducts(any(), any()))
        .thenReturn(
            ProductSearchPageResponse.of(
                new PageImpl<>(List.of(product), PageRequest.of(0, 1), 10138), MatchMode.RELAXED));

    String json =
        mockMvc
            .perform(get(SEARCH).param("q", "iphne").param("size", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(json)
        .isEqualTo(
            "{\"content\":[{\"id\":\"%s\",\"name\":\"Router iPhone 15 Pro Max Đen\",\"slug\":\"lab-p-62740\",\"sku\":null,\"price\":49720000.00,\"status\":\"ACTIVE\",\"categoryId\":\"%s\",\"createdAt\":\"2026-10-05T16:08:06.759995Z\",\"score\":0.50}],\"page\":{\"number\":0,\"size\":1,\"totalElements\":10138,\"totalPages\":10138},\"matchMode\":\"RELAXED\"}"
                .formatted(PRODUCT_ID, CATEGORY_ID));
  }

  @Test
  @DisplayName("không có từ khoá → matchMode null vẫn có mặt; không có kết quả → content []")
  void noKeyword() throws Exception {
    when(service.searchProducts(any(), any()))
        .thenReturn(ProductSearchPageResponse.of(new PageImpl<>(List.of(), FIRST_PAGE, 0), null));

    String json =
        mockMvc
            .perform(get(SEARCH))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    // jsonPath(...).exists() coi giá trị null là "không có" — so trên chuỗi JSON
    assertThat(json)
        .isEqualTo(
            "{\"content\":[],\"page\":{\"number\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0},\"matchMode\":null}");
  }

  /**
   * Luật service kiểm mà annotation không diễn đạt được: q tối thiểu tính sau khi bỏ khoảng trắng.
   */
  @Test
  @DisplayName(
      "400 của service (q ngắn quá sau khi bỏ khoảng trắng) giữ mã và detail, không errors[]")
  void validationFailedFromService() throws Exception {
    String detail = "Từ khoá phải dài 2 – 80 ký tự (không tính khoảng trắng hai đầu), nhận 1.";
    when(service.searchProducts(any(), any()))
        .thenThrow(new BadRequestException(ErrorCode.VALIDATION_FAILED, detail));

    mockMvc
        .perform(get(SEARCH).param("q", " a "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.detail").value(detail))
        .andExpect(jsonPath("$.errors").doesNotExist());

    verify(service)
        .searchProducts(new ProductSearchCriteria("a", null, null, null, null, null), FIRST_PAGE);
  }

  @Test
  @DisplayName("chỉ đọc: POST → 405, Allow: GET")
  void readOnly() throws Exception {
    mockMvc
        .perform(post(SEARCH))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string(HttpHeaders.ALLOW, "GET"));

    verifyNoInteractions(service);
  }
}
