package com.tungph7.learnpostgres.feature.coupon.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;

/**
 * Kết quả kiểm tra coupon.
 *
 * <p><b>Không hợp lệ ở đây KHÔNG phải lỗi HTTP.</b> Gõ nhầm mã là chuyện bình thường của giỏ hàng,
 * nên endpoint luôn trả 200 kèm {@code valid = false} và một {@code errorCode} để FE hiển thị. Ném
 * 4xx sẽ bắt FE phải bắt exception cho một luồng hoàn toàn bình thường.
 *
 * <p>Trái lại, {@code redeem()} thì ném exception thật: tới bước đó là đang đặt hàng, coupon hỏng
 * nghĩa là giao dịch phải dừng.
 *
 * <p>{@code errorCode} là {@code String} để serialize JSON, nhưng chỉ tạo được qua {@link
 * #fail(ErrorCode, String)} nên không thể gõ sai một mã không tồn tại.
 */
public record ValidateCouponResponse(
    boolean valid,
    UUID couponId,
    String code,
    CouponType type,
    BigDecimal discountAmount,
    boolean freeShipping,
    String errorCode,
    String message) {

  public static ValidateCouponResponse ok(
      UUID id, String code, CouponType type, BigDecimal discount, boolean freeShipping) {
    return new ValidateCouponResponse(true, id, code, type, discount, freeShipping, null, null);
  }

  public static ValidateCouponResponse fail(ErrorCode errorCode, String message) {
    return new ValidateCouponResponse(
        false, null, null, null, BigDecimal.ZERO, false, errorCode.code(), message);
  }
}
