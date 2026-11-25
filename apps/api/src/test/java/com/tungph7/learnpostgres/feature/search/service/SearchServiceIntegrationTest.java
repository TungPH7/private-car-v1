package com.tungph7.learnpostgres.feature.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;

/**
 * {@link SearchService} trên Postgres thật — những thứ unit test với mock không chứng minh được:
 *
 * <ul>
 *   <li>Mọi câu của một lần tìm (đặt ngưỡng, trang, đếm, lần nới) chạy trong transaction {@code
 *       READ ONLY} của Postgres.
 *   <li>Ngưỡng đặt bằng {@code set_config(..., true)} có tác dụng thật: "iphne" không khớp ở ngưỡng
 *       chuẩn, khớp ở ngưỡng nới (AC-02) — và câu đếm của trang sau trang cuối chạy cùng ngưỡng với
 *       câu trang.
 * </ul>
 *
 * <p>Không {@code @Transactional} trên class: mỗi lời gọi service là một transaction thật, như một
 * request. Dữ liệu commit trong {@code TransactionTemplate}, khoanh trong một category riêng, tự
 * dọn.
 */
@SpringBootTest
@Tag("integration")
class SearchServiceIntegrationTest extends AbstractContainerBaseTest {

  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Autowired SearchService searchService;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  /** Repository đi qua bean này — spy để chen một câu {@code SHOW} vào trước mỗi câu SQL. */
  @MockitoSpyBean JdbcClient jdbcClient;

  private UUID category;

  @BeforeEach
  void seed() {
    cleanup();
    tx.executeWithoutResult(
        status -> {
          category =
              jdbc.queryForObject(
                  "INSERT INTO app.categories (name, slug) VALUES ('Search service test',"
                      + " 'srch-svc') RETURNING id",
                  UUID.class);
          product("Ốp lưng iPhone 15 Pro Trong suốt", "SRCH-SVC-01");
          product("Ốp lưng iPhone 15 Pro Max Silicon Đen", "SRCH-SVC-02");
          product("Iphone Case Leather", null);
          product("Tai nghe AirPods Pro 2 Trắng", "SRCH-SVC-04");
        });
  }

  private void product(String name, String sku) {
    jdbc.update(
        "INSERT INTO app.products (name, slug, sku, price, status, category_id)"
            + " VALUES (?, 'srch-svc-' || gen_random_uuid(), ?, 150000, 'ACTIVE', ?)",
        name,
        sku,
        category);
  }

  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM app.products WHERE slug LIKE 'srch-svc-%'");
          jdbc.update("DELETE FROM app.categories WHERE slug = 'srch-svc'");
        });
  }

  private ProductSearchCriteria query(String q) {
    return new ProductSearchCriteria(q, category, null, null, null, null);
  }

  private static List<String> names(ProductSearchPageResponse page) {
    return page.content().stream().map(ProductSearchResponse::name).toList();
  }

  /**
   * Trước mỗi câu SQL repository gửi qua {@link JdbcClient}, ghi lại {@code transaction_read_only}
   * của chính transaction đó — {@link JdbcTemplate} dùng chung connection đang gắn với transaction.
   */
  private List<String> recordTransactionReadOnly() {
    List<String> seen = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              seen.add(jdbc.queryForObject("SHOW transaction_read_only", String.class));
              return invocation.callRealMethod();
            })
        .when(jdbcClient)
        .sql(anyString());
    return seen;
  }

  @Test
  @DisplayName(
      "AC-02: 'iphne' không khớp ở ngưỡng chuẩn → tìm lại ở ngưỡng nới, ra 3 sản phẩm iPhone,"
          + " kết quả ghi RELAXED")
  void typoFallsBackToTheRelaxedThreshold() {
    ProductSearchPageResponse page = searchService.searchProducts(query("iphne"), FIRST_PAGE);

    assertThat(page.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(names(page))
        .containsExactlyInAnyOrder(
            "Ốp lưng iPhone 15 Pro Trong suốt",
            "Ốp lưng iPhone 15 Pro Max Silicon Đen",
            "Iphone Case Leather");
  }

  @Test
  @DisplayName("ngưỡng chuẩn có kết quả → STRICT; không gì khớp ở cả hai ngưỡng → RELAXED, rỗng")
  void strictWinsAndNothingStaysNothing() {
    ProductSearchPageResponse iphone = searchService.searchProducts(query("iphone"), FIRST_PAGE);
    ProductSearchPageResponse nothing = searchService.searchProducts(query("xyzabc"), FIRST_PAGE);

    assertThat(iphone.matchMode()).isEqualTo(MatchMode.STRICT);
    assertThat(iphone.page().totalElements()).isEqualTo(3);
    assertThat(nothing.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(nothing.content()).isEmpty();
    assertThat(nothing.page().totalElements()).isZero();
  }

  @Test
  @DisplayName(
      "trang sau trang cuối: tổng đếm ở đúng ngưỡng của câu trang — 'iphone' giữ STRICT (3),"
          + " 'iphne' nới rồi đếm ở RELAXED (3)")
  void pageAfterTheLastPageCountsAtTheSameThreshold() {
    PageRequest farAway = PageRequest.of(5, 2);

    ProductSearchPageResponse iphone = searchService.searchProducts(query("iphone"), farAway);
    ProductSearchPageResponse iphne = searchService.searchProducts(query("iphne"), farAway);

    assertThat(iphone.content()).isEmpty();
    assertThat(iphone.matchMode()).isEqualTo(MatchMode.STRICT);
    assertThat(iphone.page().totalElements()).isEqualTo(3);
    assertThat(iphne.content()).isEmpty();
    assertThat(iphne.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(iphne.page().totalElements()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "mọi câu của một lần tìm — đặt ngưỡng, trang, lần nới, câu đếm — chạy trong transaction READ"
          + " ONLY của Postgres")
  void everyStatementRunsInReadOnlyTransaction() {
    List<String> readOnly = recordTransactionReadOnly();

    searchService.searchProducts(query("iphne"), PageRequest.of(5, 2));
    searchService.searchProducts(query(null), FIRST_PAGE);

    // iphne trang 6: 2 setting (ngưỡng, plan_cache_mode) + trang + đếm ở STRICT, rồi cũng vậy ở
    // RELAXED; duyệt: 1
    assertThat(readOnly).hasSize(9).containsOnly("on");
  }
}
