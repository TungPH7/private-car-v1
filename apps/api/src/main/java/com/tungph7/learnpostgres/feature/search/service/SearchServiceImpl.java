package com.tungph7.learnpostgres.feature.search.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;
import com.tungph7.learnpostgres.feature.search.repository.SearchQueryRepository;

/**
 * Cài đặt {@link SearchService}: kiểm đầu vào, rồi tìm ở ngưỡng chuẩn và — chỉ khi không ra gì — ở
 * ngưỡng nới.
 *
 * <p><b>Transaction.</b> {@link #searchProducts} PHẢI có transaction: ngưỡng so tên là tham số cấu
 * hình đặt bằng {@code set_config(..., true)} — sống tới hết transaction. Không có transaction thì
 * repository ném {@code IllegalStateException} thay vì để câu tìm lặng lẽ chạy với ngưỡng mặc định.
 * {@code readOnly = true}: Spring đánh dấu connection read-only, pgjdbc mở transaction {@code READ
 * ONLY} của Postgres. {@link #pageStatement} không chạm DB nên không mở transaction nào.
 */
@Service
public class SearchServiceImpl implements SearchService {

  private final SearchQueryRepository repository;

  public SearchServiceImpl(SearchQueryRepository repository) {
    this.repository = repository;
  }

  /**
   * Tổng của trang lấy từ repository: có từ khoá thì từ window của câu trang, hoặc — trang rỗng nằm
   * sau trang cuối — từ câu đếm ở cùng ngưỡng. Vì vậy "không sản phẩm nào khớp ở ngưỡng chuẩn" là
   * {@code totalElements == 0}, đúng ở mọi trang.
   */
  @Override
  @Transactional(readOnly = true)
  public ProductSearchPageResponse searchProducts(
      ProductSearchCriteria criteria, Pageable pageable) {
    requireValid(criteria, pageable);
    Page<ProductSearchResponse> strict =
        repository.searchProducts(criteria, MatchMode.STRICT, pageable);
    if (criteria.q() == null) {
      return ProductSearchPageResponse.of(strict, null);
    }
    if (strict.getTotalElements() > 0) {
      return ProductSearchPageResponse.of(strict, MatchMode.STRICT);
    }
    return ProductSearchPageResponse.of(
        repository.searchProducts(criteria, MatchMode.RELAXED, pageable), MatchMode.RELAXED);
  }

  @Override
  public SqlStatement pageStatement(
      ProductSearchCriteria criteria, MatchMode mode, Pageable pageable) {
    Objects.requireNonNull(mode, "mode");
    requireValid(criteria, pageable);
    return repository.pageStatement(criteria, mode, pageable);
  }

  // ================================================================
  // Kiểm đầu vào — sai → 400 VALIDATION_FAILED
  // ================================================================

  private static void requireValid(ProductSearchCriteria criteria, Pageable pageable) {
    requireQuery(criteria.q());
    requirePrice("priceMin", criteria.priceMin());
    requirePrice("priceMax", criteria.priceMax());
    if (criteria.priceMin() != null
        && criteria.priceMax() != null
        && criteria.priceMin().compareTo(criteria.priceMax()) > 0) {
      throw invalid(
          "priceMin (%s) phải nhỏ hơn hoặc bằng priceMax (%s).",
          criteria.priceMin(), criteria.priceMax());
    }
    if (pageable.isUnpaged() || pageable.getPageSize() > MAX_PAGE_SIZE) {
      throw invalid("Phải phân trang, mỗi trang 1 – %d sản phẩm.", MAX_PAGE_SIZE);
    }
  }

  /**
   * {@code q} đã bỏ khoảng trắng hai đầu ({@link ProductSearchCriteria}); {@code null} = không có.
   */
  private static void requireQuery(String q) {
    if (q != null && (q.length() < MIN_QUERY_LENGTH || q.length() > MAX_QUERY_LENGTH)) {
      throw invalid(
          "Từ khoá phải dài %d – %d ký tự (không tính khoảng trắng hai đầu), nhận %d.",
          MIN_QUERY_LENGTH, MAX_QUERY_LENGTH, q.length());
    }
  }

  /**
   * Đếm chữ số như {@code @Digits} của Bean Validation — phần nguyên = {@code precision - scale},
   * phần lẻ = {@code scale} như client gửi — để controller và service chặn đúng cùng những giá trị.
   */
  private static void requirePrice(String name, BigDecimal price) {
    if (price == null) {
      return;
    }
    if (price.signum() < 0) {
      throw invalid("%s không được âm, nhận %s.", name, price);
    }
    if (price.precision() - price.scale() > MAX_PRICE_INTEGER_DIGITS
        || price.scale() > MAX_PRICE_FRACTION_DIGITS) {
      throw invalid(
          "%s tối đa %d chữ số phần nguyên và %d chữ số lẻ, nhận %s.",
          name, MAX_PRICE_INTEGER_DIGITS, MAX_PRICE_FRACTION_DIGITS, price);
    }
  }

  private static BadRequestException invalid(String format, Object... args) {
    return new BadRequestException(ErrorCode.VALIDATION_FAILED, format.formatted(args));
  }
}
