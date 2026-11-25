package com.tungph7.learnpostgres.feature.search.domain;

/**
 * Thứ tự của kết quả tìm sản phẩm. Hòa nhau thì sản phẩm mới hơn trước, rồi theo id — thứ tự luôn
 * xác định, nên phân trang không lặp hay sót sản phẩm.
 */
public enum SearchSort {
  /** Mới nhất trước — mặc định. */
  NEWEST,
  /** Giá tăng dần. */
  PRICE_ASC,
  /** Giá giảm dần. */
  PRICE_DESC,
  /** Giống từ khoá nhất trước ({@code score}). Không có từ khoá thì như {@link #NEWEST}. */
  RELEVANCE
}
