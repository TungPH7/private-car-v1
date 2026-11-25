package com.tungph7.learnpostgres.feature.coupon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponRedemption;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.coupon.dto.CreateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.RedemptionResponse;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;
import com.tungph7.learnpostgres.feature.coupon.repository.CouponRepository;
import com.tungph7.learnpostgres.feature.coupon.repository.RedemptionRepository;

/**
 * Mặc định readOnly; method ghi override bằng {@code @Transactional}.
 *
 * <p>Nguyên tắc xuyên suốt: <b>điều kiện nghiệp vụ nào đặt được vào WHERE của câu ghi thì đặt vào
 * đó</b>, đừng kiểm ở Java rồi mới ghi. Kiểm trước-ghi-sau luôn chừa một khoảng hở cho request khác
 * chen vào giữa. Cụ thể ở đây:
 *
 * <ul>
 *   <li>trùng khoảng thời gian với campaign cùng code → EXCLUDE constraint, service không
 *       pre-check;
 *   <li>còn lượt + còn hiệu lực + chưa deactivate → nằm trong WHERE của {@code incrementUsedCount};
 *   <li>một {@code (coupon, order)} chỉ ghi một lần → UNIQUE, qua {@code insertIfAbsent}.
 * </ul>
 *
 * <p>Chỉ còn {@code max_uses_per_customer} là không nhét được vào WHERE (cần COUNT bảng khác) — xem
 * {@link #requireWithinPerCustomerLimit} để biết khoảng hở còn lại rộng bao nhiêu.
 */
@Service
@Transactional(readOnly = true)
public class CouponServiceImpl implements CouponService {

  private static final int PERCENT_SCALE = 2;
  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  private final CouponRepository couponRepo;
  private final RedemptionRepository redemptionRepo;

  public CouponServiceImpl(CouponRepository couponRepo, RedemptionRepository redemptionRepo) {
    this.couponRepo = couponRepo;
    this.redemptionRepo = redemptionRepo;
  }

  // ================================================================
  // CRUD admin
  // ================================================================

  /**
   * Cố ý KHÔNG kiểm trước xem đã có campaign nào trùng khoảng thời gian chưa.
   *
   * <p>Một câu {@code SELECT ... WHERE code = ? AND valid_period && ?} rồi mới INSERT vẫn để lọt:
   * hai request cùng chạy sẽ cùng thấy "chưa có gì" rồi cùng ghi. EXCLUDE constraint là thứ duy
   * nhất quyết định được, và {@code GlobalExceptionHandler} dịch vi phạm đó thành {@code
   * CPN_PERIOD_OVERLAPS}.
   */
  @Override
  @Transactional
  public Coupon create(CreateCouponRequest req) {
    Coupon coupon = new Coupon(req.code(), req.type(), req.value(), req.validFrom(), req.validTo());
    coupon.setDescription(req.description());
    coupon.setMaxDiscount(req.maxDiscount());
    coupon.setMinOrderAmount(req.minOrderAmount());
    coupon.setMaxUses(req.maxUses());
    coupon.setMaxUsesPerCustomer(req.maxUsesPerCustomer());
    return couponRepo.save(coupon);
  }

  @Override
  public Coupon getById(UUID id) {
    return couponRepo.findById(id).orElseThrow(() -> couponNotFound(id));
  }

  @Override
  public Coupon getByCode(String code) {
    return couponRepo
        .findActiveByCodeAt(code, OffsetDateTime.now())
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CPN_NOT_FOUND,
                    "Coupon '%s' không tồn tại, đã deactivate, hoặc ngoài khoảng hiệu lực."
                        .formatted(code)));
  }

  /**
   * Soft disable. KHÔNG xoá row: {@code coupon_redemptions} tham chiếu tới nó bằng FK RESTRICT, và
   * lịch sử redeem phải giữ được.
   *
   * <p>Deactivate cũng nhả lại khoảng thời gian cho campaign mới cùng code, vì EXCLUDE constraint
   * có partial predicate {@code WHERE is_active = TRUE}.
   */
  @Override
  @Transactional
  public void deactivate(UUID id) {
    Coupon coupon = getById(id);
    coupon.setActive(false);
    couponRepo.save(coupon);
  }

  // ================================================================
  // Validate — read-only, gọi từ giỏ hàng
  // ================================================================

  @Override
  public ValidateCouponResponse validate(ValidateCouponRequest req) {
    OffsetDateTime now = OffsetDateTime.now();

    // Lấy mọi campaign active của code (thực tế 1-3 row) trong 1 query. KHÔNG dùng
    // Optional: cùng code có thể có nhiều row active nối tiếp nhau.
    List<Coupon> actives = couponRepo.findActiveByCode(req.code());
    if (actives.isEmpty()) {
      return ValidateCouponResponse.fail(
          ErrorCode.CPN_NOT_FOUND, "Mã giảm giá không tồn tại hoặc đã ngừng áp dụng.");
    }

    // EXCLUDE bảo đảm không thời điểm nào nằm trong 2 khoảng cùng lúc → nhiều nhất 1 row
    // khớp. findFirst() vì vậy là xác định, không phải "chọn bừa một cái".
    Coupon coupon = actives.stream().filter(c -> c.isValidAt(now)).findFirst().orElse(null);

    // Code có thật nhưng không campaign nào phủ `now`. Phải tách khỏi nhánh NOT_FOUND ở
    // trên: "gõ sai mã" và "mã chưa tới/đã qua hạn" là hai việc khác nhau với người dùng.
    if (coupon == null) {
      return ValidateCouponResponse.fail(
          ErrorCode.CPN_EXPIRED, "Mã giảm giá không còn trong thời gian áp dụng.");
    }

    if (isExhausted(coupon)) {
      return ValidateCouponResponse.fail(
          ErrorCode.CPN_EXHAUSTED, "Mã giảm giá đã hết lượt sử dụng.");
    }

    if (coupon.getMinOrderAmount() != null
        && req.orderSubtotal().compareTo(coupon.getMinOrderAmount()) < 0) {
      return ValidateCouponResponse.fail(
          ErrorCode.CPN_MIN_ORDER_NOT_MET,
          "Đơn hàng tối thiểu %s để dùng mã này."
              .formatted(coupon.getMinOrderAmount().toPlainString()));
    }

    if (hasReachedPerCustomerLimit(coupon, req.customerId(), 0)) {
      return ValidateCouponResponse.fail(
          ErrorCode.CPN_PER_CUSTOMER_LIMIT, "Bạn đã dùng hết lượt cho mã giảm giá này.");
    }

    return ValidateCouponResponse.ok(
        coupon.getId(),
        coupon.getCode(),
        coupon.getType(),
        computeDiscount(coupon, req.orderSubtotal()),
        coupon.getType() == CouponType.FREE_SHIPPING);
  }

  /**
   * PERCENT bị chặn trên bởi {@code maxDiscount}; AMOUNT không vượt quá subtotal (nếu không tổng
   * đơn sẽ âm); FREE_SHIPPING giảm 0 vì phần phí ship do F05 xử lý.
   *
   * <p>{@code HALF_UP} với 2 chữ số thập phân để khớp {@code NUMERIC(12,2)} của DB — làm tròn ở
   * Java rồi mới ghi, tránh việc DB tự làm tròn theo cách khác với con số đã báo cho khách.
   */
  @Override
  public BigDecimal computeDiscount(Coupon coupon, BigDecimal orderSubtotal) {
    return switch (coupon.getType()) {
      case PERCENT -> {
        BigDecimal raw =
            orderSubtotal
                .multiply(coupon.getValue())
                .divide(ONE_HUNDRED, PERCENT_SCALE, RoundingMode.HALF_UP);
        yield coupon.getMaxDiscount() == null ? raw : raw.min(coupon.getMaxDiscount());
      }
      case AMOUNT -> coupon.getValue().min(orderSubtotal);
      case FREE_SHIPPING -> BigDecimal.ZERO;
    };
  }

  // ================================================================
  // Redeem — F05 placeOrder gọi
  // ================================================================

  /**
   * Ghi nhận một lần dùng coupon. Idempotent theo {@code (couponId, orderId)}.
   *
   * <p>Thứ tự ba bước là phần đáng chú ý nhất:
   *
   * <ol>
   *   <li><b>insert trước.</b> UNIQUE {@code (coupon_id, order_id)} là thứ quyết định lần gọi này
   *       có phải lần đầu hay không. Gọi lại cùng một order sẽ đi nhánh ON CONFLICT và trả về kết
   *       quả lần đầu — <em>không</em> kiểm tra lại gì cả, vì retry của một giao dịch đã thành công
   *       phải thành công bất kể coupon giờ ra sao.
   *   <li><b>kiểm per-customer.</b> Chạy SAU insert nên COUNT đã tính cả row vừa ghi — xem {@link
   *       #requireWithinPerCustomerLimit}.
   *   <li><b>increment sau cùng.</b> Nếu làm ngược lại (increment trước), một lần redeem lặp sẽ
   *       tăng counter rồi mới phát hiện đã tồn tại → counter phình lên sai.
   * </ol>
   *
   * <p>Bước 2 hoặc 3 hỏng thì ném exception → transaction rollback → row vừa insert biến mất. Đó là
   * lý do cả ba bước phải nằm trong cùng một {@code @Transactional}.
   */
  @Override
  @Transactional
  public RedemptionResponse redeem(
      UUID couponId, UUID orderId, UUID customerId, BigDecimal discount) {
    OffsetDateTime now = OffsetDateTime.now();
    Coupon coupon = getById(couponId);

    List<CouponRedemption> inserted =
        redemptionRepo.insertIfAbsent(couponId, orderId, customerId, discount);

    if (inserted.isEmpty()) {
      CouponRedemption existing =
          redemptionRepo
              .findByCouponIdAndOrderId(couponId, orderId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "ON CONFLICT bỏ qua insert nhưng không tìm thấy row (coupon=%s, order=%s)"
                              .formatted(couponId, orderId)));
      return RedemptionResponse.alreadyRedeemed(existing, coupon.getCode());
    }

    requireWithinPerCustomerLimit(coupon, customerId);

    if (couponRepo.incrementUsedCount(couponId, now).isEmpty()) {
      throw explainWhyCounterDidNotMove(coupon, now);
    }

    return RedemptionResponse.newlyRedeemed(inserted.get(0), coupon.getCode());
  }

  /**
   * Huỷ một lần redeem (đơn bị cancel).
   *
   * <p>Chỉ giảm counter khi thực sự xoá được row. Nếu không, gọi revoke hai lần sẽ trừ counter hai
   * lần trong khi chỉ có một lần redeem.
   *
   * <p>Không ném khi không có gì để huỷ: revoke một đơn chưa từng dùng coupon là no-op hợp lệ, và
   * F05 gọi revoke trên đường rollback nên nó phải chịu được việc gọi lặp.
   *
   * <p>Không kiểm khoảng hiệu lực — huỷ đơn sau khi campaign kết thúc vẫn phải trả lại lượt.
   */
  @Override
  @Transactional
  public void revoke(UUID couponId, UUID orderId) {
    if (redemptionRepo.deleteByCouponAndOrder(couponId, orderId) > 0) {
      couponRepo.decrementUsedCount(couponId);
    }
  }

  // ================================================================
  // Lịch sử
  // ================================================================

  /**
   * Hai query, cố định — không phải N+1.
   *
   * <p>Query 1 lấy các dòng redeem; query 2 lấy code của những coupon xuất hiện trong đó, một lần
   * cho tất cả. Một customer thường dùng đi dùng lại vài mã nên danh sách id sau khi {@code
   * distinct()} ngắn hơn hẳn số dòng.
   *
   * <p>Cách khác là JOIN ngay trong query đầu và nhận về projection. Gọn hơn một query, nhưng thêm
   * một native query cùng một interface projection nữa để bảo trì — với kích thước dữ liệu của một
   * trang lịch sử thì không đáng.
   */
  @Override
  public List<RedemptionResponse> listCustomerRedemptions(UUID customerId) {
    List<CouponRedemption> rows = redemptionRepo.findByCustomerIdOrderByRedeemedAtDesc(customerId);
    if (rows.isEmpty()) {
      return List.of();
    }

    Map<UUID, String> codeById =
        couponRepo
            .findAllById(rows.stream().map(CouponRedemption::getCouponId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Coupon::getId, Coupon::getCode));

    return rows.stream()
        .map(row -> RedemptionResponse.ofHistory(row, codeById.get(row.getCouponId())))
        .toList();
  }

  // ================================================================
  // Helpers
  // ================================================================

  private static boolean isExhausted(Coupon coupon) {
    return coupon.getMaxUses() != null && coupon.getUsedCount() >= coupon.getMaxUses();
  }

  /**
   * @param alreadyCounted số row của chính lần gọi này đã nằm trong COUNT — 0 khi mới chỉ kiểm tra
   *     (validate), 1 khi đã insert xong (redeem)
   */
  private boolean hasReachedPerCustomerLimit(Coupon coupon, UUID customerId, int alreadyCounted) {
    Integer limit = coupon.getMaxUsesPerCustomer();
    if (limit == null) {
      return false;
    }
    long used = redemptionRepo.countByCouponIdAndCustomerId(coupon.getId(), customerId);
    return used - alreadyCounted >= limit;
  }

  /**
   * Chạy sau khi đã insert, nên COUNT bao gồm cả row vừa ghi — trừ đi 1 để so với hạn mức.
   *
   * <p>Đây là điều kiện duy nhất của F04 không đưa được vào WHERE của câu ghi, vì nó cần COUNT trên
   * bảng khác. Khoảng hở còn lại: hai lần redeem của cùng customer cho hai order khác nhau chạy
   * đồng thời, chưa ai commit, cả hai cùng đếm ra số cũ và cùng qua. Hệ quả là vượt hạn mức
   * per-customer đúng 1 lượt trong tình huống hiếm; {@code used_count} tổng vẫn luôn đúng vì nó do
   * câu UPDATE atomic giữ.
   *
   * <p>Muốn đóng hẳn thì cần một bảng đếm per-(coupon, customer) với UNIQUE để chính DB quyết — đổi
   * lại thêm một bảng và một lần ghi cho mỗi redeem. F04 chưa cần tới mức đó.
   */
  private void requireWithinPerCustomerLimit(Coupon coupon, UUID customerId) {
    if (hasReachedPerCustomerLimit(coupon, customerId, 1)) {
      throw new ConflictException(
          ErrorCode.CPN_PER_CUSTOMER_LIMIT,
          "Customer %s đã dùng hết %d lượt cho coupon '%s'."
              .formatted(customerId, coupon.getMaxUsesPerCustomer(), coupon.getCode()));
    }
  }

  /**
   * {@code incrementUsedCount} trả rỗng vì một trong ba lý do; phân loại để client nhận đúng mã.
   *
   * <p>Dựa trên bản coupon đã đọc đầu transaction, không query lại: đằng nào cũng đang trên đường
   * ném lỗi, và nhánh cuối ({@code CPN_EXHAUSTED}) là kết luận đúng cho mọi trường hợp còn lại kể
   * cả khi snapshot đã cũ.
   */
  private static ConflictException explainWhyCounterDidNotMove(Coupon coupon, OffsetDateTime at) {
    if (!coupon.isActive()) {
      return new ConflictException(
          ErrorCode.CPN_EXPIRED, "Coupon '%s' đã ngừng áp dụng.".formatted(coupon.getCode()));
    }
    if (!coupon.isValidAt(at)) {
      return new ConflictException(
          ErrorCode.CPN_EXPIRED,
          "Coupon '%s' hết hiệu lực lúc %s.".formatted(coupon.getCode(), coupon.getValidTo()));
    }
    return new ConflictException(
        ErrorCode.CPN_EXHAUSTED,
        "Coupon '%s' vừa hết lượt trước khi đơn này kịp dùng.".formatted(coupon.getCode()));
  }

  private static NotFoundException couponNotFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CPN_NOT_FOUND, "Coupon '%s' không tồn tại.".formatted(id));
  }
}
