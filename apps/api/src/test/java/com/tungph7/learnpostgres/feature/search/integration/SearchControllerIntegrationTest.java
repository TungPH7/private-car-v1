package com.tungph7.learnpostgres.feature.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.dto.PagedResponse.PageMetadata;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * Tìm sản phẩm đi hết HTTP → controller → service → Postgres, không mock gì — mỗi AC của tìm kiếm
 * (AC-01 – AC-05, AC-17) qua đúng {@code GET /api/v1/search/products}, so với đáp số tính trước
 * bằng {@code word_similarity} trên chính DB và với câu SQL viết tay.
 *
 * <p>Các lớp test đã có mỗi lớp thiếu một nửa: {@code SearchControllerWebMvcTest} có HTTP nhưng
 * service là mock; {@code SearchQueryRepositoryJdbcTest} và {@code SearchServiceIntegrationTest} có
 * Postgres nhưng không qua HTTP. Ở đây kiểm những gì chỉ đúng khi ghép cả hai:
 *
 * <ul>
 *   <li>Số liệu thật ra JSON: điểm và giá giữ 2 chữ số lẻ, {@code createdAt} ISO-8601 UTC, {@code
 *       sku} {@code null} có mặt, {@code matchMode} theo ngưỡng đã cho ra trang.
 *   <li>Mặc định của tham số tới tận câu SQL: không gửi {@code size} là {@code LIMIT 20}, không gửi
 *       {@code sort} là mới nhất trước.
 *   <li>Cross-check: số sản phẩm khớp = câu SQL viết tay bằng hàm {@code word_similarity} — không
 *       qua toán tử {@code %>}, index hay tham số ngưỡng của app.
 *   <li>Lỗi của service thật, qua handler thật: 400 không có {@code errors[]}.
 * </ul>
 *
 * <p>Cố ý KHÔNG {@code @Transactional}: request chỉ thấy thứ đã commit. Dữ liệu commit trong {@link
 * TransactionTemplate}, khoanh trong ba category riêng (tìm theo từ khoá trên cả DB thì gặp cả sản
 * phẩm của lớp test khác) — dọn theo tiền tố {@value #SLUG} trước và sau mỗi test.
 *
 * <pre>
 * Phụ kiện  A2 Ốp lưng iPhone 15 Pro Trong suốt       150.000  ACTIVE    07/01  SRCH-HTTP-02
 *           A1 Ốp lưng iPhone 15 Pro Max Silicon Đen  150.000  ACTIVE    06/01  SRCH-HTTP-01
 *           A3 Iphone Case Leather                    500.000  ACTIVE    04/01  (không SKU)
 *           A4 Tai nghe AirPods Pro 2 Trắng          5.990.000 DRAFT     03/01  SRCH-HTTP-04
 *           A5 Sạc dự phòng Anker 20000mAh            890.000  ACTIVE    02/01  SRCH-HTTP-05
 * Thiết bị  D1 Điện thoại iPhone 15 Pro 256GB      28.990.000  ACTIVE    06/01  SRCH-HTTP-11
 *           D2 Điện thoại Galaxy S24 Ultra 256GB   25.990.000  ARCHIVED  01/01  SRCH-HTTP-12
 *           D3 Laptop MacBook Air M3 Bạc           27.990.000  ACTIVE    31/12/2025
 * Cáp sạc   25 sản phẩm "Cáp sạc USB-C 1m số 1" … "số 25", số lớn hơn mới hơn
 *
 * word_similarity (đã chuẩn hoá): "iphone" — A1, A2, A3, D1 1,00; "iphne" — 0,50 (dưới ngưỡng
 * chuẩn 0,6); "iphone 15 pro max" — A1 1,00, A2 0,78; "case leather" — A3 1,00
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class SearchControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String SEARCH = "/api/v1/search/products";
  private static final String SLUG = "srch-http-";
  private static final BigDecimal ONE = new BigDecimal("1.00");

  private static final String A1 = "Ốp lưng iPhone 15 Pro Max Silicon Đen";
  private static final String A2 = "Ốp lưng iPhone 15 Pro Trong suốt";
  private static final String A3 = "Iphone Case Leather";
  private static final String A4 = "Tai nghe AirPods Pro 2 Trắng";
  private static final String A5 = "Sạc dự phòng Anker 20000mAh";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private UUID accessories;
  private UUID devices;
  private UUID cables;
  private UUID a3;
  private int slugs;

  @BeforeEach
  void seed() {
    cleanup();
    tx.executeWithoutResult(
        status -> {
          accessories = category("Phụ kiện (HTTP search)", "acc");
          devices = category("Thiết bị (HTTP search)", "dev");
          cables = category("Cáp sạc (HTTP search)", "cable");
          product(A2, "SRCH-HTTP-02", "150000", "ACTIVE", accessories, "2026-01-07T10:00+07:00");
          product(A1, "SRCH-HTTP-01", "150000", "ACTIVE", accessories, "2026-01-06T10:00+07:00");
          a3 = product(A3, null, "500000", "ACTIVE", accessories, "2026-01-04T10:00+07:00");
          product(A4, "SRCH-HTTP-04", "5990000", "DRAFT", accessories, "2026-01-03T10:00+07:00");
          product(A5, "SRCH-HTTP-05", "890000", "ACTIVE", accessories, "2026-01-02T10:00+07:00");
          product(
              "Điện thoại iPhone 15 Pro 256GB",
              "SRCH-HTTP-11",
              "28990000",
              "ACTIVE",
              devices,
              "2026-01-06T09:00+07:00");
          product(
              "Điện thoại Galaxy S24 Ultra 256GB",
              "SRCH-HTTP-12",
              "25990000",
              "ARCHIVED",
              devices,
              "2026-01-01T10:00+07:00");
          product(
              "Laptop MacBook Air M3 Bạc",
              "SRCH-HTTP-13",
              "27990000",
              "ACTIVE",
              devices,
              "2025-12-31T10:00+07:00");
          for (int i = 1; i <= 25; i++) {
            product(
                "Cáp sạc USB-C 1m số " + i,
                null,
                "99000",
                "ACTIVE",
                cables,
                OffsetDateTime.parse("2026-02-01T10:00+07:00").plusHours(i).toString());
          }
        });
  }

  /** BẮT BUỘC dọn: dữ liệu đã commit. {@link #seed()} cũng gọi ở dòng đầu — cứu lần chạy bỏ dở. */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("DELETE FROM app.products WHERE slug LIKE :slug")
              .param("slug", SLUG + "%")
              .update();
          jdbc.sql("DELETE FROM app.categories WHERE slug LIKE :slug")
              .param("slug", SLUG + "%")
              .update();
        });
  }

  private UUID category(String name, String slug) {
    return jdbc.sql("INSERT INTO app.categories (name, slug) VALUES (:name, :slug) RETURNING id")
        .param("name", name)
        .param("slug", SLUG + slug)
        .query(UUID.class)
        .single();
  }

  private UUID product(
      String name, String sku, String price, String status, UUID category, String createdAt) {
    return jdbc.sql(
            """
            INSERT INTO app.products (name, slug, sku, price, status, category_id, created_at)
            VALUES (:name, :slug, :sku, :price, :status, :category, :createdAt)
            RETURNING id
            """)
        .param("name", name)
        .param("slug", SLUG + ++slugs)
        .param("sku", sku)
        .param("price", new BigDecimal(price))
        .param("status", status)
        .param("category", category)
        .param("createdAt", OffsetDateTime.parse(createdAt))
        .query(UUID.class)
        .single();
  }

  /** GET tìm sản phẩm với các cặp tham số {@code name, value, name, value...}. */
  private static MockHttpServletRequestBuilder search(String... params) {
    MockHttpServletRequestBuilder request = get(SEARCH);
    for (int i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    return request;
  }

  /** Request → 200, body nguyên văn. */
  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private ProductSearchPageResponse read(MockHttpServletRequestBuilder request) throws Exception {
    return objectMapper.readValue(body(request), ProductSearchPageResponse.class);
  }

  private static List<String> names(ProductSearchPageResponse page) {
    return page.content().stream().map(ProductSearchResponse::name).toList();
  }

  /**
   * Số sản phẩm của category có {@code word_similarity} với từ khoá đạt {@code threshold} — viết
   * tay bằng hàm, không qua toán tử {@code %>} / index / tham số ngưỡng mà câu của app dùng.
   */
  private long manualCount(UUID category, String q, double threshold) {
    return jdbc.sql(
            """
            SELECT count(*) FROM app.products
            WHERE category_id = :category
              AND word_similarity(app.search_normalize(:q), app.search_normalize(name))
                  >= :threshold
            """)
        .param("category", category)
        .param("q", q)
        .param("threshold", threshold)
        .query(Long.class)
        .single();
  }

  // ================================================================
  // Search correctness — AC-01 – AC-05, AC-17
  // ================================================================

  @Test
  @DisplayName(
      "AC-01: 'iphone' ra mọi tên có từ đó, mọi kiểu viết hoa — 'Ốp lưng iPhone ...' lẫn 'Iphone"
          + " Case', điểm 1.00, STRICT; số kết quả = câu SQL viết tay")
  void matchesEveryNameContainingTheWord() throws Exception {
    ProductSearchPageResponse page =
        read(search("q", "iphone", "categoryId", accessories.toString()));

    assertThat(page.matchMode()).isEqualTo(MatchMode.STRICT);
    assertThat(page.content())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(tuple(A2, ONE), tuple(A1, ONE), tuple(A3, ONE));
    assertThat(page.page().totalElements())
        .isEqualTo(manualCount(accessories, "iphone", 0.6))
        .isEqualTo(3);
  }

  @Test
  @DisplayName(
      "AC-02: 'iphne' không khớp ở ngưỡng chuẩn (0,50 < 0,6) → tìm lại ở ngưỡng nới: 3 sản phẩm"
          + " iPhone, điểm 0.50, matchMode RELAXED")
  void typoFallsBackToTheRelaxedThreshold() throws Exception {
    ProductSearchPageResponse page =
        read(search("q", "iphne", "categoryId", accessories.toString(), "sort", "RELEVANCE"));

    assertThat(manualCount(accessories, "iphne", 0.6)).isZero();
    assertThat(page.matchMode()).isEqualTo(MatchMode.RELAXED);
    // hòa điểm → mới hơn trước
    assertThat(page.content())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(
            tuple(A2, new BigDecimal("0.50")),
            tuple(A1, new BigDecimal("0.50")),
            tuple(A3, new BigDecimal("0.50")));
    assertThat(page.page().totalElements()).isEqualTo(manualCount(accessories, "iphne", 0.4));
  }

  @Test
  @DisplayName(
      "AC-03: từ khoá + categoryId → đúng phần giao — 'iphone' trong Thiết bị chỉ ra 'Điện thoại"
          + " iPhone ...'; cùng số với câu SQL viết tay ở từng category")
  void queryAndCategoryIntersect() throws Exception {
    ProductSearchPageResponse inDevices =
        read(search("q", "iphone", "categoryId", devices.toString()));
    ProductSearchPageResponse inAccessories =
        read(search("q", "iphone", "categoryId", accessories.toString()));

    assertThat(names(inDevices)).containsExactly("Điện thoại iPhone 15 Pro 256GB");
    assertThat(inDevices.page().totalElements()).isEqualTo(manualCount(devices, "iphone", 0.6));
    assertThat(inAccessories.page().totalElements())
        .isEqualTo(manualCount(accessories, "iphone", 0.6));
    assertThat(names(inAccessories)).doesNotContainAnyElementsOf(names(inDevices));
  }

  @Test
  @DisplayName(
      "AC-04: sort=RELEVANCE → điểm cao trước ('iphone 15 pro max': A1 1.00, rồi A2 0.78); cùng"
          + " từ khoá sắp NEWEST thì A2 mới hơn đứng trước")
  void relevanceRanksTheClosestNameFirst() throws Exception {
    String category = accessories.toString();

    assertThat(
            read(search("q", "iphone 15 pro max", "categoryId", category, "sort", "RELEVANCE"))
                .content())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(tuple(A1, ONE), tuple(A2, new BigDecimal("0.78")));
    assertThat(names(read(search("q", "iphone 15 pro max", "categoryId", category))))
        .containsExactly(A2, A1);
  }

  @Test
  @DisplayName(
      "AC-05: không có từ khoá (không gửi, hay toàn khoảng trắng) → mới nhất trước, kể cả"
          + " sort=RELEVANCE; score null, matchMode null")
  void noKeywordIsNewestFirst() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            search("categoryId", accessories.toString(), "sort", "RELEVANCE"),
            search("q", "   ", "categoryId", accessories.toString(), "sort", "RELEVANCE"))) {
      ProductSearchPageResponse page = read(request);

      assertThat(page.matchMode()).isNull();
      assertThat(names(page)).containsExactly(A2, A1, A3, A4, A5);
      assertThat(page.content()).extracting(ProductSearchResponse::score).containsOnlyNulls();
    }
  }

  @Test
  @DisplayName(
      "AC-17: không phân biệt dấu / hoa thường — 'ỐP LƯNG', 'op lung', 'Ốp Lưng' cùng kết quả;"
          + " 'dien thoai' ra 'Điện thoại ...'")
  void accentAndCaseInsensitive() throws Exception {
    String category = accessories.toString();

    for (String q : List.of("ỐP LƯNG", "op lung", "Ốp Lưng")) {
      assertThat(names(read(search("q", q, "categoryId", category)))).as(q).containsExactly(A2, A1);
    }
    assertThat(names(read(search("q", "dien thoai", "categoryId", devices.toString()))))
        .containsExactly("Điện thoại iPhone 15 Pro 256GB", "Điện thoại Galaxy S24 Ultra 256GB");
  }

  // ================================================================
  // Chỉ đúng khi ghép HTTP + service + Postgres
  // ================================================================

  @Test
  @DisplayName(
      "Mặc định tới tận SQL: không gửi size → 20 sản phẩm / trang (25 khớp → 2 trang), không gửi"
          + " sort → mới nhất trước; trang 2 là 5 sản phẩm cũ nhất")
  void defaultsReachTheSql() throws Exception {
    ProductSearchPageResponse first = read(search("categoryId", cables.toString()));
    ProductSearchPageResponse second = read(search("categoryId", cables.toString(), "page", "1"));

    assertThat(first.page()).isEqualTo(new PageMetadata(0, 20, 25, 2));
    assertThat(names(first))
        .isEqualTo(Stream.iterate(25, i -> i >= 6, i -> i - 1).map(this::cable).toList());
    assertThat(names(second)).containsExactly(cable(5), cable(4), cable(3), cable(2), cable(1));
  }

  private String cable(int number) {
    return "Cáp sạc USB-C 1m số " + number;
  }

  @Test
  @DisplayName(
      "JSON từ số liệu thật: điểm, giá 2 chữ số lẻ; createdAt ISO-8601 UTC (10:00 giờ VN = 03:00Z);"
          + " sku null có mặt; trang + matchMode")
  void jsonOfRealValues() throws Exception {
    assertThat(body(search("q", "case leather", "categoryId", accessories.toString())))
        .isEqualTo(
            ("{\"content\":[{\"id\":\"%s\",\"name\":\"Iphone Case Leather\",\"slug\":\"%s\","
                 + "\"sku\":null,\"price\":500000.00,\"status\":\"ACTIVE\",\"categoryId\":\"%s\","
                 + "\"createdAt\":\"2026-01-04T03:00:00Z\",\"score\":1.00}],"
                 + "\"page\":{\"number\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1},"
                 + "\"matchMode\":\"STRICT\"}")
                .formatted(a3, SLUG + 3, accessories));
  }

  @Test
  @DisplayName(
      "Trang sau trang cuối vẫn là kết quả chuẩn, tổng đúng; không gì khớp ở cả hai ngưỡng →"
          + " RELAXED, rỗng")
  void emptyPagesKeepTheirMatchMode() throws Exception {
    String category = accessories.toString();

    assertThat(body(search("q", "iphone", "categoryId", category, "page", "5", "size", "2")))
        .isEqualTo(
            "{\"content\":[],\"page\":{\"number\":5,\"size\":2,\"totalElements\":3,"
                + "\"totalPages\":2},\"matchMode\":\"STRICT\"}");
    assertThat(body(search("q", "xyzabc", "categoryId", category)))
        .isEqualTo(
            "{\"content\":[],\"page\":{\"number\":0,\"size\":20,\"totalElements\":0,"
                + "\"totalPages\":0},\"matchMode\":\"RELAXED\"}");
  }

  static Stream<Arguments> invalidSearches() {
    return Stream.of(
        Arguments.of(
            "từ khoá 1 ký tự sau khi bỏ khoảng trắng",
            search("q", " a "),
            "Từ khoá phải dài 2 – 80 ký tự (không tính khoảng trắng hai đầu), nhận 1."),
        Arguments.of(
            "từ khoá 81 ký tự",
            search("q", "x".repeat(81)),
            "Từ khoá phải dài 2 – 80 ký tự (không tính khoảng trắng hai đầu), nhận 81."),
        Arguments.of(
            "priceMin > priceMax",
            search("priceMin", "500000", "priceMax", "100000"),
            "priceMin (500000) phải nhỏ hơn hoặc bằng priceMax (100000)."));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSearches")
  @DisplayName(
      "service thật chặn đầu vào → 400 VALIDATION_FAILED, detail của service, không errors[]")
  void serviceRejectsInvalidSearches(
      String name, MockHttpServletRequestBuilder request, String detail) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value(detail))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }
}
