package com.tungph7.learnpostgres.feature.coupon.domain;

/**
 * Loại coupon — quyết định ý nghĩa của {@code Coupon.value}.
 *
 * <p>Miền hợp lệ của {@code value} theo từng loại được DB enforce bằng CHECK constraint {@code
 * ck_coupons_value} (V10), không chỉ bằng Bean Validation ở app.
 */
public enum CouponType {

  /** Giảm theo phần trăm. {@code value} thuộc (0, 100]. Có thể bị chặn trần bởi maxDiscount. */
  PERCENT,

  /** Giảm số tiền cố định. {@code value} > 0, đơn vị VND. */
  AMOUNT,

  /** Miễn phí ship. {@code value} = 0 — giảm giá hàng là 0, phần ship xử lý ở F05. */
  FREE_SHIPPING
}
