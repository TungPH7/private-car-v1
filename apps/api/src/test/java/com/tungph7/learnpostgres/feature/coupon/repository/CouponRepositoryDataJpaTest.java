package com.tungph7.learnpostgres.feature.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.common.domain.TstzRange;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;

/**
 * Slice test chạy trên Postgres thật (Testcontainers) với {@code ddl-auto: validate}.
 *
 * <p>Ba thứ chỉ DB thật mới chứng minh được: mapping {@code tstzrange} ↔ {@link TstzRange} có
 * round-trip đúng không, EXCLUDE constraint có chặn đúng chỗ không, và {@code UPDATE ... RETURNING}
 * có map được về {@code List} không.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class CouponRepositoryDataJpaTest extends AbstractContainerBaseTest {

  private static final OffsetDateTime JUN = OffsetDateTime.parse("2026-06-01T00:00:00Z");
  private static final OffsetDateTime JUL = OffsetDateTime.parse("2026-07-01T00:00:00Z");
  private static final OffsetDateTime AUG = OffsetDateTime.parse("2026-08-01T00:00:00Z");

  /** Một mốc nằm gọn trong [JUN, JUL) — dùng cho các test không quan tâm tới biên thời gian. */
  private static final OffsetDateTime INSIDE = OffsetDateTime.parse("2026-06-15T00:00:00Z");

  @Autowired CouponRepository repository;
  @Autowired EntityManager em;

  private Coupon newCoupon(String code, String value, OffsetDateTime from, OffsetDateTime to) {
    return new Coupon(code, CouponType.PERCENT, new BigDecimal(value), from, to);
  }

  // -----------------------------------------------------------------
  // Mapping tstzrange
  // -----------------------------------------------------------------

  @Test
  @DisplayName("tstzrange round-trip — ghi rồi đọc lại ra đúng khoảng")
  void tstzRangeRoundTrip() {
    Coupon saved = repository.saveAndFlush(newCoupon("RT1", "25", JUN, JUL));
    em.clear();

    Coupon found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getValidPeriod()).isEqualTo(TstzRange.of(JUN, JUL));
    assertThat(found.getValidFrom()).isEqualTo(JUN);
    assertThat(found.getValidTo()).isEqualTo(JUL);
  }

  @Test
  @DisplayName("tstzrange round-trip — offset khác UTC được chuẩn hoá, không mất mốc thời gian")
  void tstzRangeNormalisesOffset() {
    OffsetDateTime fromPlus7 = OffsetDateTime.parse("2026-06-01T07:00:00+07:00"); // = JUN
    Coupon saved = repository.saveAndFlush(newCoupon("RT2", "25", fromPlus7, JUL));
    em.clear();

    Coupon found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getValidFrom()).isEqualTo(JUN);
    assertThat(found.getValidFrom().isEqual(fromPlus7)).isTrue();
  }

  @Test
  @DisplayName("isValidAt — biên trái thuộc khoảng, biên phải thì không")
  void isValidAtIsHalfOpen() {
    Coupon c = newCoupon("HALFOPEN", "25", JUN, JUL);

    assertThat(c.isValidAt(JUN)).isTrue();
    assertThat(c.isValidAt(OffsetDateTime.parse("2026-06-15T00:00:00Z"))).isTrue();
    assertThat(c.isValidAt(JUL)).isFalse();
    assertThat(c.isValidAt(OffsetDateTime.parse("2026-05-31T23:59:59Z"))).isFalse();
  }

  // -----------------------------------------------------------------
  // EXCLUDE constraint
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-12 — cùng code, period nối tiếp → cả 2 insert OK")
  void adjacentPeriodsSameCodeAreAllowed() {
    repository.saveAndFlush(newCoupon("ADJ", "25", JUN, JUL));
    repository.saveAndFlush(newCoupon("ADJ", "30", JUL, AUG));

    assertThat(repository.findActiveByCode("ADJ")).hasSize(2);
  }

  @Test
  @DisplayName("AC-11 — cùng code, period overlap → vi phạm EXCLUDE")
  void overlappingPeriodsSameCodeAreRejected() {
    repository.saveAndFlush(newCoupon("OVL", "25", JUN, JUL));

    Coupon overlapping = newCoupon("OVL", "30", OffsetDateTime.parse("2026-06-15T00:00:00Z"), AUG);

    assertThatThrownBy(() -> repository.saveAndFlush(overlapping))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ex_coupons_code_no_overlap");
  }

  @Test
  @DisplayName("overlap nhưng row cũ inactive → OK (partial WHERE is_active)")
  void overlapWithInactiveRowIsAllowed() {
    Coupon old = newCoupon("INACT", "25", JUN, JUL);
    old.setActive(false);
    repository.saveAndFlush(old);

    repository.saveAndFlush(
        newCoupon("INACT", "30", OffsetDateTime.parse("2026-06-15T00:00:00Z"), AUG));

    assertThat(repository.findActiveByCode("INACT")).hasSize(1);
  }

  // -----------------------------------------------------------------
  // Lookup theo code
  // -----------------------------------------------------------------

  @Test
  @DisplayName("findActiveByCode — sắp theo thời điểm bắt đầu, bỏ row inactive")
  void findActiveByCodeOrdersByLowerBound() {
    repository.saveAndFlush(newCoupon("ORD", "30", JUL, AUG));
    repository.saveAndFlush(newCoupon("ORD", "25", JUN, JUL));
    Coupon inactive = newCoupon("ORD", "99", AUG, OffsetDateTime.parse("2026-09-01T00:00:00Z"));
    inactive.setActive(false);
    repository.saveAndFlush(inactive);
    em.clear();

    List<Coupon> found = repository.findActiveByCode("ORD");

    assertThat(found).hasSize(2);
    assertThat(found.get(0).getValue()).isEqualByComparingTo("25");
    assertThat(found.get(1).getValue()).isEqualByComparingTo("30");
  }

  @Test
  @DisplayName("findActiveByCodeAt — chọn đúng campaign phủ thời điểm, biên nửa-mở")
  void findActiveByCodeAtPicksCoveringCampaign() {
    repository.saveAndFlush(newCoupon("ATX", "25", JUN, JUL));
    repository.saveAndFlush(newCoupon("ATX", "30", JUL, AUG));
    em.clear();

    assertThat(repository.findActiveByCodeAt("ATX", OffsetDateTime.parse("2026-06-15T00:00:00Z")))
        .hasValueSatisfying(c -> assertThat(c.getValue()).isEqualByComparingTo("25"));

    // 7/1 thuộc campaign sau, không thuộc campaign trước.
    assertThat(repository.findActiveByCodeAt("ATX", JUL))
        .hasValueSatisfying(c -> assertThat(c.getValue()).isEqualByComparingTo("30"));

    assertThat(repository.findActiveByCodeAt("ATX", OffsetDateTime.parse("2026-09-01T00:00:00Z")))
        .isEmpty();
  }

  @Test
  @DisplayName("findActiveAt — mọi coupon hiệu lực tại T, bất kể code")
  void findActiveAtReturnsEveryCoveringCoupon() {
    repository.saveAndFlush(newCoupon("AAA", "25", JUN, JUL));
    repository.saveAndFlush(newCoupon("BBB", "30", JUN, AUG));
    repository.saveAndFlush(newCoupon("CCC", "40", JUL, AUG));
    em.clear();

    List<Coupon> found = repository.findActiveAt(OffsetDateTime.parse("2026-06-15T00:00:00Z"));

    assertThat(found).extracting(Coupon::getCode).containsExactlyInAnyOrder("AAA", "BBB");
  }

  // -----------------------------------------------------------------
  // Atomic counter
  // -----------------------------------------------------------------

  @Test
  @DisplayName("incrementUsedCount — tăng tới max rồi trả list rỗng")
  void incrementStopsAtMaxUses() {
    Coupon c = newCoupon("CNT", "25", JUN, JUL);
    c.setMaxUses(2);
    UUID id = repository.saveAndFlush(c).getId();

    assertThat(repository.incrementUsedCount(id, INSIDE)).containsExactly(1);
    assertThat(repository.incrementUsedCount(id, INSIDE)).containsExactly(2);
    assertThat(repository.incrementUsedCount(id, INSIDE)).isEmpty();

    em.clear();
    assertThat(repository.findById(id).orElseThrow().getUsedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("incrementUsedCount — max_uses NULL = unlimited")
  void incrementIsUnlimitedWhenMaxUsesIsNull() {
    UUID id = repository.saveAndFlush(newCoupon("UNLIM", "25", JUN, JUL)).getId();

    assertThat(repository.incrementUsedCount(id, INSIDE)).containsExactly(1);
    assertThat(repository.incrementUsedCount(id, INSIDE)).containsExactly(2);
  }

  @Test
  @DisplayName("incrementUsedCount — coupon inactive → không tăng")
  void incrementSkipsInactiveCoupon() {
    Coupon c = newCoupon("OFF", "25", JUN, JUL);
    c.setActive(false);
    UUID id = repository.saveAndFlush(c).getId();

    assertThat(repository.incrementUsedCount(id, INSIDE)).isEmpty();
  }

  /**
   * Khoảng hiệu lực nằm trong WHERE chứ không kiểm ở service: giữa lúc giỏ hàng validate và lúc đặt
   * hàng thật có thể trôi qua vài phút, đủ để campaign hết hạn. Kiểm ở service thì luôn còn khoảng
   * hở đó.
   */
  @Test
  @DisplayName("incrementUsedCount — ngoài khoảng hiệu lực → không tăng")
  void incrementSkipsCouponOutsideItsPeriod() {
    UUID id = repository.saveAndFlush(newCoupon("WINDOW", "25", JUN, JUL)).getId();

    assertThat(repository.incrementUsedCount(id, JUL)).isEmpty(); // biên phải: ngoài khoảng
    assertThat(repository.incrementUsedCount(id, AUG)).isEmpty(); // đã qua hạn
    assertThat(repository.incrementUsedCount(id, JUN))
        .containsExactly(1); // biên trái: trong khoảng

    em.clear();
    assertThat(repository.findById(id).orElseThrow().getUsedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("decrementUsedCount — có sàn 0, không xuống âm")
  void decrementIsFlooredAtZero() {
    UUID id = repository.saveAndFlush(newCoupon("DEC", "25", JUN, JUL)).getId();
    repository.incrementUsedCount(id, INSIDE);

    assertThat(repository.decrementUsedCount(id)).isEqualTo(1);
    em.clear();
    assertThat(repository.findById(id).orElseThrow().getUsedCount()).isZero();

    // Revoke thừa: vẫn 1 row đổi, nhưng counter không âm (nếu âm sẽ vi phạm ck_coupons_used_count).
    assertThat(repository.decrementUsedCount(id)).isEqualTo(1);
    em.clear();
    assertThat(repository.findById(id).orElseThrow().getUsedCount()).isZero();
  }

  // -----------------------------------------------------------------
  // CHECK constraint từ V10 vẫn có hiệu lực qua entity
  // -----------------------------------------------------------------

  @Test
  @DisplayName("ck_coupons_value — PERCENT 150 bị DB chặn")
  void checkRejectsPercentAbove100() {
    Coupon bad = newCoupon("BADPCT", "150", JUN, JUL);

    assertThatThrownBy(() -> repository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_coupons_value");
  }

  @Test
  @DisplayName("ck_coupons_code_format — code chữ thường bị DB chặn")
  void checkRejectsLowercaseCode() {
    Coupon bad = newCoupon("khong-hop-le", "25", JUN, JUL);

    assertThatThrownBy(() -> repository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_coupons_code_format");
  }

  @Test
  @DisplayName("TstzRange chặn from >= to ngay ở Java, trước khi chạm DB")
  void rangeRejectsInvertedBoundsBeforeHittingDb() {
    assertThatThrownBy(() -> newCoupon("INVERT", "25", JUL, JUN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("phải trước");
  }
}
