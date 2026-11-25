package com.tungph7.learnpostgres.feature.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;

/**
 * Slice test cho {@link SearchQueryRepository} trên Postgres thật, cùng locale {@code C} với DB dev
 * ({@code DatabaseSetupJdbcTest}) — đáp số tính trước bằng {@code word_similarity} trên chính DB
 * ({@code F08__search-performance/02-domain-and-repository.md}).
 *
 * <p>Mọi thứ chạy trong transaction của {@code @JdbcTest}, rollback cuối test. Container dùng chung
 * giữa các test class, nên kết quả được khoanh bằng hai category chỉ test này tạo — điều kiện
 * category ghép AND, không đổi cách so từ khoá.
 *
 * <pre>
 * Phụ kiện  A2 Ốp lưng iPhone 15 Pro Trong suốt       150.000  ACTIVE    07/01
 *           A1 Ốp lưng iPhone 15 Pro Max Silicon Đen  150.000  ACTIVE    06/01  SRCH-OPL-01
 *           A3 Iphone Case Leather                    500.000  ACTIVE    04/01  (không SKU)
 *           A4 Tai nghe AirPods Pro 2 Trắng          5.990.000 DRAFT     03/01
 *           A5 Sạc dự phòng Anker 20000mAh            890.000  ACTIVE    02/01
 * Thiết bị  D1 Điện thoại iPhone 15 Pro 256GB      28.990.000  ACTIVE    06/01
 *           D2 Điện thoại Galaxy S24 Ultra 256GB   25.990.000  ARCHIVED  01/01
 *           D3 Laptop MacBook Air M3 Bạc           27.990.000  ACTIVE    31/12/2025
 * </pre>
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchQueryRepository.class)
class SearchQueryRepositoryJdbcTest extends AbstractContainerBaseTest {

  private static final Pattern QUOTED_VALUE = Pattern.compile("'([A-Z_]+)'");
  private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([a-zA-Z]+)");
  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
  private static final BigDecimal ONE = new BigDecimal("1.00");

  @Autowired SearchQueryRepository repository;
  @Autowired JdbcClient jdbc;

  private UUID accessories;
  private UUID devices;
  private UUID a1;
  private int slugs;

  @BeforeEach
  void seed() {
    accessories = category("Phụ kiện (test search)", "srch-acc");
    devices = category("Thiết bị (test search)", "srch-dev");
    product(
        "Ốp lưng iPhone 15 Pro Trong suốt",
        "SRCH-OPL-02",
        "150000",
        "ACTIVE",
        accessories,
        "2026-01-07T10:00+07:00");
    a1 =
        product(
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "SRCH-OPL-01",
            "150000",
            "ACTIVE",
            accessories,
            "2026-01-06T10:00+07:00");
    product("Iphone Case Leather", null, "500000", "ACTIVE", accessories, "2026-01-04T10:00+07:00");
    product(
        "Tai nghe AirPods Pro 2 Trắng",
        "SRCH-TN-04",
        "5990000",
        "DRAFT",
        accessories,
        "2026-01-03T10:00+07:00");
    product(
        "Sạc dự phòng Anker 20000mAh",
        "SRCH-SDP-05",
        "890000",
        "ACTIVE",
        accessories,
        "2026-01-02T10:00+07:00");
    product(
        "Điện thoại iPhone 15 Pro 256GB",
        "SRCH-DT-01",
        "28990000",
        "ACTIVE",
        devices,
        "2026-01-06T09:00+07:00");
    product(
        "Điện thoại Galaxy S24 Ultra 256GB",
        "SRCH-DT-02",
        "25990000",
        "ARCHIVED",
        devices,
        "2026-01-01T10:00+07:00");
    product(
        "Laptop MacBook Air M3 Bạc",
        "SRCH-LT-03",
        "27990000",
        "ACTIVE",
        devices,
        "2025-12-31T10:00+07:00");
  }

  private UUID category(String name, String slug) {
    return jdbc.sql("INSERT INTO app.categories (name, slug) VALUES (:name, :slug) RETURNING id")
        .param("name", name)
        .param("slug", slug)
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
        .param("slug", "srch-" + ++slugs)
        .param("sku", sku)
        .param("price", new BigDecimal(price))
        .param("status", status)
        .param("category", category)
        .param("createdAt", OffsetDateTime.parse(createdAt))
        .query(UUID.class)
        .single();
  }

  private static ProductSearchCriteria query(String q, UUID category) {
    return new ProductSearchCriteria(q, category, null, null, null, null);
  }

  private static ProductSearchCriteria sorted(String q, UUID category, SearchSort sort) {
    return new ProductSearchCriteria(q, category, null, null, null, sort);
  }

  private Page<ProductSearchResponse> search(ProductSearchCriteria criteria, MatchMode mode) {
    return repository.searchProducts(criteria, mode, FIRST_PAGE);
  }

  private List<String> names(ProductSearchCriteria criteria) {
    return names(search(criteria, MatchMode.STRICT));
  }

  private static List<String> names(Page<ProductSearchResponse> page) {
    return page.getContent().stream().map(ProductSearchResponse::name).toList();
  }

  // -----------------------------------------------------------------
  // So từ khoá với tên và SKU
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-17: không phân biệt dấu / hoa thường — 'op lung', 'ỐP LƯNG' ra tên có dấu; 'dien thoai'"
          + " ra 'Điện thoại'")
  void accentAndCaseInsensitive() {
    List<String> opLung =
        List.of("Ốp lưng iPhone 15 Pro Trong suốt", "Ốp lưng iPhone 15 Pro Max Silicon Đen");

    assertThat(names(query("op lung", accessories))).isEqualTo(opLung);
    assertThat(names(query("ỐP LƯNG", accessories))).isEqualTo(opLung);
    assertThat(names(query("  op lung  ", accessories))).isEqualTo(opLung);
    assertThat(names(query("dien thoai", devices)))
        .containsExactly("Điện thoại iPhone 15 Pro 256GB", "Điện thoại Galaxy S24 Ultra 256GB");
  }

  @Test
  @DisplayName(
      "AC-01: 'iphone' ra mọi tên có từ đó — 'Ốp lưng iPhone ...' lẫn 'Iphone Case', điểm 1,00 —"
          + " dù tên dài")
  void matchesEveryNameContainingTheWord() {
    assertThat(search(query("iphone", accessories), MatchMode.STRICT).getContent())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(
            tuple("Ốp lưng iPhone 15 Pro Trong suốt", ONE),
            tuple("Ốp lưng iPhone 15 Pro Max Silicon Đen", ONE),
            tuple("Iphone Case Leather", ONE));
  }

  @Test
  @DisplayName(
      "AC-02: 'tai nge' khớp ở STRICT (0,75); 'iphne' (0,50) chỉ khớp ở RELAXED — ngưỡng đặt lại"
          + " ở mỗi lần gọi")
  void typoToleranceDependsOnMatchMode() {
    assertThat(search(query("tai nge", accessories), MatchMode.STRICT).getContent())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(tuple("Tai nghe AirPods Pro 2 Trắng", new BigDecimal("0.75")));

    assertThat(search(query("iphne", accessories), MatchMode.STRICT)).isEmpty();
    assertThat(search(query("iphne", accessories), MatchMode.RELAXED).getContent())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(
            tuple("Ốp lưng iPhone 15 Pro Trong suốt", new BigDecimal("0.50")),
            tuple("Ốp lưng iPhone 15 Pro Max Silicon Đen", new BigDecimal("0.50")),
            tuple("Iphone Case Leather", new BigDecimal("0.50")));
    // Cùng transaction, gọi lại STRICT: ngưỡng 0,4 của lần trước không còn tác dụng
    assertThat(search(query("iphne", accessories), MatchMode.STRICT)).isEmpty();
  }

  @Test
  @DisplayName("Thứ tự từ không quan trọng: 'iphone op lung' ra 'Ốp lưng iPhone ...'")
  void wordOrderDoesNotMatter() {
    assertThat(names(query("iphone op lung", accessories)))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt", "Ốp lưng iPhone 15 Pro Max Silicon Đen");
  }

  @Test
  @DisplayName(
      "SKU khớp khi trùng cả mã (không phân biệt hoa thường), điểm 1,00; tiền tố của SKU thì"
          + " không — map đủ mọi cột")
  void skuMatchesTheWholeCodeOnly() {
    assertThat(search(query("srch-opl-01", accessories), MatchMode.STRICT).getContent())
        .containsExactly(
            new ProductSearchResponse(
                a1,
                "Ốp lưng iPhone 15 Pro Max Silicon Đen",
                "srch-2",
                "SRCH-OPL-01",
                new BigDecimal("150000.00"),
                ProductStatus.ACTIVE,
                accessories,
                Instant.parse("2026-01-06T03:00:00Z"),
                ONE));
    assertThat(search(query("SRCH-OPL", accessories), MatchMode.RELAXED)).isEmpty();
  }

  // -----------------------------------------------------------------
  // Bộ lọc
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-03: từ khoá + category → giao của hai điều kiện")
  void queryAndCategoryIntersect() {
    assertThat(names(query("iphone", devices))).containsExactly("Điện thoại iPhone 15 Pro 256GB");
  }

  @Test
  @DisplayName("Giá lấy cả hai mốc; lọc theo trạng thái")
  void priceRangeIsInclusiveAndStatusFilters() {
    assertThat(
            names(
                new ProductSearchCriteria(
                    null,
                    accessories,
                    new BigDecimal("150000"),
                    new BigDecimal("500000"),
                    null,
                    null)))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "Iphone Case Leather");
    assertThat(
            names(
                new ProductSearchCriteria(
                    null, accessories, null, null, ProductStatus.DRAFT, null)))
        .containsExactly("Tai nghe AirPods Pro 2 Trắng");
  }

  // -----------------------------------------------------------------
  // Thứ tự
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-04: RELEVANCE — điểm cao trước (khác NEWEST); hòa điểm thì mới hơn trước")
  void relevanceSortsByScoreThenNewest() {
    assertThat(
            search(sorted("iphone 15 pro max", accessories, SearchSort.RELEVANCE), MatchMode.STRICT)
                .getContent())
        .extracting(ProductSearchResponse::name, ProductSearchResponse::score)
        .containsExactly(
            tuple("Ốp lưng iPhone 15 Pro Max Silicon Đen", ONE),
            tuple("Ốp lưng iPhone 15 Pro Trong suốt", new BigDecimal("0.78")));
    assertThat(names(sorted("iphone 15 pro max", accessories, SearchSort.NEWEST)))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt", "Ốp lưng iPhone 15 Pro Max Silicon Đen");
    assertThat(names(sorted("iphone", accessories, SearchSort.RELEVANCE)))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "Iphone Case Leather");
  }

  @Test
  @DisplayName("AC-05: không có từ khoá — RELEVANCE thành mới nhất trước, score null")
  void noQueryFallsBackToNewest() {
    Page<ProductSearchResponse> page =
        search(sorted("   ", accessories, SearchSort.RELEVANCE), MatchMode.STRICT);

    assertThat(names(page))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "Iphone Case Leather",
            "Tai nghe AirPods Pro 2 Trắng",
            "Sạc dự phòng Anker 20000mAh");
    assertThat(page.getContent()).extracting(ProductSearchResponse::score).containsOnlyNulls();
  }

  @Test
  @DisplayName("Theo giá — hòa giá thì mới hơn trước, rồi theo id, ở cả hai chiều")
  void priceSortsBreakTiesByNewestThenId() {
    assertThat(names(sorted(null, accessories, SearchSort.PRICE_ASC)))
        .containsExactly(
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "Iphone Case Leather",
            "Sạc dự phòng Anker 20000mAh",
            "Tai nghe AirPods Pro 2 Trắng");
    assertThat(names(sorted(null, accessories, SearchSort.PRICE_DESC)))
        .containsExactly(
            "Tai nghe AirPods Pro 2 Trắng",
            "Sạc dự phòng Anker 20000mAh",
            "Iphone Case Leather",
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen");

    // Cùng giá, cùng thời điểm tạo → theo id. Postgres so uuid từng byte không dấu — đúng thứ tự
    // của chuỗi hex (UUID.compareTo của Java so long có dấu)
    UUID twins = category("Cặp song sinh (test search)", "srch-twins");
    UUID x = product("Cáp sạc X", null, "99000", "ACTIVE", twins, "2026-01-08T10:00+07:00");
    UUID y = product("Cáp sạc Y", null, "99000", "ACTIVE", twins, "2026-01-08T10:00+07:00");
    assertThat(search(sorted(null, twins, SearchSort.PRICE_ASC), MatchMode.STRICT).getContent())
        .extracting(ProductSearchResponse::id)
        .isEqualTo(Stream.of(x, y).sorted(Comparator.comparing(UUID::toString)).toList());
  }

  // -----------------------------------------------------------------
  // Phân trang + tổng
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "Tổng đúng ở mọi trang — kể cả trang rỗng sau trang cuối (đếm bằng câu riêng), có và không"
          + " có từ khoá")
  void everyPageReportsTheSameTotal() {
    ProductSearchCriteria browse = query(null, accessories);
    ProductSearchCriteria iphone = query("iphone", accessories);

    // (số sản phẩm của trang, tổng) — trang 2 phần tử
    assertThat(sizeAndTotal(browse, 0)).isEqualTo(tuple(2, 5L));
    assertThat(sizeAndTotal(browse, 2)).isEqualTo(tuple(1, 5L));
    assertThat(sizeAndTotal(browse, 4)).isEqualTo(tuple(0, 5L));
    assertThat(sizeAndTotal(iphone, 0)).isEqualTo(tuple(2, 3L));
    assertThat(sizeAndTotal(iphone, 1)).isEqualTo(tuple(1, 3L));
    assertThat(sizeAndTotal(iphone, 5)).isEqualTo(tuple(0, 3L));
  }

  private Tuple sizeAndTotal(ProductSearchCriteria criteria, int pageNumber) {
    Page<ProductSearchResponse> page =
        repository.searchProducts(criteria, MatchMode.STRICT, PageRequest.of(pageNumber, 2));
    return tuple(page.getNumberOfElements(), page.getTotalElements());
  }

  @Test
  @DisplayName("Không điều kiện nào — tổng = mọi sản phẩm trong bảng, mới nhất trước")
  void noCriteriaCountsEveryProduct() {
    Page<ProductSearchResponse> page =
        search(new ProductSearchCriteria(null, null, null, null, null, null), MatchMode.STRICT);

    assertThat(page.getTotalElements())
        .isEqualTo(jdbc.sql("SELECT count(*) FROM app.products").query(Long.class).single());
    assertThat(page.getContent())
        .extracting(ProductSearchResponse::createdAt)
        .isSortedAccordingTo((x, y) -> y.compareTo(x));
  }

  // -----------------------------------------------------------------
  // Câu được dựng
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "Câu chỉ chứa điều kiện có giá trị; có từ khoá thì thêm điểm, tổng (window) và ngưỡng —"
          + " đúng nguyên văn")
  void pageStatementContainsOnlyThePresentConditions() {
    SqlStatement scored =
        repository.pageStatement(
            sorted("op lung", accessories, SearchSort.RELEVANCE), MatchMode.STRICT, FIRST_PAGE);
    assertThat(scored.sql())
        .isEqualTo(
            """
            SELECT p.id, p.name, p.slug, p.sku, p.price, p.status, p.category_id, p.created_at,
                   round((CASE WHEN p.sku = upper(:q) THEN 1
                               ELSE word_similarity(app.search_normalize(:q),
                                                    app.search_normalize(p.name))
                          END)::numeric, 2) AS score,
                   count(*) OVER () AS total_count
            FROM app.products p
            WHERE (app.search_normalize(p.name) %> app.search_normalize(:q) OR p.sku = upper(:q))
              AND p.category_id = :categoryId
            ORDER BY score DESC, p.created_at DESC, p.id
            LIMIT :limit OFFSET :offset""");
    assertThat(scored.params())
        .isEqualTo(Map.of("q", "op lung", "categoryId", accessories, "limit", 20, "offset", 0L));
    assertThat(scored.settings())
        .isEqualTo(
            Map.of(
                "pg_trgm.word_similarity_threshold",
                "0.6",
                "plan_cache_mode",
                "force_custom_plan"));

    SqlStatement plain =
        repository.pageStatement(
            new ProductSearchCriteria(
                null,
                null,
                new BigDecimal("100000"),
                new BigDecimal("900000"),
                ProductStatus.ACTIVE,
                SearchSort.PRICE_ASC),
            MatchMode.RELAXED,
            PageRequest.of(1, 10));
    assertThat(plain.sql())
        .isEqualTo(
            """
            SELECT p.id, p.name, p.slug, p.sku, p.price, p.status, p.category_id, p.created_at,
                   NULL::numeric AS score
            FROM app.products p
            WHERE p.price >= :priceMin
              AND p.price <= :priceMax
              AND p.status = :status
            ORDER BY p.price, p.created_at DESC, p.id
            LIMIT :limit OFFSET :offset""");
    assertThat(plain.params())
        .isEqualTo(
            Map.of(
                "priceMin",
                new BigDecimal("100000"),
                "priceMax",
                new BigDecimal("900000"),
                "status",
                "ACTIVE",
                "limit",
                10,
                "offset",
                10L));
    assertThat(plain.settings()).isEmpty();
  }

  // -----------------------------------------------------------------
  // Plan + schema
  // -----------------------------------------------------------------

  @Test
  @DisplayName(
      "AC-06: câu tìm thật (pageStatement) dùng được idx_products_name_trgm và products_sku_unique"
          + " — BitmapOr; điều kiện tên chỉ tính một lần mỗi row (không có Filter)")
  void searchStatementUsesTheTrigramAndSkuIndexes() {
    SqlStatement statement =
        repository.pageStatement(query("iphone", null), MatchMode.STRICT, FIRST_PAGE);
    onlyBitmapScans();

    assertThat(
            jdbc.sql("EXPLAIN (COSTS OFF) " + statement.sql())
                .params(statement.params())
                .query(String.class)
                .list())
        .anyMatch(line -> line.contains("BitmapOr"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_products_name_trgm"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on products_sku_unique"))
        // Điều kiện chỉ ở Recheck Cond: có thêm Filter là mỗi row tính word_similarity hai lần
        .noneMatch(line -> line.contains("Filter:"));
    assertThat(statement.settings())
        .containsOnly(
            Map.entry("pg_trgm.word_similarity_threshold", "0.6"),
            Map.entry("plan_cache_mode", "force_custom_plan"));
  }

  @Test
  @DisplayName(
      "Plan generic (tham số chưa có giá trị) vẫn dùng index — câu chỉ chứa điều kiện có giá trị,"
          + " không có ':x IS NULL OR ...'")
  void genericPlanKeepsTheIndexes() {
    onlyBitmapScans();

    assertThat(genericPlan(query("iphone", null)))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_products_name_trgm"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on products_sku_unique"))
        .noneMatch(line -> line.contains("Seq Scan"));
    assertThat(genericPlan(query(null, accessories)))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_products_category_id"))
        .noneMatch(line -> line.contains("Seq Scan"));
  }

  @Test
  @DisplayName(
      "Câu tìm có từ khoá giữ plan custom khi chạy lặp trên một connection (plan_cache_mode ="
          + " force_custom_plan) — có thống kê mà không có nó, Postgres chuyển sang plan generic")
  void searchKeepsCustomPlansOnAReusedConnection() {
    // 400 / 600 sản phẩm khớp "iphone": ước lượng của plan custom (có thống kê) cao hơn hẳn 1% mặc
    // định của plan generic — tình huống làm Postgres coi plan generic là rẻ hơn (bài 06)
    UUID many = category("Nhiều iPhone (test search)", "srch-many");
    jdbc.sql(
            """
            INSERT INTO app.products (name, slug, price, status, category_id)
            SELECT CASE WHEN i % 3 = 0 THEN 'Sạc nhanh ' ELSE 'Ốp lưng iPhone ' END || i,
                   'srch-many-' || i, 100000, 'ACTIVE', :category
            FROM generate_series(1, 600) i
            """)
        .param("category", many)
        .update();
    jdbc.sql("ANALYZE app.products").update();
    // PRICE_DESC + từ khoá + category: câu riêng của test này — số đếm của pg_prepared_statements
    // không lẫn câu của test khác
    ProductSearchCriteria iphone = sorted("iphone", many, SearchSort.PRICE_DESC);

    // pgjdbc chuyển câu sang prepared statement của server từ lần thứ 5; Postgres lập plan custom 5
    // lần rồi mới cân nhắc plan generic — 12 lần là đủ để thấy nó chọn gì
    for (int i = 0; i < 12; i++) {
      assertThat(search(iphone, MatchMode.STRICT).getTotalElements()).isEqualTo(400);
    }

    assertThat(
            jdbc.sql(
                    """
                    SELECT generic_plans, custom_plans FROM pg_prepared_statements
                    WHERE NOT from_sql AND statement LIKE '%word_similarity%'
                      AND statement LIKE '%ORDER BY p.price DESC%'
                    """)
                .query()
                .singleRow())
        .containsEntry("generic_plans", 0L)
        .containsEntry("custom_plans", 8L);
  }

  /**
   * Chỉ chừa bitmap scan — đường duy nhất còn lại để lọc bằng index. Bảng vài row thì planner chọn
   * quét cả bảng, hoặc đi hết {@code idx_products_created_at_desc} cho khỏi sắp xếp: tắt cả hai để
   * hỏi "index có dùng được cho điều kiện này không", không phải "planner có chọn nó không" (trên
   * 100.000 sản phẩm planner tự chọn bitmap — bài 02).
   */
  private void onlyBitmapScans() {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    jdbc.sql("SET LOCAL enable_indexscan = off").update();
  }

  /**
   * Plan generic của câu: {@code PREPARE} với {@code $n}, {@code EXPLAIN EXECUTE} dưới {@code
   * plan_cache_mode = force_generic_plan}. {@code EXPLAIN (GENERIC_PLAN)} không gửi được qua
   * pgjdbc: extended protocol coi {@code $1} là tham số bind không có giá trị ({@code 08P01}).
   */
  private List<String> genericPlan(ProductSearchCriteria criteria) {
    SqlStatement statement = repository.pageStatement(criteria, MatchMode.STRICT, FIRST_PAGE);
    List<String> order = new ArrayList<>();
    String sql = positional(statement.sql(), order);
    String arguments =
        order.stream()
            .map(name -> literal(statement.params().get(name)))
            .collect(Collectors.joining(", "));
    String name = "generic_search_" + System.nanoTime();

    jdbc.sql("SET LOCAL plan_cache_mode = force_generic_plan").update();
    jdbc.sql("PREPARE " + name + " AS " + sql).update();
    List<String> plan =
        jdbc.sql("EXPLAIN (COSTS OFF) EXECUTE " + name + "(" + arguments + ")")
            .query(String.class)
            .list();
    jdbc.sql("DEALLOCATE " + name).update();
    return plan;
  }

  /** {@code :name} → {@code $n} theo thứ tự xuất hiện; {@code order} nhận tên theo thứ tự đó. */
  private static String positional(String sql, List<String> order) {
    Matcher matcher = NAMED_PARAMETER.matcher(sql);
    StringBuilder positional = new StringBuilder();
    while (matcher.find()) {
      if (!order.contains(matcher.group(1))) {
        order.add(matcher.group(1));
      }
      matcher.appendReplacement(positional, "\\$" + (order.indexOf(matcher.group(1)) + 1));
    }
    matcher.appendTail(positional);
    return positional.toString();
  }

  private static String literal(Object value) {
    return value instanceof Number
        ? value.toString()
        : "'" + value.toString().replace("'", "''") + "'";
  }

  @Test
  @DisplayName("ProductStatus khớp miền giá trị ck_products_status (V5) — thêm ở một bên là đỏ")
  void productStatusMatchesCheckConstraint() {
    String definition =
        jdbc.sql(
                """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_products_status' AND connamespace = 'app'::regnamespace
                """)
            .query(String.class)
            .single();

    Set<String> checkValues =
        QUOTED_VALUE.matcher(definition).results().map(m -> m.group(1)).collect(Collectors.toSet());
    assertThat(checkValues)
        .isEqualTo(
            Arrays.stream(ProductStatus.values()).map(Enum::name).collect(Collectors.toSet()));
  }
}
