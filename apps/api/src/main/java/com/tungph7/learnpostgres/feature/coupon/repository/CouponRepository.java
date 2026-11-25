package com.tungph7.learnpostgres.feature.coupon.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;

/**
 * Repository của {@code app.coupons}.
 *
 * <p><b>Điểm dễ sai nhất: {@code code} KHÔNG unique.</b> EXCLUDE constraint chỉ chặn 2 row active
 * cùng code <em>overlap</em> thời gian; nhiều campaign cùng code nối tiếp nhau ({@code [6/1,7/1)} +
 * {@code [7/1,8/1)}) là hợp lệ và có thật (AC-12). Vì vậy lookup theo code phải là một trong hai:
 *
 * <ul>
 *   <li>trả {@code List} — {@link #findActiveByCode(String)};
 *   <li>hoặc kèm điều kiện thời gian để thu về tối đa 1 row — {@link #findActiveByCodeAt(String,
 *       OffsetDateTime)}.
 * </ul>
 *
 * <p>Một method kiểu {@code Optional<Coupon> findByCodeAndActiveTrue(String)} sẽ chạy đúng cho tới
 * khi data thật có 2 campaign nối tiếp, rồi ném {@code IncorrectResultSizeDataAccessException} —
 * lỗi chỉ xuất hiện ở production.
 *
 * <p><b>Về {@code @Modifying} trên method trả {@code List}</b>: Spring Data chọn execution strategy
 * theo kiểu trả về trước, nên method trả {@code List} đi đường {@code CollectionExecution} và map
 * được result set của {@code RETURNING}. Chi tiết xem Javadoc của {@code InventoryRepository} (F03)
 * — cùng pattern.
 */
@Repository
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

  // -----------------------------------------------------------------
  // Lookup theo code
  // -----------------------------------------------------------------

  /**
   * Mọi campaign đang active của một code, sắp theo thời điểm bắt đầu.
   *
   * <p>Dùng khi cần phân biệt "code không tồn tại" với "code có nhưng ngoài thời gian hiệu lực" —
   * hai trường hợp này map ra 2 error code khác nhau (CPN_NOT_FOUND vs CPN_EXPIRED).
   *
   * <p>Chạy trên index {@code idx_coupons_code_active} (partial btree, non-unique).
   */
  @Query(
      value =
          """
          SELECT * FROM app.coupons
          WHERE code = :code
            AND is_active = TRUE
          ORDER BY lower(valid_period)
          """,
      nativeQuery = true)
  List<Coupon> findActiveByCode(@Param("code") String code);

  /**
   * Campaign đang hiệu lực của một code tại thời điểm {@code at}.
   *
   * <p>{@code Optional} ở đây hợp lệ, và chính EXCLUDE constraint làm nó hợp lệ: 2 row active cùng
   * code không được overlap, nên không thời điểm nào nằm trong cả hai khoảng. Bỏ EXCLUDE đi thì
   * signature này sai ngay.
   *
   * <p>{@code CAST(:at AS timestamptz)} là bắt buộc: trong native query Hibernate bind {@code
   * OffsetDateTime} mà không kèm type, Postgres không suy được kiểu cho toán tử {@code @>} và báo
   * {@code could not determine data type of parameter}.
   */
  @Query(
      value =
          """
          SELECT * FROM app.coupons
          WHERE code = :code
            AND is_active = TRUE
            AND valid_period @> CAST(:at AS timestamptz)
          """,
      nativeQuery = true)
  Optional<Coupon> findActiveByCodeAt(@Param("code") String code, @Param("at") OffsetDateTime at);

  /** Mọi coupon đang hiệu lực tại {@code at}, bất kể code. Dùng GiST index trên valid_period. */
  @Query(
      value =
          """
          SELECT * FROM app.coupons
          WHERE is_active = TRUE
            AND valid_period @> CAST(:at AS timestamptz)
          ORDER BY created_at DESC
          """,
      nativeQuery = true)
  List<Coupon> findActiveAt(@Param("at") OffsetDateTime at);

  // -----------------------------------------------------------------
  // Atomic counter
  // -----------------------------------------------------------------

  /**
   * Tăng {@code used_count} nếu coupon còn dùng được tại thời điểm {@code at}. Atomic — cả ba điều
   * kiện nghiệp vụ nằm trong WHERE.
   *
   * <p>Không có khoảng hở check-rồi-mới-ghi: Postgres lock row khi UPDATE chạm tới nó và re-check
   * WHERE trên state đã được request trước ghi xong. 100 request đồng thời với {@code max_uses =
   * 50} cho đúng 50 lần trả về non-empty.
   *
   * <p>{@code valid_period @> at} nằm trong WHERE chứ không kiểm ở service là có chủ ý: giữa lúc
   * giỏ hàng validate và lúc đặt hàng thật có thể trôi qua vài phút, đủ để một campaign hết hạn.
   * Kiểm ở service thì luôn còn khoảng hở đó; đưa vào WHERE thì không.
   *
   * @param at thời điểm coi là "bây giờ" — caller truyền vào để mọi bước trong một lần redeem dùng
   *     chung một mốc, và để test không phụ thuộc đồng hồ thật
   * @return list 1 phần tử là {@code used_count} SAU khi tăng; list rỗng nghĩa là coupon không tồn
   *     tại, đã deactivate, ngoài khoảng hiệu lực, hoặc hết lượt — caller phân loại tiếp để chọn
   *     error code
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.coupons
          SET used_count = used_count + 1
          WHERE id = :couponId
            AND is_active = TRUE
            AND valid_period @> CAST(:at AS timestamptz)
            AND (max_uses IS NULL OR used_count < max_uses)
          RETURNING used_count
          """,
      nativeQuery = true)
  List<Integer> incrementUsedCount(
      @Param("couponId") UUID couponId, @Param("at") OffsetDateTime at);

  /**
   * Giảm {@code used_count} khi revoke/huỷ đơn.
   *
   * <p>{@code GREATEST(0, ...)} chặn xuống âm — nếu không, một lần revoke thừa sẽ vi phạm {@code
   * ck_coupons_used_count} và ném DataIntegrityViolationException giữa transaction.
   *
   * @return số row đổi: 1 nếu coupon tồn tại, 0 nếu không
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.coupons
          SET used_count = GREATEST(0, used_count - 1)
          WHERE id = :couponId
          """,
      nativeQuery = true)
  int decrementUsedCount(@Param("couponId") UUID couponId);
}
