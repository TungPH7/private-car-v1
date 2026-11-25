package com.tungph7.learnpostgres.feature.coupon.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.dto.CreateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.RedemptionResponse;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;

/**
 * Nghiệp vụ coupon.
 *
 * <p>Ba nhóm method, khác nhau ở cách báo lỗi — cố ý, không phải thiếu nhất quán:
 *
 * <ul>
 *   <li><b>CRUD admin</b> — ném exception, map ra 4xx. Admin thao tác sai thì phải biết.
 *   <li><b>{@link #validate}</b> — luôn trả về response, không ném. Khách gõ nhầm mã ở giỏ hàng là
 *       luồng bình thường, không phải lỗi HTTP.
 *   <li><b>{@link #redeem} / {@link #revoke}</b> — ném exception. Tới bước này là đang đặt hàng;
 *       coupon hỏng thì giao dịch phải dừng và rollback.
 * </ul>
 */
public interface CouponService {

  // ---- CRUD admin ----

  Coupon create(CreateCouponRequest req);

  Coupon getById(UUID id);

  /**
   * Coupon đang hiệu lực tại thời điểm gọi.
   *
   * <p>Không phải "row active duy nhất của code": một code có thể có nhiều campaign active nối tiếp
   * nhau (AC-12), nên phải chọn theo thời gian.
   */
  Coupon getByCode(String code);

  void deactivate(UUID id);

  // ---- Validate (FE giỏ hàng gọi trước khi đặt) ----

  ValidateCouponResponse validate(ValidateCouponRequest req);

  /**
   * Số tiền giảm theo {@code type} — thuần tính toán, không chạm DB.
   *
   * <p>Nằm trong interface vì F05 cần tính discount trước khi gọi {@link #redeem}: con số truyền
   * vào redeem phải do cùng một công thức sinh ra, không được tính lại ở nơi khác.
   */
  BigDecimal computeDiscount(Coupon coupon, BigDecimal orderSubtotal);

  // ---- Redeem (F05 placeOrder gọi) ----

  RedemptionResponse redeem(UUID couponId, UUID orderId, UUID customerId, BigDecimal discount);

  void revoke(UUID couponId, UUID orderId);

  // ---- Lịch sử ----

  /**
   * Lịch sử redeem của một customer, mới nhất trước.
   *
   * <p>Trả thẳng DTO chứ không trả entity, vì mỗi dòng cần cả {@code couponCode} — thứ nằm ở bảng
   * khác. Để caller tự ghép sẽ đẩy nó vào N+1, hoặc buộc nó chấp nhận một field {@code null}. Cùng
   * cách {@code InventoryService.getLowStockProducts} đang làm.
   */
  List<RedemptionResponse> listCustomerRedemptions(UUID customerId);
}
