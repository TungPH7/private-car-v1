package com.tungph7.learnpostgres.feature.product.domain;

/**
 * Trạng thái của sản phẩm — đúng miền giá trị của {@code ck_products_status} (V5).
 *
 * <p>Entity {@link Product} (F01) chưa map cột {@code status}; enum này cho những chỗ đọc thẳng
 * bảng bằng SQL, như tìm sản phẩm (F08). Thêm giá trị ở đây thì phải thêm vào CHECK, và ngược lại —
 * test {@code SearchQueryRepositoryJdbcTest.productStatusMatchesCheckConstraint} so hai bên.
 */
public enum ProductStatus {
  /** Chưa bán — giá trị mặc định của cột. */
  DRAFT,
  /** Đang bán. */
  ACTIVE,
  /** Ngừng bán. */
  ARCHIVED
}
