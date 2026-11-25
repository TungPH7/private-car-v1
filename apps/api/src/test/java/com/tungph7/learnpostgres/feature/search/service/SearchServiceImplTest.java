package com.tungph7.learnpostgres.feature.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;
import com.tungph7.learnpostgres.feature.search.repository.SearchQueryRepository;

/**
 * Unit test cho {@link SearchServiceImpl}: đầu vào sai bị chặn (400) trước khi chạm DB, và chính
 * sách "ngưỡng chuẩn trước, nới khi không ra gì".
 *
 * <p>Những thứ mock không chứng minh được — transaction READ ONLY thật, ngưỡng đặt bằng {@code
 * set_config} sống qua cả câu trang lẫn câu đếm — ở {@code SearchServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Mock SearchQueryRepository repository;
  @InjectMocks SearchServiceImpl service;

  private static ProductSearchCriteria query(String q) {
    return new ProductSearchCriteria(q, null, null, null, null, null);
  }

  private static ProductSearchCriteria prices(BigDecimal min, BigDecimal max) {
    return new ProductSearchCriteria(null, null, min, max, null, null);
  }

  /**
   * Một trang có {@code total} sản phẩm khớp — {@code content} rỗng khi trang nằm sau trang cuối.
   */
  private static Page<ProductSearchResponse> page(
      Pageable pageable, long total, ProductSearchResponse... content) {
    return new PageImpl<>(List.of(content), pageable, total);
  }

  private static ProductSearchResponse product(String name) {
    return new ProductSearchResponse(
        UUID.randomUUID(),
        name,
        "slug",
        null,
        new BigDecimal("150000.00"),
        ProductStatus.ACTIVE,
        null,
        Instant.parse("2026-01-06T03:00:00Z"),
        new BigDecimal("0.50"));
  }

  private static void assertInvalid(Throwable thrown, String messagePart) {
    assertThat(thrown).isInstanceOf(BadRequestException.class).hasMessageContaining(messagePart);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  // ================================================================
  // Ngưỡng chuẩn trước, nới khi không ra gì
  // ================================================================

  @Test
  @DisplayName("ngưỡng chuẩn có kết quả → trả luôn, không thử ngưỡng nới")
  void strictResultsAreFinal() {
    ProductSearchCriteria iphone = query("iphone");
    Page<ProductSearchResponse> strict = page(FIRST_PAGE, 1, product("Ốp lưng iPhone 15"));
    when(repository.searchProducts(iphone, MatchMode.STRICT, FIRST_PAGE)).thenReturn(strict);

    ProductSearchPageResponse result = service.searchProducts(iphone, FIRST_PAGE);

    assertThat(result.matchMode()).isEqualTo(MatchMode.STRICT);
    assertThat(result.content()).isEqualTo(strict.getContent());
    assertThat(result.page().totalElements()).isEqualTo(1);
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName("ngưỡng chuẩn không ra sản phẩm nào → tìm lại ở ngưỡng nới, kết quả ghi RELAXED")
  void noStrictMatchFallsBackToRelaxed() {
    ProductSearchCriteria iphne = query("iphne");
    Page<ProductSearchResponse> relaxed = page(FIRST_PAGE, 1, product("Ốp lưng iPhone 15"));
    when(repository.searchProducts(iphne, MatchMode.STRICT, FIRST_PAGE))
        .thenReturn(page(FIRST_PAGE, 0));
    when(repository.searchProducts(iphne, MatchMode.RELAXED, FIRST_PAGE)).thenReturn(relaxed);

    ProductSearchPageResponse result = service.searchProducts(iphne, FIRST_PAGE);

    assertThat(result.matchMode()).isEqualTo(MatchMode.RELAXED);
    assertThat(result.content()).isEqualTo(relaxed.getContent());
    InOrder order = inOrder(repository);
    order.verify(repository).searchProducts(iphne, MatchMode.STRICT, FIRST_PAGE);
    order.verify(repository).searchProducts(iphne, MatchMode.RELAXED, FIRST_PAGE);
  }

  @Test
  @DisplayName(
      "trang sau trang cuối của kết quả chuẩn (rỗng, tổng > 0) → vẫn là kết quả chuẩn, không nới")
  void emptyPageAfterTheLastStrictPageIsNotAFallback() {
    ProductSearchCriteria iphone = query("iphone");
    PageRequest farAway = PageRequest.of(50, 20);
    when(repository.searchProducts(iphone, MatchMode.STRICT, farAway)).thenReturn(page(farAway, 3));

    ProductSearchPageResponse result = service.searchProducts(iphone, farAway);

    assertThat(result.matchMode()).isEqualTo(MatchMode.STRICT);
    assertThat(result.content()).isEmpty();
    assertThat(result.page().totalElements()).isEqualTo(3);
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName("không có từ khoá → một lần tìm, không so tên: matchMode null")
  void noQueryMeansNoMatchMode() {
    ProductSearchCriteria browse = query("   ");
    when(repository.searchProducts(eq(browse), any(), eq(FIRST_PAGE)))
        .thenReturn(page(FIRST_PAGE, 0));

    ProductSearchPageResponse result = service.searchProducts(browse, FIRST_PAGE);

    assertThat(result.matchMode()).isNull();
    verify(repository).searchProducts(browse, MatchMode.STRICT, FIRST_PAGE);
    verifyNoMoreInteractions(repository);
  }

  @Test
  @DisplayName(
      "pageStatement: kiểm đầu vào như tìm thật, rồi trả đúng câu của repository ở mức được hỏi")
  void pageStatementIsTheRepositoryStatementAfterTheSameChecks() {
    ProductSearchCriteria iphne = query("iphne");
    SqlStatement statement = new SqlStatement("SELECT 1", Map.of(), Map.of());
    when(repository.pageStatement(iphne, MatchMode.RELAXED, FIRST_PAGE)).thenReturn(statement);

    assertThat(service.pageStatement(iphne, MatchMode.RELAXED, FIRST_PAGE)).isSameAs(statement);
    assertInvalid(
        catchThrowable(() -> service.pageStatement(query("a"), MatchMode.STRICT, FIRST_PAGE)),
        "Từ khoá phải dài 2 – 80 ký tự");
    assertThatNullPointerException()
        .isThrownBy(() -> service.pageStatement(iphne, null, FIRST_PAGE));
    verifyNoMoreInteractions(repository);
  }

  // ================================================================
  // Kiểm đầu vào — sai → 400, không chạm DB
  // ================================================================

  @Test
  @DisplayName("từ khoá 1 ký tự (sau khi bỏ khoảng trắng) hoặc dài hơn 80 → 400; 2 và 80 → được")
  void queryLength() {
    assertInvalid(catchThrowable(() -> service.searchProducts(query("a"), FIRST_PAGE)), "nhận 1");
    assertInvalid(
        catchThrowable(() -> service.searchProducts(query("  a  "), FIRST_PAGE)), "nhận 1");
    assertInvalid(
        catchThrowable(() -> service.searchProducts(query("x".repeat(81)), FIRST_PAGE)), "nhận 81");
    verifyNoInteractions(repository);

    ProductSearchCriteria two = query("ab");
    ProductSearchCriteria eighty = query("x".repeat(SearchService.MAX_QUERY_LENGTH));
    when(repository.searchProducts(any(), eq(MatchMode.STRICT), eq(FIRST_PAGE)))
        .thenReturn(page(FIRST_PAGE, 1, product("Ab")));
    service.searchProducts(two, FIRST_PAGE);
    service.searchProducts(eighty, FIRST_PAGE);

    verify(repository).searchProducts(two, MatchMode.STRICT, FIRST_PAGE);
    verify(repository).searchProducts(eighty, MatchMode.STRICT, FIRST_PAGE);
  }

  @Test
  @DisplayName("giá âm → 400; giá từ lớn hơn giá tới → 400; bằng nhau → được")
  void priceRange() {
    assertInvalid(
        catchThrowable(
            () -> service.searchProducts(prices(new BigDecimal("-1"), null), FIRST_PAGE)),
        "priceMin không được âm");
    assertInvalid(
        catchThrowable(
            () -> service.searchProducts(prices(null, new BigDecimal("-0.01")), FIRST_PAGE)),
        "priceMax không được âm");
    assertInvalid(
        catchThrowable(
            () ->
                service.searchProducts(
                    prices(new BigDecimal("500000"), new BigDecimal("499999.99")), FIRST_PAGE)),
        "priceMin (500000) phải nhỏ hơn hoặc bằng priceMax (499999.99)");
    verifyNoInteractions(repository);

    ProductSearchCriteria same = prices(new BigDecimal("150000"), new BigDecimal("150000.00"));
    when(repository.searchProducts(same, MatchMode.STRICT, FIRST_PAGE))
        .thenReturn(page(FIRST_PAGE, 0));
    service.searchProducts(same, FIRST_PAGE);

    verify(repository).searchProducts(same, MatchMode.STRICT, FIRST_PAGE);
  }

  /**
   * Chữ số đếm như {@code @Digits} và như cột {@code numeric(12,2)}. {@code 1E+999999} không chặn
   * thì pgjdbc gửi thành 0 — "giá từ rất lớn" thành "mọi giá"; {@code 1E-20000} thì Postgres từ
   * chối tham số ({@code 22P03}) → 409.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"10000000000", "0.001", "100.500", "1E+999999", "1E-20000"})
  @DisplayName("giá quá 10 chữ số phần nguyên hoặc quá 2 chữ số lẻ → 400")
  void priceDigits(String price) {
    assertInvalid(
        catchThrowable(
            () -> service.searchProducts(prices(new BigDecimal(price), null), FIRST_PAGE)),
        "priceMin tối đa 10 chữ số phần nguyên và 2 chữ số lẻ");
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("giá đúng mép của numeric(12,2) — 9999999999.99, 0.00, 1E+3 → được")
  void priceDigitsAtTheEdge() {
    ProductSearchCriteria edge = prices(new BigDecimal("0.00"), new BigDecimal("9999999999.99"));
    ProductSearchCriteria scientific = prices(new BigDecimal("1E+3"), null);
    when(repository.searchProducts(any(), eq(MatchMode.STRICT), eq(FIRST_PAGE)))
        .thenReturn(page(FIRST_PAGE, 0));

    service.searchProducts(edge, FIRST_PAGE);
    service.searchProducts(scientific, FIRST_PAGE);

    verify(repository).searchProducts(edge, MatchMode.STRICT, FIRST_PAGE);
    verify(repository).searchProducts(scientific, MatchMode.STRICT, FIRST_PAGE);
  }

  @Test
  @DisplayName("không phân trang hoặc trang quá 100 sản phẩm → 400; 100 → được")
  void pageSize() {
    ProductSearchCriteria browse = query(null);
    assertInvalid(
        catchThrowable(() -> service.searchProducts(browse, Pageable.unpaged())),
        "mỗi trang 1 – 100 sản phẩm");
    assertInvalid(
        catchThrowable(() -> service.searchProducts(browse, PageRequest.of(0, 101))),
        "mỗi trang 1 – 100 sản phẩm");
    verifyNoInteractions(repository);

    PageRequest hundred = PageRequest.of(3, SearchService.MAX_PAGE_SIZE);
    when(repository.searchProducts(browse, MatchMode.STRICT, hundred)).thenReturn(page(hundred, 0));
    service.searchProducts(browse, hundred);

    verify(repository).searchProducts(browse, MatchMode.STRICT, hundred);
  }
}
