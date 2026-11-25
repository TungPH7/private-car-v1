package com.tungph7.learnpostgres.feature.coupon.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.coupon.domain.CouponRedemption;

/**
 * Một lần redeem coupon.
 *
 * <p>{@code wasNew} chỉ có nghĩa trên đường {@code redeem()}: {@code true} = lần gọi này thực sự
 * tạo row và đã tăng counter, {@code false} = {@code (coupon, order)} đã redeem từ trước và lần gọi
 * này không đổi gì. F05 dùng nó để biết có phải trừ tồn kho / ghi sổ hay không.
 *
 * <p>Với danh sách lịch sử thì câu hỏi "lần gọi này có tạo mới không" vô nghĩa — dùng {@link
 * #ofHistory(CouponRedemption, String)}, nó đặt {@code wasNew = false} và nói rõ lý do ở đây thay
 * vì để mỗi call site tự đoán một giá trị.
 */
public record RedemptionResponse(
    UUID id,
    UUID couponId,
    String couponCode,
    UUID orderId,
    UUID customerId,
    BigDecimal discountAmount,
    Instant redeemedAt,
    boolean wasNew) {

  /** Lần gọi redeem này vừa tạo row. */
  public static RedemptionResponse newlyRedeemed(CouponRedemption row, String couponCode) {
    return from(row, couponCode, true);
  }

  /** {@code (coupon, order)} đã redeem từ trước — trả lại kết quả lần đầu, không đổi gì. */
  public static RedemptionResponse alreadyRedeemed(CouponRedemption row, String couponCode) {
    return from(row, couponCode, false);
  }

  /** Dòng trong lịch sử redeem. {@code wasNew} không áp dụng nên luôn {@code false}. */
  public static RedemptionResponse ofHistory(CouponRedemption row, String couponCode) {
    return from(row, couponCode, false);
  }

  private static RedemptionResponse from(CouponRedemption row, String couponCode, boolean wasNew) {
    return new RedemptionResponse(
        row.getId(),
        row.getCouponId(),
        couponCode,
        row.getOrderId(),
        row.getCustomerId(),
        row.getDiscountAmount(),
        row.getRedeemedAt(),
        wasNew);
  }
}
