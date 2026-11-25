package com.tungph7.learnpostgres.feature.search.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một lần tìm sản phẩm qua HTTP: đúng các tham số của {@code GET /api/v1/search/products} — cùng
 * tên, cùng giới hạn, cùng mặc định, cùng mô tả. {@code SearchController} gom query param vào đây;
 * EXPLAIN và benchmark của admin (F08) nhận nó làm body JSON, nên admin dán nguyên tham số của một
 * lần tìm thật vào là giải thích / đo đúng lần tìm đó.
 *
 * <p>Bean Validation chỉ cho những luật annotation diễn đạt đúng — giá, trang — bằng hằng số của
 * {@link SearchService}. Độ dài từ khoá thì không: service đếm sau khi bỏ khoảng trắng hai đầu,
 * {@code @Size} đếm cả khoảng trắng — nên chỉ service kiểm, cùng với {@code priceMin <= priceMax}
 * (sai → 400 không có {@code errors[]}); OpenAPI vẫn ghi {@code maxLength}.
 *
 * <p>{@code minimum = "0"} của giá khai tường minh: swagger-core không đổi {@code @PositiveOrZero}
 * trên field thành {@code minimum} (springdoc thì đổi được cho query param của GET) — thiếu nó body
 * mô tả lệch GET, {@code SearchOpenApiIntegrationTest} đỏ.
 *
 * @param page số trang, tính từ 0; {@code null} = 0
 * @param size số sản phẩm mỗi trang; {@code null} = {@link #DEFAULT_PAGE_SIZE}
 */
public record ProductSearchRequest(
    @Schema(description = Q_DESCRIPTION, maxLength = SearchService.MAX_QUERY_LENGTH) String q,
    @Schema(description = CATEGORY_DESCRIPTION) UUID categoryId,
    @Schema(description = PRICE_MIN_DESCRIPTION, minimum = "0")
        @PositiveOrZero
        @Digits(
            integer = SearchService.MAX_PRICE_INTEGER_DIGITS,
            fraction = SearchService.MAX_PRICE_FRACTION_DIGITS)
        BigDecimal priceMin,
    @Schema(description = PRICE_MAX_DESCRIPTION, minimum = "0")
        @PositiveOrZero
        @Digits(
            integer = SearchService.MAX_PRICE_INTEGER_DIGITS,
            fraction = SearchService.MAX_PRICE_FRACTION_DIGITS)
        BigDecimal priceMax,
    ProductStatus status,
    @Schema(description = SORT_DESCRIPTION, defaultValue = "NEWEST") SearchSort sort,
    @Schema(description = PAGE_DESCRIPTION, defaultValue = "0") @Min(0) Integer page,
    @Schema(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(SearchService.MAX_PAGE_SIZE)
        Integer size) {

  /** {@code size} khi client không gửi — như mọi endpoint phân trang của repo. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  // ---- Mô tả của OpenAPI — dùng chung cho query param của GET và field của body ----

  public static final String Q_DESCRIPTION =
      "Từ khoá — so với tên (không phân biệt dấu, hoa thường, thứ tự từ; chịu lỗi gõ nhẹ) và với"
          + " SKU (trùng cả mã). "
          + SearchService.MIN_QUERY_LENGTH
          + " – "
          + SearchService.MAX_QUERY_LENGTH
          + " ký tự, không tính khoảng trắng hai đầu; rỗng / không gửi = không có từ khoá";

  public static final String CATEGORY_DESCRIPTION =
      "Chỉ sản phẩm thuộc đúng category này (không tính category con)";

  private static final String PRICE_DIGITS =
      ". Không âm, tối đa "
          + SearchService.MAX_PRICE_INTEGER_DIGITS
          + " chữ số phần nguyên, "
          + SearchService.MAX_PRICE_FRACTION_DIGITS
          + " chữ số lẻ";

  public static final String PRICE_MIN_DESCRIPTION =
      "Giá từ (lấy cả mốc), không lớn hơn priceMax" + PRICE_DIGITS;

  public static final String PRICE_MAX_DESCRIPTION = "Giá tới (lấy cả mốc)" + PRICE_DIGITS;

  public static final String SORT_DESCRIPTION =
      "Hòa nhau thì sản phẩm mới hơn trước, rồi theo id. RELEVANCE không có từ khoá thì như NEWEST";

  public static final String PAGE_DESCRIPTION = "Số trang, tính từ 0";

  public ProductSearchRequest {
    page = page == null ? 0 : page;
    size = size == null ? DEFAULT_PAGE_SIZE : size;
  }

  /** Điều kiện tìm của service — {@code q} bỏ khoảng trắng hai đầu, {@code sort} mặc định ở đó. */
  public ProductSearchCriteria toCriteria() {
    return new ProductSearchCriteria(q, categoryId, priceMin, priceMax, status, sort);
  }

  /** Trang đã qua kiểm của Bean Validation — {@code page} âm / {@code size} < 1 thì ném lỗi. */
  public Pageable toPageable() {
    return PageRequest.of(page, size);
  }
}
