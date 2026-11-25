package com.tungph7.learnpostgres.feature.coupon.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tạo coupon mới.
 *
 * <p>Bean Validation ở đây chặn những gì nhìn <em>request</em> là biết sai — kể cả rule nhìn hai
 * field ({@link #isPeriodOrdered()}). Hai rule còn lại phải để DB quyết vì chúng phụ thuộc dữ liệu
 * đang có, không phải phụ thuộc request:
 *
 * <ul>
 *   <li>miền của {@code value} phụ thuộc {@code type} → {@code ck_coupons_value};
 *   <li>không trùng khoảng thời gian với campaign cùng code → {@code ex_coupons_code_no_overlap},
 *       và rule này <b>không thể</b> pre-check ở app mà không có race.
 * </ul>
 *
 * <p>{@code value} dùng {@code @PositiveOrZero} chứ không {@code @Positive}: FREE_SHIPPING hợp lệ
 * với {@code value = 0}. Chặn PERCENT/AMOUNT bằng 0 là việc của {@code ck_coupons_value}.
 */
public record CreateCouponRequest(
    @NotBlank
        @Size(min = 3, max = 40)
        @Pattern(
            regexp = "^[A-Z0-9_-]{3,40}$",
            message = "phải viết hoa, chỉ gồm A-Z 0-9 _ - và dài 3-40 ký tự")
        String code,
    @Size(max = 4000) String description,
    @NotNull CouponType type,
    @NotNull @PositiveOrZero BigDecimal value,
    @Positive BigDecimal maxDiscount,
    @Positive BigDecimal minOrderAmount,
    @Positive Integer maxUses,
    @Positive Integer maxUsesPerCustomer,
    @NotNull OffsetDateTime validFrom,
    @NotNull OffsetDateTime validTo) {

  /**
   * {@code validFrom < validTo} — rule nhìn hai field nên không gắn được lên field nào.
   *
   * <p>Không để {@code TstzRange} chặn hộ: nó ném {@link IllegalArgumentException}, mà {@code
   * GlobalExceptionHandler} không có handler cho kiểu đó nên request rơi vào catch-all và client
   * nhận <b>500</b>. Gửi {@code validTo} trước {@code validFrom} là lỗi của client, phải là 400 kèm
   * tên field — đúng như mọi lỗi 400 khác của API này.
   *
   * <p>{@code TstzRange} và {@code ck_coupons_period_bounded} vẫn giữ nguyên check của mình: chúng
   * bảo vệ đường ghi từ code khác (F05 gọi thẳng service), không đi qua DTO này.
   *
   * <p>Trả {@code true} khi thiếu field để {@code @NotNull} tự báo — nếu không, một request thiếu
   * {@code validTo} sẽ nhận 2 lỗi cho cùng một nguyên nhân.
   */
  @AssertTrue(message = "validTo phải sau validFrom")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isPeriodOrdered() {
    return validFrom == null || validTo == null || validFrom.isBefore(validTo);
  }
}
