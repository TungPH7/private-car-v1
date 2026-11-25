package com.tungph7.learnpostgres.feature.search.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;

/**
 * Điều kiện tìm sản phẩm. Field {@code null} = không lọc theo field đó; các điều kiện có giá trị
 * phải đúng cùng lúc.
 *
 * @param q từ khoá: so với tên — không phân biệt dấu, hoa thường, thứ tự từ, chịu lỗi gõ nhẹ — và
 *     với SKU (trùng cả mã, không phân biệt hoa thường). Bỏ khoảng trắng hai đầu; rỗng = không có
 *     từ khoá
 * @param categoryId chỉ sản phẩm thuộc đúng category này (không tính category con)
 * @param priceMin giá từ (lấy cả mốc)
 * @param priceMax giá tới (lấy cả mốc)
 * @param sort {@code null} = {@link SearchSort#NEWEST}
 */
public record ProductSearchCriteria(
    String q,
    UUID categoryId,
    BigDecimal priceMin,
    BigDecimal priceMax,
    ProductStatus status,
    SearchSort sort) {

  public ProductSearchCriteria {
    q = q == null || q.isBlank() ? null : q.strip();
    sort = sort == null ? SearchSort.NEWEST : sort;
  }
}
