package com.tungph7.learnpostgres.feature.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tungph7.learnpostgres.common.error.BusinessException;
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
 * Unit test cho logic thuần của service — không chạm DB.
 *
 * <p>Những gì DB quyết (EXCLUDE, atomic counter, UNIQUE idempotency) đã có slice test ở bài 02 lo.
 * Ở đây chỉ kiểm phần service tự quyết: chọn campaign nào, map ra error code nào, gọi repository
 * theo thứ tự nào.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

  private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

  @Mock CouponRepository couponRepo;
  @Mock RedemptionRepository redemptionRepo;
  @InjectMocks CouponServiceImpl service;

  /**
   * Coupon còn hiệu lực quanh thời điểm hiện tại — service dùng {@code OffsetDateTime.now()}.
   *
   * <p>Gán sẵn {@code id}: {@code BaseEntity} chỉ sinh id ở {@code @PrePersist}, nên một entity
   * dựng bằng {@code new} có id null. Để null thì mọi stub theo id đều khớp lẫn nhau, và chỗ nào
   * gom id vào {@code List.of(...)} sẽ ném NPE.
   */
  private static Coupon activeCoupon(String code, CouponType type, String value) {
    OffsetDateTime now = OffsetDateTime.now();
    Coupon coupon =
        new Coupon(code, type, new BigDecimal(value), now.minusDays(1), now.plusDays(1));
    ReflectionSupport.setId(coupon, UUID.randomUUID());
    return coupon;
  }

  private static ValidateCouponRequest request(String code, String subtotal, UUID customerId) {
    return new ValidateCouponRequest(code, new BigDecimal(subtotal), customerId);
  }

  private static CouponRedemption redemptionRow(UUID couponId, UUID orderId, UUID customerId) {
    CouponRedemption row =
        new CouponRedemption(couponId, orderId, customerId, new BigDecimal("250000"));
    // insertIfAbsent để DB sinh id/redeemed_at; ở unit test mô phỏng bằng @PrePersist.
    ReflectionSupport.invokePrePersist(row);
    return row;
  }

  // ================================================================
  // CRUD admin
  // ================================================================

  @Nested
  @DisplayName("CRUD")
  class Crud {

    @Test
    @DisplayName("create — chuyển đủ field tuỳ chọn sang entity rồi để DB phán quyết")
    void createCopiesEveryOptionalField() {
      OffsetDateTime now = OffsetDateTime.now();
      CreateCouponRequest req =
          new CreateCouponRequest(
              "SUM25",
              "Hè 2025",
              CouponType.PERCENT,
              new BigDecimal("25"),
              new BigDecimal("100000"),
              new BigDecimal("500000"),
              100,
              1,
              now,
              now.plusDays(30));
      when(couponRepo.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

      Coupon saved = service.create(req);

      assertThat(saved.getCode()).isEqualTo("SUM25");
      assertThat(saved.getDescription()).isEqualTo("Hè 2025");
      assertThat(saved.getMaxDiscount()).isEqualByComparingTo("100000");
      assertThat(saved.getMinOrderAmount()).isEqualByComparingTo("500000");
      assertThat(saved.getMaxUses()).isEqualTo(100);
      assertThat(saved.getMaxUsesPerCustomer()).isEqualTo(1);
      assertThat(saved.getValidFrom()).isEqualTo(now.withOffsetSameInstant(ZoneOffset.UTC));
      assertThat(saved.isActive()).isTrue();
      assertThat(saved.getUsedCount()).isZero();
    }

    @Test
    @DisplayName("getById — không có → CPN_NOT_FOUND")
    void getByIdThrowsWhenMissing() {
      UUID id = UUID.randomUUID();
      when(couponRepo.findById(id)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getById(id))
          .isInstanceOf(NotFoundException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_NOT_FOUND);
    }

    @Test
    @DisplayName("getById — có thì trả về chính nó")
    void getByIdReturnsCoupon() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));

      assertThat(service.getById(c.getId())).isSameAs(c);
    }

    /** Tra theo thời điểm, không phải "row active duy nhất" — một code có thể có nhiều campaign. */
    @Test
    @DisplayName("getByCode — tra theo thời điểm hiện tại")
    void getByCodeLooksUpByInstant() {
      Coupon c = activeCoupon("SUM25", CouponType.PERCENT, "25");
      when(couponRepo.findActiveByCodeAt(eq("SUM25"), any())).thenReturn(Optional.of(c));

      assertThat(service.getByCode("SUM25")).isSameAs(c);
    }

    @Test
    @DisplayName("getByCode — không campaign nào đang hiệu lực → CPN_NOT_FOUND")
    void getByCodeThrowsWhenNothingActiveNow() {
      when(couponRepo.findActiveByCodeAt(eq("GONE"), any())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getByCode("GONE"))
          .isInstanceOf(NotFoundException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_NOT_FOUND);
    }

    /** Soft disable: row phải ở lại vì coupon_redemptions tham chiếu tới nó bằng FK RESTRICT. */
    @Test
    @DisplayName("deactivate — tắt cờ active rồi lưu, KHÔNG xoá row")
    void deactivateFlipsFlagWithoutDeleting() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));

      service.deactivate(c.getId());

      assertThat(c.isActive()).isFalse();
      verify(couponRepo).save(c);
      verify(couponRepo, never()).deleteById(any());
    }

    /**
     * Mỗi dòng lịch sử phải kèm {@code couponCode} — thứ nằm ở bảng khác. Service lấy bằng đúng 2
     * query cố định, không phải N+1: một query cho các dòng redeem, một query cho code của những
     * coupon xuất hiện trong đó.
     */
    @Test
    @DisplayName("listCustomerRedemptions — điền couponCode, 2 query dù bao nhiêu dòng")
    void listCustomerRedemptionsFillsCouponCodeWithoutNplusOne() {
      UUID customerId = UUID.randomUUID();
      Coupon coupon = activeCoupon("SUM25", CouponType.PERCENT, "25");
      // 3 dòng nhưng chỉ 2 coupon khác nhau → query thứ 2 chỉ hỏi 2 id.
      Coupon other = activeCoupon("WIN25", CouponType.PERCENT, "30");
      List<CouponRedemption> rows =
          List.of(
              redemptionRow(coupon.getId(), UUID.randomUUID(), customerId),
              redemptionRow(other.getId(), UUID.randomUUID(), customerId),
              redemptionRow(coupon.getId(), UUID.randomUUID(), customerId));
      when(redemptionRepo.findByCustomerIdOrderByRedeemedAtDesc(customerId)).thenReturn(rows);
      when(couponRepo.findAllById(List.of(coupon.getId(), other.getId())))
          .thenReturn(List.of(coupon, other));

      List<RedemptionResponse> history = service.listCustomerRedemptions(customerId);

      assertThat(history)
          .extracting(RedemptionResponse::couponCode)
          .containsExactly("SUM25", "WIN25", "SUM25");
      // wasNew không áp dụng cho lịch sử.
      assertThat(history).extracting(RedemptionResponse::wasNew).containsOnly(false);
    }

    @Test
    @DisplayName("listCustomerRedemptions — không có gì thì không hỏi bảng coupon")
    void listCustomerRedemptionsSkipsSecondQueryWhenEmpty() {
      UUID customerId = UUID.randomUUID();
      when(redemptionRepo.findByCustomerIdOrderByRedeemedAtDesc(customerId)).thenReturn(List.of());

      assertThat(service.listCustomerRedemptions(customerId)).isEmpty();
      verify(couponRepo, never()).findAllById(any());
    }
  }

  // ================================================================
  // computeDiscount
  // ================================================================

  @Nested
  @DisplayName("computeDiscount")
  class ComputeDiscount {

    @Test
    @DisplayName("PERCENT — bị chặn bởi maxDiscount")
    void percentIsCappedByMaxDiscount() {
      Coupon c = activeCoupon("PCT", CouponType.PERCENT, "25");
      c.setMaxDiscount(new BigDecimal("100000"));

      // 25% của 1tr = 250k, nhưng trần là 100k.
      assertThat(service.computeDiscount(c, ONE_MILLION)).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("PERCENT — không có maxDiscount thì giảm đủ")
    void percentWithoutCap() {
      Coupon c = activeCoupon("PCT", CouponType.PERCENT, "25");

      assertThat(service.computeDiscount(c, ONE_MILLION)).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("PERCENT — làm tròn HALF_UP tới 2 chữ số, khớp NUMERIC(12,2)")
    void percentRoundsHalfUpToTwoDecimals() {
      Coupon c = activeCoupon("PCT", CouponType.PERCENT, "33.33");

      // 33.33% của 1000.05 = 333.3166... → 333.32
      assertThat(service.computeDiscount(c, new BigDecimal("1000.05")))
          .isEqualByComparingTo("333.32");
    }

    @Test
    @DisplayName("AMOUNT — không vượt quá subtotal, nếu không tổng đơn sẽ âm")
    void amountNeverExceedsSubtotal() {
      Coupon c = activeCoupon("AMT", CouponType.AMOUNT, "200000");

      assertThat(service.computeDiscount(c, new BigDecimal("100000")))
          .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("FREE_SHIPPING — giảm 0, phần ship do F05 xử lý")
    void freeShippingDiscountsNothing() {
      Coupon c = activeCoupon("SHIP", CouponType.FREE_SHIPPING, "0");

      assertThat(service.computeDiscount(c, new BigDecimal("500000")))
          .isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  // ================================================================
  // validate
  // ================================================================

  @Nested
  @DisplayName("validate")
  class Validate {

    @Test
    @DisplayName("code không tồn tại → CPN_NOT_FOUND")
    void unknownCode() {
      when(couponRepo.findActiveByCode("NOPE")).thenReturn(List.of());

      ValidateCouponResponse r = service.validate(request("NOPE", "100000", UUID.randomUUID()));

      assertThat(r.valid()).isFalse();
      assertThat(r.errorCode()).isEqualTo(ErrorCode.CPN_NOT_FOUND.code());
    }

    @Test
    @DisplayName("code có nhưng mọi campaign ngoài hạn → CPN_EXPIRED, KHÔNG phải NOT_FOUND")
    void codeExistsButOutsideEveryPeriod() {
      OffsetDateTime now = OffsetDateTime.now();
      Coupon past =
          new Coupon(
              "OLD",
              CouponType.PERCENT,
              new BigDecimal("10"),
              now.minusDays(100),
              now.minusDays(50));
      when(couponRepo.findActiveByCode("OLD")).thenReturn(List.of(past));

      ValidateCouponResponse r = service.validate(request("OLD", "100000", UUID.randomUUID()));

      assertThat(r.errorCode()).isEqualTo(ErrorCode.CPN_EXPIRED.code());
    }

    /**
     * Cùng code, 2 campaign active nối tiếp nhau — hợp lệ vì EXCLUDE chỉ chặn overlap. Service phải
     * chọn campaign phủ thời điểm hiện tại, không phải row đầu danh sách.
     */
    @Test
    @DisplayName("nhiều campaign cùng code → chọn đúng cái đang hiệu lực")
    void picksTheCampaignCoveringNow() {
      OffsetDateTime now = OffsetDateTime.now();
      Coupon past =
          new Coupon(
              "SUM25",
              CouponType.PERCENT,
              new BigDecimal("25"),
              now.minusDays(60),
              now.minusDays(30));
      Coupon current =
          new Coupon(
              "SUM25",
              CouponType.PERCENT,
              new BigDecimal("30"),
              now.minusDays(30),
              now.plusDays(30));
      // Repository ORDER BY lower(valid_period) → campaign cũ đứng trước.
      when(couponRepo.findActiveByCode("SUM25")).thenReturn(List.of(past, current));

      ValidateCouponResponse r = service.validate(request("SUM25", "1000000", UUID.randomUUID()));

      assertThat(r.valid()).isTrue();
      // 30% chứ không phải 25% → đã chọn campaign thứ hai.
      assertThat(r.discountAmount()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("hết lượt → CPN_EXHAUSTED")
    void exhausted() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "10");
      c.setMaxUses(5);
      ReflectionSupport.setUsedCount(c, 5);
      when(couponRepo.findActiveByCode("TEST")).thenReturn(List.of(c));

      ValidateCouponResponse r = service.validate(request("TEST", "100000", UUID.randomUUID()));

      assertThat(r.errorCode()).isEqualTo(ErrorCode.CPN_EXHAUSTED.code());
    }

    @Test
    @DisplayName("đơn dưới mức tối thiểu → CPN_MIN_ORDER_NOT_MET")
    void belowMinimumOrder() {
      Coupon c = activeCoupon("TEST", CouponType.AMOUNT, "100000");
      c.setMinOrderAmount(new BigDecimal("500000"));
      when(couponRepo.findActiveByCode("TEST")).thenReturn(List.of(c));

      ValidateCouponResponse r = service.validate(request("TEST", "400000", UUID.randomUUID()));

      assertThat(r.errorCode()).isEqualTo(ErrorCode.CPN_MIN_ORDER_NOT_MET.code());
    }

    @Test
    @DisplayName("hết lượt per-customer → CPN_PER_CUSTOMER_LIMIT")
    void perCustomerLimitReached() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "10");
      c.setMaxUsesPerCustomer(1);
      UUID customerId = UUID.randomUUID();
      when(couponRepo.findActiveByCode("TEST")).thenReturn(List.of(c));
      when(redemptionRepo.countByCouponIdAndCustomerId(c.getId(), customerId)).thenReturn(1L);

      ValidateCouponResponse r = service.validate(request("TEST", "100000", customerId));

      assertThat(r.errorCode()).isEqualTo(ErrorCode.CPN_PER_CUSTOMER_LIMIT.code());
    }

    @Test
    @DisplayName("FREE_SHIPPING hợp lệ → valid, discount 0, cờ freeShipping bật")
    void freeShippingSetsFlag() {
      Coupon c = activeCoupon("SHIP", CouponType.FREE_SHIPPING, "0");
      when(couponRepo.findActiveByCode("SHIP")).thenReturn(List.of(c));

      ValidateCouponResponse r = service.validate(request("SHIP", "500000", UUID.randomUUID()));

      assertThat(r.valid()).isTrue();
      assertThat(r.freeShipping()).isTrue();
      assertThat(r.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(r.errorCode()).isNull();
    }
  }

  // ================================================================
  // redeem
  // ================================================================

  @Nested
  @DisplayName("redeem")
  class Redeem {

    @Test
    @DisplayName("lần đầu → wasNew=true, counter được tăng")
    void firstRedeemIncrementsCounter() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();
      CouponRedemption row = redemptionRow(c.getId(), orderId, customerId);

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any())).thenReturn(List.of(row));
      when(couponRepo.incrementUsedCount(eq(c.getId()), any())).thenReturn(List.of(1));

      RedemptionResponse r =
          service.redeem(c.getId(), orderId, customerId, new BigDecimal("250000"));

      assertThat(r.wasNew()).isTrue();
      assertThat(r.couponCode()).isEqualTo("TEST");
      assertThat(r.redeemedAt()).isEqualTo(row.getRedeemedAt());
    }

    /**
     * Retry của một redeem đã thành công phải thành công lại, và tuyệt đối không được tăng counter
     * lần nữa. Đây là lý do insert phải chạy TRƯỚC increment.
     */
    @Test
    @DisplayName("gọi lại cùng (coupon, order) → wasNew=false, KHÔNG tăng counter")
    void repeatRedeemIsIdempotent() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();
      CouponRedemption existing = redemptionRow(c.getId(), orderId, customerId);

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any())).thenReturn(List.of());
      when(redemptionRepo.findByCouponIdAndOrderId(c.getId(), orderId))
          .thenReturn(Optional.of(existing));

      RedemptionResponse r =
          service.redeem(c.getId(), orderId, customerId, new BigDecimal("250000"));

      assertThat(r.wasNew()).isFalse();
      assertThat(r.id()).isEqualTo(existing.getId());
      verify(couponRepo, never()).incrementUsedCount(any(), any());
    }

    @Test
    @DisplayName("coupon không tồn tại → CPN_NOT_FOUND, không ghi gì")
    void unknownCouponIsRejectedBeforeWriting() {
      UUID ghost = UUID.randomUUID();
      when(couponRepo.findById(ghost)).thenReturn(Optional.empty());

      assertThatThrownBy(
              () ->
                  service.redeem(
                      ghost, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000")))
          .isInstanceOf(NotFoundException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_NOT_FOUND);

      verify(redemptionRepo, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("vượt hạn mức per-customer → CPN_PER_CUSTOMER_LIMIT, counter không tăng")
    void perCustomerLimitBlocksRedeem() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      c.setMaxUsesPerCustomer(1);
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any()))
          .thenReturn(List.of(redemptionRow(c.getId(), orderId, customerId)));
      // COUNT chạy SAU insert nên đã tính cả row vừa ghi: 2 nghĩa là đây là lần thứ 2.
      when(redemptionRepo.countByCouponIdAndCustomerId(c.getId(), customerId)).thenReturn(2L);

      assertThatThrownBy(
              () -> service.redeem(c.getId(), orderId, customerId, new BigDecimal("1000")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_PER_CUSTOMER_LIMIT);

      verify(couponRepo, never()).incrementUsedCount(any(), any());
    }

    @Test
    @DisplayName("hạn mức per-customer vừa đủ → lần redeem cuối cùng vẫn qua")
    void lastAllowedRedeemPasses() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      c.setMaxUsesPerCustomer(1);
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any()))
          .thenReturn(List.of(redemptionRow(c.getId(), orderId, customerId)));
      // COUNT = 1 và row đó chính là row vừa insert → đây là lượt đầu tiên, hợp lệ.
      when(redemptionRepo.countByCouponIdAndCustomerId(c.getId(), customerId)).thenReturn(1L);
      when(couponRepo.incrementUsedCount(eq(c.getId()), any())).thenReturn(List.of(1));

      assertThat(service.redeem(c.getId(), orderId, customerId, new BigDecimal("1000")).wasNew())
          .isTrue();
    }

    @Test
    @DisplayName("counter không nhúc nhích vì coupon đã deactivate → CPN_EXPIRED")
    void inactiveCouponReportsExpired() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      c.setActive(false);
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any()))
          .thenReturn(List.of(redemptionRow(c.getId(), orderId, customerId)));
      when(couponRepo.incrementUsedCount(eq(c.getId()), any())).thenReturn(List.of());

      assertThatThrownBy(
              () -> service.redeem(c.getId(), orderId, customerId, new BigDecimal("1000")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_EXPIRED);
    }

    @Test
    @DisplayName("counter không nhúc nhích mà coupon vẫn hợp lệ → CPN_EXHAUSTED (race)")
    void exhaustedDuringCheckoutReportsExhausted() {
      Coupon c = activeCoupon("TEST", CouponType.PERCENT, "25");
      c.setMaxUses(1);
      UUID orderId = UUID.randomUUID();
      UUID customerId = UUID.randomUUID();

      when(couponRepo.findById(c.getId())).thenReturn(Optional.of(c));
      when(redemptionRepo.insertIfAbsent(any(), any(), any(), any()))
          .thenReturn(List.of(redemptionRow(c.getId(), orderId, customerId)));
      when(couponRepo.incrementUsedCount(eq(c.getId()), any())).thenReturn(List.of());

      assertThatThrownBy(
              () -> service.redeem(c.getId(), orderId, customerId, new BigDecimal("1000")))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getCode())
          .isEqualTo(ErrorCode.CPN_EXHAUSTED);
    }
  }

  // ================================================================
  // revoke
  // ================================================================

  @Nested
  @DisplayName("revoke")
  class Revoke {

    @Test
    @DisplayName("có row để xoá → giảm counter")
    void decrementsWhenRowWasDeleted() {
      UUID couponId = UUID.randomUUID();
      UUID orderId = UUID.randomUUID();
      when(redemptionRepo.deleteByCouponAndOrder(couponId, orderId)).thenReturn(1);

      service.revoke(couponId, orderId);

      verify(couponRepo).decrementUsedCount(couponId);
    }

    /** Gọi revoke hai lần mà vẫn trừ counter hai lần thì counter sẽ lệch xuống dưới thực tế. */
    @Test
    @DisplayName("không có gì để xoá → KHÔNG giảm counter, cũng không ném")
    void doesNothingWhenNoRowMatched() {
      UUID couponId = UUID.randomUUID();
      UUID orderId = UUID.randomUUID();
      when(redemptionRepo.deleteByCouponAndOrder(couponId, orderId)).thenReturn(0);

      service.revoke(couponId, orderId);

      verify(couponRepo, never()).decrementUsedCount(any());
    }
  }

  /**
   * Đặt những field mà production code cố ý không cho ghi.
   *
   * <p>{@code usedCount} không có setter vì chỉ được đổi bằng UPDATE atomic; {@code id} / {@code
   * redeemedAt} do DB sinh. Unit test cần dựng sẵn các trạng thái đó nên đi đường reflection — giữ
   * gọn trong một chỗ thay vì rải khắp test.
   */
  private static final class ReflectionSupport {

    private ReflectionSupport() {}

    static void setUsedCount(Coupon coupon, int value) {
      set(coupon, Coupon.class, "usedCount", value);
    }

    /** {@code id} nằm ở {@code BaseEntity} và setter của nó là package-private. */
    static void setId(Coupon coupon, UUID id) {
      set(coupon, Coupon.class.getSuperclass(), "id", id);
    }

    /** Chạy @PrePersist để có id + redeemedAt như khi đi qua JPA. */
    static void invokePrePersist(CouponRedemption row) {
      try {
        var method = CouponRedemption.class.getDeclaredMethod("onPrePersist");
        method.setAccessible(true);
        method.invoke(row);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Không gọi được onPrePersist", e);
      }
    }

    private static void set(Object target, Class<?> declaringClass, String field, Object value) {
      try {
        var f = declaringClass.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Không set được field " + field, e);
      }
    }
  }
}
