package com.tungph7.learnpostgres.feature.search.controller;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchRequest;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Tìm sản phẩm (F08) — {@code GET /api/v1/search/products}.
 *
 * <p>Không mapper: service trả thẳng {@link ProductSearchPageResponse} — repository dựng từng sản
 * phẩm từ result set, không có entity nào để đổi.
 *
 * <p>Tham số gom vào {@link ProductSearchRequest} — đúng record EXPLAIN / benchmark của admin nhận
 * làm body: cùng giới hạn, cùng mặc định, cùng mô tả OpenAPI, cùng một cách đổi sang điều kiện tìm
 * và trang.
 *
 * <p><b>Giới hạn: một hằng số, hai nơi kiểm</b> — như {@code DashboardController} (F07). Bean
 * Validation (giá, trang) cho OpenAPI ({@code minimum}, {@code maximum}) và cho {@code errors[]}
 * theo từng tham số; service kiểm lại, và kiểm một mình những gì annotation không diễn đạt đúng: độ
 * dài từ khoá tính sau khi bỏ khoảng trắng hai đầu, {@code priceMin <= priceMax} — sai thì 400
 * không có {@code errors[]}.
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Product search — pg_trgm, accent / case insensitive (F08)")
public class SearchController {

  /** {@code size} khi client không gửi — cùng mặc định với body của admin. */
  private static final String DEFAULT_PAGE_SIZE = "" + ProductSearchRequest.DEFAULT_PAGE_SIZE;

  /** Mô tả của 400, dựng từ chính hằng số service kiểm — không lệch được. */
  private static final String SEARCH_ERRORS =
      "VALIDATION_FAILED (q ngoài "
          + SearchService.MIN_QUERY_LENGTH
          + " – "
          + SearchService.MAX_QUERY_LENGTH
          + " ký tự sau khi bỏ khoảng trắng hai đầu, giá âm hoặc quá "
          + SearchService.MAX_PRICE_INTEGER_DIGITS
          + " chữ số phần nguyên / "
          + SearchService.MAX_PRICE_FRACTION_DIGITS
          + " chữ số lẻ, priceMin > priceMax, page < 0, size ngoài 1 – "
          + SearchService.MAX_PAGE_SIZE
          + "), INVALID_PARAMETER (categoryId không phải UUID, status / sort ngoài danh sách,"
          + " giá / page / size không phải số)";

  private final SearchService service;

  public SearchController(SearchService service) {
    this.service = service;
  }

  @GetMapping("/products")
  @Operation(
      summary = "Tìm sản phẩm",
      description =
          "Các điều kiện có giá trị phải đúng cùng lúc; không gửi điều kiện nào = mọi sản phẩm."
              + " Có từ khoá: so ở ngưỡng chuẩn trước, chỉ khi không sản phẩm nào khớp mới tìm lại"
              + " ở ngưỡng nới — matchMode = RELAXED: kết quả gần đúng.")
  @ApiResponse(
      responseCode = "200",
      description = "Một trang kết quả kèm tổng số sản phẩm khớp; trang sau trang cuối: content []")
  @ApiResponse(responseCode = "400", description = SEARCH_ERRORS)
  public ProductSearchPageResponse searchProducts(
      @Parameter(
              description = ProductSearchRequest.Q_DESCRIPTION,
              schema = @Schema(types = "string", maxLength = SearchService.MAX_QUERY_LENGTH))
          @RequestParam(required = false)
          String q,
      @Parameter(description = ProductSearchRequest.CATEGORY_DESCRIPTION)
          @RequestParam(required = false)
          UUID categoryId,
      @Parameter(description = ProductSearchRequest.PRICE_MIN_DESCRIPTION)
          @RequestParam(required = false)
          @PositiveOrZero
          @Digits(
              integer = SearchService.MAX_PRICE_INTEGER_DIGITS,
              fraction = SearchService.MAX_PRICE_FRACTION_DIGITS)
          BigDecimal priceMin,
      @Parameter(description = ProductSearchRequest.PRICE_MAX_DESCRIPTION)
          @RequestParam(required = false)
          @PositiveOrZero
          @Digits(
              integer = SearchService.MAX_PRICE_INTEGER_DIGITS,
              fraction = SearchService.MAX_PRICE_FRACTION_DIGITS)
          BigDecimal priceMax,
      @RequestParam(required = false) ProductStatus status,
      @Parameter(description = ProductSearchRequest.SORT_DESCRIPTION)
          @RequestParam(defaultValue = "NEWEST")
          SearchSort sort,
      @Parameter(description = ProductSearchRequest.PAGE_DESCRIPTION)
          @RequestParam(defaultValue = "0")
          @Min(0)
          int page,
      @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(SearchService.MAX_PAGE_SIZE)
          int size) {
    ProductSearchRequest request =
        new ProductSearchRequest(q, categoryId, priceMin, priceMax, status, sort, page, size);
    return service.searchProducts(request.toCriteria(), request.toPageable());
  }
}
