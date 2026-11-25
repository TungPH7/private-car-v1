package com.tungph7.learnpostgres.feature.coupon.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.coupon.domain.CouponRedemption;

/** Repository của {@code app.coupon_redemptions} — log redeem, nền tảng của idempotency. */
@Repository
public interface RedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

  Optional<CouponRedemption> findByCouponIdAndOrderId(UUID couponId, UUID orderId);

  List<CouponRedemption> findByCustomerIdOrderByRedeemedAtDesc(UUID customerId);

  /** Số lần customer đã dùng coupon này — để enforce {@code max_uses_per_customer}. */
  long countByCouponIdAndCustomerId(UUID couponId, UUID customerId);

  /**
   * Ghi log redeem, bỏ qua nếu {@code (coupon, order)} đã có. Idempotent.
   *
   * <p>Một câu lệnh làm cả việc kiểm tra lẫn việc ghi, nên hai request đồng thời cùng {@code
   * (coupon, order)} không thể cùng ghi: UNIQUE {@code uq_coupon_redemption_order} quyết định,
   * request thua đi nhánh ON CONFLICT thay vì ném 23505.
   *
   * <p>{@code id} và {@code redeemed_at} để DB sinh ({@code app.gen_uuid_v7()}, {@code now()}) —
   * câu lệnh này không đi qua entity nên {@code @PrePersist} không chạy.
   *
   * <p>{@code RETURNING *} chứ không chỉ {@code id}: nhờ vậy {@code redeemed_at} trả về là giá trị
   * DB thực sự ghi, không phải {@code Instant.now()} của app đoán lại. Hai mốc này lệch nhau vài
   * millisecond trong trường hợp tốt, và lệch hẳn nếu đồng hồ app trôi so với đồng hồ DB.
   *
   * @return list 1 phần tử là row vừa tạo; list rỗng nghĩa là row đã tồn tại → caller load row cũ
   *     bằng {@link #findByCouponIdAndOrderId} và trả kết quả của lần redeem đầu
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO app.coupon_redemptions
              (id, coupon_id, order_id, customer_id, discount_amount, redeemed_at)
          VALUES
              (app.gen_uuid_v7(), :couponId, :orderId, :customerId, :discount, now())
          ON CONFLICT (coupon_id, order_id) DO NOTHING
          RETURNING *
          """,
      nativeQuery = true)
  List<CouponRedemption> insertIfAbsent(
      @Param("couponId") UUID couponId,
      @Param("orderId") UUID orderId,
      @Param("customerId") UUID customerId,
      @Param("discount") BigDecimal discount);

  /**
   * Xoá log redeem khi revoke.
   *
   * @return số row xoá: 1 nếu có, 0 nếu chưa từng redeem → caller KHÔNG được giảm counter
   */
  @Modifying
  @Query("DELETE FROM CouponRedemption r WHERE r.couponId = :couponId AND r.orderId = :orderId")
  int deleteByCouponAndOrder(@Param("couponId") UUID couponId, @Param("orderId") UUID orderId);
}
