package com.tungph7.learnpostgres.feature.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Map;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.error.ServiceUnavailableException;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.PlanNodeResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.admin.service.AdminService;
import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web layer của 8 endpoint admin: routing, tham số và body tới service đúng kiểu và đúng mặc định,
 * Bean Validation bằng hằng số của {@link AdminService} / {@link SearchService}, JSON trả ra, và
 * lỗi của service (404 / 409 / 503) ra HTTP.
 *
 * <p>Service là mock — ANALYZE / VACUUM / EXPLAIN / benchmark thật đã có test trên Postgres ở bài
 * 03.
 */
@WebMvcTest(AdminController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class, ProblemDetailFactory.class})
@EnableConfigurationProperties(LoggingProperties.class)
class AdminControllerWebMvcTest {

  private static final String ADMIN = "/api/v1/admin";
  private static final String EXPLAIN = ADMIN + "/search/products/explain";
  private static final String BENCHMARK = ADMIN + "/search/products/benchmark";
  private static final UUID CATEGORY_ID = UUID.fromString("01930000-0000-7000-8000-0000000ca701");

  /** Body đủ mọi tham số tìm, và điều kiện + trang service phải nhận từ nó. */
  private static final String FULL_SEARCH =
      """
      {"q":"  iPhone  ","categoryId":"%s","priceMin":100000.50,"priceMax":30000000,\
      "status":"ACTIVE","sort":"RELEVANCE","page":1,"size":50}"""
          .formatted(CATEGORY_ID);

  private static final ProductSearchCriteria FULL_CRITERIA =
      new ProductSearchCriteria(
          "iPhone",
          CATEGORY_ID,
          new BigDecimal("100000.50"),
          new BigDecimal("30000000"),
          ProductStatus.ACTIVE,
          SearchSort.RELEVANCE);

  /** Body {@code {}}: không điều kiện nào, NEWEST, trang 0, 20 sản phẩm — như GET không tham số. */
  private static final ProductSearchCriteria NO_FILTER =
      new ProductSearchCriteria(null, null, null, null, null, SearchSort.NEWEST);

  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Autowired MockMvc mockMvc;

  @MockitoBean AdminService service;

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static Arguments call(
      String name, MockHttpServletRequestBuilder request, Consumer<AdminService> expected) {
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
        call("GET /indexes", get(ADMIN + "/indexes"), AdminService::indexes),
        call("GET /indexes/usage", get(ADMIN + "/indexes/usage"), AdminService::indexUsage),
        call(
            "GET /slow-queries",
            get(ADMIN + "/slow-queries").param("limit", "5"),
            s -> s.slowQueries(5)),
        call(
            "GET /tables/{name}/stats",
            get(ADMIN + "/tables/{name}/stats", "orders"),
            s -> s.tableStats("orders")),
        call(
            "POST /tables/{name}/analyze",
            post(ADMIN + "/tables/{name}/analyze", "orders"),
            s -> s.analyze("orders")),
        call(
            "POST /tables/{name}/vacuum",
            post(ADMIN + "/tables/{name}/vacuum", "orders").param("full", "true"),
            s -> s.vacuum("orders", true)),
        call(
            "POST /search/products/explain",
            json(post(EXPLAIN).param("matchMode", "RELAXED"), FULL_SEARCH),
            s -> s.explainProductSearch(FULL_CRITERIA, MatchMode.RELAXED, PageRequest.of(1, 50))),
        call(
            "POST /search/products/benchmark",
            json(post(BENCHMARK).param("iterations", "50"), FULL_SEARCH),
            s -> s.benchmarkProductSearch(FULL_CRITERIA, PageRequest.of(1, 50), 50)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  @DisplayName("8 endpoint: 200, service nhận đúng tham số đã parse — một lời gọi, không gì khác")
  void everyEndpointPassesParsedParameters(
      String name, MockHttpServletRequestBuilder request, Consumer<AdminService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
    verifyNoMoreInteractions(service);
  }

  static Stream<Arguments> defaults() {
    return Stream.of(
        call("slow-queries: limit = 20", get(ADMIN + "/slow-queries"), s -> s.slowQueries(20)),
        call(
            "vacuum: full = false",
            post(ADMIN + "/tables/{name}/vacuum", "orders"),
            s -> s.vacuum("orders", false)),
        call(
            "explain: body {} → không lọc, NEWEST, trang 0, 20 sản phẩm",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{}"),
            s -> s.explainProductSearch(NO_FILTER, MatchMode.STRICT, FIRST_PAGE)),
        call(
            "explain: page / size null → như không gửi",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"page\":null,\"size\":null}"),
            s -> s.explainProductSearch(NO_FILTER, MatchMode.STRICT, FIRST_PAGE)),
        call(
            "benchmark: iterations = 100, body {}",
            json(post(BENCHMARK), "{}"),
            s -> s.benchmarkProductSearch(NO_FILTER, FIRST_PAGE, 100)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaults")
  @DisplayName("tham số / field không gửi → mặc định, đúng như GET /api/v1/search/products")
  void optionalValuesHaveDefaults(
      String name, MockHttpServletRequestBuilder request, Consumer<AdminService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
  }

  /**
   * Mép của mỗi giới hạn đi qua — cùng hằng số mà service kiểm. Độ dài từ khoá là luật của service
   * (đếm sau khi bỏ khoảng trắng hai đầu), như ở GET: body chuyển nguyên, kể cả khi quá dài.
   */
  static Stream<Arguments> limitsInclusive() {
    String tooLong = "a".repeat(SearchService.MAX_QUERY_LENGTH + 1);
    return Stream.of(
        call(
            "body: q dài MAX_QUERY_LENGTH + 1 → tới service, service chặn",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"q\":\"%s\"}".formatted(tooLong)),
            s ->
                s.explainProductSearch(
                    new ProductSearchCriteria(tooLong, null, null, null, null, null),
                    MatchMode.STRICT,
                    FIRST_PAGE)),
        call("limit = 1", get(ADMIN + "/slow-queries").param("limit", "1"), s -> s.slowQueries(1)),
        call(
            "limit = MAX_SLOW_QUERIES",
            get(ADMIN + "/slow-queries")
                .param("limit", String.valueOf(AdminService.MAX_SLOW_QUERIES)),
            s -> s.slowQueries(AdminService.MAX_SLOW_QUERIES)),
        call(
            "iterations = 1",
            json(post(BENCHMARK).param("iterations", "1"), "{}"),
            s -> s.benchmarkProductSearch(NO_FILTER, FIRST_PAGE, 1)),
        call(
            "iterations = MAX_BENCHMARK_ITERATIONS",
            json(
                post(BENCHMARK)
                    .param("iterations", String.valueOf(AdminService.MAX_BENCHMARK_ITERATIONS)),
                "{}"),
            s ->
                s.benchmarkProductSearch(
                    NO_FILTER, FIRST_PAGE, AdminService.MAX_BENCHMARK_ITERATIONS)),
        call(
            "body size = MAX_PAGE_SIZE",
            json(
                post(EXPLAIN).param("matchMode", "STRICT"),
                "{\"size\":%d}".formatted(SearchService.MAX_PAGE_SIZE)),
            s ->
                s.explainProductSearch(
                    NO_FILTER, MatchMode.STRICT, PageRequest.of(0, SearchService.MAX_PAGE_SIZE))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limitsInclusive")
  @DisplayName("mép giới hạn của controller, và mọi độ dài từ khoá → tới service")
  void limitsAreInclusive(
      String name, MockHttpServletRequestBuilder request, Consumer<AdminService> expected)
      throws Exception {
    mockMvc.perform(request).andExpect(status().isOk());

    expected.accept(verify(service));
  }

  // ================================================================
  // Bean Validation → 400 VALIDATION_FAILED + errors[]
  // ================================================================

  static Stream<Arguments> constraintViolations() {
    return Stream.of(
        Arguments.of(
            "limit = 0", get(ADMIN + "/slow-queries").param("limit", "0"), List.of("limit")),
        Arguments.of(
            "limit = MAX_SLOW_QUERIES + 1",
            get(ADMIN + "/slow-queries")
                .param("limit", String.valueOf(AdminService.MAX_SLOW_QUERIES + 1)),
            List.of("limit")),
        Arguments.of(
            "iterations = 0",
            json(post(BENCHMARK).param("iterations", "0"), "{}"),
            List.of("iterations")),
        Arguments.of(
            "iterations = MAX_BENCHMARK_ITERATIONS + 1",
            json(
                post(BENCHMARK)
                    .param("iterations", String.valueOf(AdminService.MAX_BENCHMARK_ITERATIONS + 1)),
                "{}"),
            List.of("iterations")),
        Arguments.of(
            "body: size = 0",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"size\":0}"),
            List.of("size")),
        Arguments.of(
            "body: size = MAX_PAGE_SIZE + 1",
            json(
                post(EXPLAIN).param("matchMode", "STRICT"),
                "{\"size\":%d}".formatted(SearchService.MAX_PAGE_SIZE + 1)),
            List.of("size")),
        Arguments.of("body: page = -1", json(post(BENCHMARK), "{\"page\":-1}"), List.of("page")),
        Arguments.of(
            "body: priceMin âm",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"priceMin\":-1}"),
            List.of("priceMin")),
        Arguments.of(
            "body: priceMax 1e999999 — pgjdbc sẽ gửi thành 0",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"priceMax\":1e999999}"),
            List.of("priceMax")),
        Arguments.of(
            "body và tham số cùng sai → cả hai vào errors[]",
            json(post(BENCHMARK).param("iterations", "0"), "{\"page\":-1}"),
            List.of("page", "iterations")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constraintViolations")
  @DisplayName("vi phạm giới hạn → 400 VALIDATION_FAILED, errors[] nêu tên; không tới service")
  void constraintViolationsAreBadRequests(
      String name, MockHttpServletRequestBuilder request, List<String> fields) throws Exception {
    String json =
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    // Thứ tự lỗi trong errors[] không cố định (Bean Validation trả một Set)
    List<String> actual = JsonPath.read(json, "$.errors[*].field");
    assertThat(actual).containsExactlyInAnyOrderElementsOf(fields);
    verifyNoInteractions(service);
  }

  // ================================================================
  // Không đọc được tham số / body → 400 INVALID_PARAMETER / MALFORMED_REQUEST, 415
  // ================================================================

  static Stream<Arguments> unreadableParameters() {
    return Stream.of(
        Arguments.of("thiếu matchMode", json(post(EXPLAIN), "{}"), "matchMode"),
        Arguments.of(
            "matchMode viết thường",
            json(post(EXPLAIN).param("matchMode", "strict"), "{}"),
            "matchMode"),
        Arguments.of(
            "iterations không phải số",
            json(post(BENCHMARK).param("iterations", "many"), "{}"),
            "iterations"),
        Arguments.of(
            "limit không phải số", get(ADMIN + "/slow-queries").param("limit", "x"), "limit"),
        Arguments.of(
            "full không phải boolean",
            post(ADMIN + "/tables/{name}/vacuum", "orders").param("full", "maybe"),
            "full"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableParameters")
  @DisplayName("thiếu / sai kiểu → 400 INVALID_PARAMETER nêu tên tham số; không tới service")
  void unreadableParametersAreBadRequests(
      String name, MockHttpServletRequestBuilder request, String parameter) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
        .andExpect(jsonPath("$.parameter").value(parameter));

    verifyNoInteractions(service);
  }

  static Stream<Arguments> malformedBodies() {
    return Stream.of(
        Arguments.of(
            "thiếu body",
            post(EXPLAIN).param("matchMode", "STRICT").contentType(MediaType.APPLICATION_JSON)),
        Arguments.of("body là mảng", json(post(EXPLAIN).param("matchMode", "STRICT"), "[]")),
        Arguments.of(
            "status viết thường",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"status\":\"active\"}")),
        Arguments.of(
            "categoryId không phải UUID", json(post(BENCHMARK), "{\"categoryId\":\"abc\"}")),
        Arguments.of("size là chữ", json(post(BENCHMARK), "{\"size\":\"many\"}")),
        Arguments.of("hai JSON liền nhau", json(post(BENCHMARK), "{} {}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedBodies")
  @DisplayName("body không đọc được → 400 MALFORMED_REQUEST; không tới service")
  void malformedBodiesAreBadRequests(String name, MockHttpServletRequestBuilder request)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.code()));

    verifyNoInteractions(service);
  }

  /** {@code curl -d '{...}'} không kèm {@code -H 'Content-Type: application/json'}. */
  @Test
  @DisplayName("body không phải JSON (form của curl -d) → 415, Accept liệt kê kiểu đọc được")
  void bodyMustBeJson() throws Exception {
    String accept =
        mockMvc
            .perform(
                post(EXPLAIN)
                    .param("matchMode", "STRICT")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("{\"q\":\"iphone\"}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code()))
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.ACCEPT);

    assertThat(accept).contains(MediaType.APPLICATION_JSON_VALUE);
    verifyNoInteractions(service);
  }

  // ================================================================
  // JSON trả ra
  // ================================================================

  @Nested
  @DisplayName("JSON trả ra")
  class ResponseBody {

    /** Số 64 bit vượt 2^53 — JavaScript đọc thành number là sai. */
    @Test
    @DisplayName("slow-queries: queryId là chuỗi, đủ 19 chữ số; tiền giữ 2 chữ số lẻ")
    void queryIdIsAString() throws Exception {
      when(service.slowQueries(20))
          .thenReturn(
              List.of(
                  new SlowQueryResponse(
                      4028545980519127792L,
                      "SELECT count(*) FROM app.orders",
                      1,
                      new BigDecimal("84949.06"),
                      new BigDecimal("84949.06"),
                      new BigDecimal("84949.06"),
                      new BigDecimal("31.25"),
                      1,
                      new BigDecimal("2.10"))));

      assertThat(body(get(ADMIN + "/slow-queries")))
          .isEqualTo(
              "[{\"queryId\":\"4028545980519127792\",\"query\":\"SELECT count(*) FROM app.orders\","
                  + "\"calls\":1,\"totalMs\":84949.06,\"meanMs\":84949.06,\"maxMs\":84949.06,"
                  + "\"pctOfTotal\":31.25,\"rows\":1,\"cacheHitPct\":2.10}]");
    }

    @Test
    @DisplayName("thống kê bảng: thời điểm ISO-8601 UTC, field null vẫn có mặt")
    void tableStats() throws Exception {
      when(service.analyze("orders"))
          .thenReturn(
              new TableStatsResponse(
                  "orders",
                  8192,
                  "8192 bytes",
                  0,
                  "0 bytes",
                  8192,
                  "8192 bytes",
                  0,
                  0,
                  null,
                  0,
                  1,
                  0,
                  null,
                  null,
                  Instant.parse("2026-10-13T09:43:01.337556Z"),
                  null));

      assertThat(body(post(ADMIN + "/tables/{name}/analyze", "orders")))
          .contains("\"deadPct\":null,")
          .endsWith(
              "\"lastVacuum\":null,\"lastAutovacuum\":null,"
                  + "\"lastAnalyze\":\"2026-10-13T09:43:01.337556Z\",\"lastAutoanalyze\":null}");
    }

    /** {@code plan} là JSON thô của Postgres — chép nguyên, không bọc thành chuỗi. */
    @Test
    @DisplayName("explain: plan là mảng JSON nguyên văn; thời gian giữ 3 chữ số lẻ")
    void explainKeepsThePlanAsJson() throws Exception {
      String plan =
          "[{\"Plan\":{\"Node Type\":\"Limit\",\"Actual Total Time\":0.540,\"Actual Loops\":1},"
              + "\"Planning Time\":0.199,\"Execution Time\":0.612}]";
      PlanNodeResponse limit =
          new PlanNodeResponse(
              0, "Limit", null, null, 20, 20, 1, new BigDecimal("0.540"), new BigDecimal("0.540"));
      when(service.explainProductSearch(any(), eq(MatchMode.STRICT), any()))
          .thenReturn(
              new ExplainResponse(
                  "SELECT ...",
                  Map.of("limit", 20, "offset", 0L, "q", "iphone"),
                  Map.of("pg_trgm.word_similarity_threshold", "0.6"),
                  new BigDecimal("0.199"),
                  new BigDecimal("0.612"),
                  12,
                  0,
                  new BigDecimal("100.00"),
                  limit,
                  List.of(limit),
                  // như ObjectMapper của app: use-big-decimal-for-floats — số giữ đủ chữ số
                  JsonMapper.builder()
                      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                      .build()
                      .readTree(plan)));

      String json = body(json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"q\":\"iphone\"}"));

      assertThat(json)
          .contains("\"planningMs\":0.199,\"executionMs\":0.612,")
          .contains("\"settings\":{\"pg_trgm.word_similarity_threshold\":\"0.6\"}")
          .contains("\"selfMs\":0.540")
          .endsWith(",\"plan\":" + plan + "}");
    }

    @Test
    @DisplayName("benchmark: ms giữ 2 chữ số lẻ, kèm matchMode và tổng của lần cuối")
    void benchmark() throws Exception {
      when(service.benchmarkProductSearch(any(), any(), anyInt()))
          .thenReturn(
              new BenchmarkResponse(
                  10,
                  100,
                  100,
                  new BigDecimal("80.02"),
                  new BigDecimal("86.10"),
                  new BigDecimal("120.32"),
                  new BigDecimal("131.00"),
                  new BigDecimal("131.00"),
                  new BigDecimal("91.54"),
                  MatchMode.STRICT,
                  10138));

      assertThat(body(json(post(BENCHMARK), "{\"q\":\"iphone\"}")))
          .isEqualTo(
              "{\"warmUp\":10,\"requestedIterations\":100,\"iterations\":100,\"minMs\":80.02,"
                  + "\"p50Ms\":86.10,\"p95Ms\":120.32,\"p99Ms\":131.00,\"maxMs\":131.00,"
                  + "\"meanMs\":91.54,\"matchMode\":\"STRICT\",\"totalElements\":10138}");
    }
  }

  // ================================================================
  // Lỗi của service ra HTTP
  // ================================================================

  /** Lỗi service ném, endpoint gây ra nó, và status / mã mong đợi. */
  static Stream<Arguments> serviceErrors() {
    return Stream.of(
        Arguments.of(
            "bảng không có → 404 ADM_TABLE_NOT_FOUND",
            get(ADMIN + "/tables/{name}/stats", "khong_co"),
            (Function<AdminService, Object>) s -> s.tableStats("khong_co"),
            new NotFoundException(
                ErrorCode.ADM_TABLE_NOT_FOUND,
                "Schema app không có bảng / materialized view 'khong_co'."),
            404),
        Arguments.of(
            "bảng bận → 409 ADM_TABLE_LOCKED",
            post(ADMIN + "/tables/{name}/vacuum", "orders").param("full", "true"),
            (Function<AdminService, Object>) s -> s.vacuum("orders", true),
            new ConflictException(
                ErrorCode.ADM_TABLE_LOCKED,
                "VACUUM FULL 'orders' đã chờ khóa của bảng 2 giây và bỏ — transaction khác đang"
                    + " giữ bảng, thử lại sau.",
                new IllegalStateException("55P03")),
            409),
        Arguments.of(
            "EXPLAIN quá giờ → 503 ADM_QUERY_TIMEOUT",
            json(post(EXPLAIN).param("matchMode", "STRICT"), "{\"q\":\"iphone\"}"),
            (Function<AdminService, Object>)
                s ->
                    s.explainProductSearch(
                        new ProductSearchCriteria("iphone", null, null, null, null, null),
                        MatchMode.STRICT,
                        FIRST_PAGE),
            new ServiceUnavailableException(
                ErrorCode.ADM_QUERY_TIMEOUT,
                "EXPLAIN ANALYZE bị Postgres hủy sau 10 giây (statement_timeout).",
                new IllegalStateException("57014")),
            503),
        Arguments.of(
            "tên bảng quá 63 byte (service đếm byte) → 400, không errors[]",
            post(ADMIN + "/tables/{name}/analyze", "ă".repeat(32)),
            (Function<AdminService, Object>) s -> s.analyze("ă".repeat(32)),
            new BadRequestException(
                ErrorCode.VALIDATION_FAILED,
                "Tên bảng tối đa 63 byte (giới hạn tên của Postgres), nhận 64 byte."),
            400));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("serviceErrors")
  @DisplayName("lỗi của service → status + mã + detail giữ nguyên, problem+json")
  void serviceErrorsKeepTheirStatusAndCode(
      String name,
      MockHttpServletRequestBuilder request,
      Function<AdminService, Object> call,
      BusinessException error,
      int status)
      throws Exception {
    when(call.apply(service)).thenThrow(error);

    mockMvc
        .perform(request)
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.code").value(error.getCode().code()))
        .andExpect(jsonPath("$.detail").value(error.getDetail()))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  static Stream<Arguments> wrongMethods() {
    return Stream.of(
        Arguments.of(get(ADMIN + "/tables/{name}/analyze", "orders"), "POST"),
        Arguments.of(get(ADMIN + "/tables/{name}/vacuum", "orders"), "POST"),
        Arguments.of(get(EXPLAIN), "POST"),
        Arguments.of(post(ADMIN + "/indexes"), "GET"),
        Arguments.of(post(ADMIN + "/tables/{name}/stats", "orders"), "GET"));
  }

  @ParameterizedTest
  @MethodSource("wrongMethods")
  @DisplayName("sai method → 405, Allow nêu method đúng")
  void wrongMethodIsNotAllowed(MockHttpServletRequestBuilder request, String allow)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string(HttpHeaders.ALLOW, allow));

    verifyNoInteractions(service);
  }
}
