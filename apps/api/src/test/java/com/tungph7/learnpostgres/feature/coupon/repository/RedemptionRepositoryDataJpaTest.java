package com.tungph7.learnpostgres.feature.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponRedemption;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;

/** Slice test cho idempotent redeem — nền tảng là UNIQUE {@code (coupon_id, order_id)} (V11). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class RedemptionRepositoryDataJpaTest extends AbstractContainerBaseTest {

  private static final BigDecimal DISCOUNT = new BigDecimal("250000.00");

  @Autowired RedemptionRepository repository;
  @Autowired CouponRepository couponRepository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  private UUID couponId;
  private UUID customerId;
  private UUID orderId;

  /** Order thứ hai của cùng customer — cho các test cần "khác order". */
  private UUID otherOrderId;

  @BeforeEach
  void setUp() {
    Coupon coupon =
        new Coupon(
            "REDEEM",
            CouponType.PERCENT,
            new BigDecimal("25"),
            OffsetDateTime.parse("2026-06-01T00:00:00Z"),
            OffsetDateTime.parse("2026-07-01T00:00:00Z"));
    couponId = couponRepository.saveAndFlush(coupon).getId();

    Customer customer = new Customer("redeem.probe@example.com", null, "Redeem", "Probe");
    em.persist(customer);
    em.flush();
    customerId = customer.getId();

    // Từ V12, order_id có FK tới app.orders — phải là order thật, UUID tuỳ ý bị DB chặn.
    UUID addressId = OrderFixtures.insertShippingAddress(jdbc, customerId);
    List<UUID> orders = OrderFixtures.insertOrders(jdbc, addressId, 2);
    orderId = orders.get(0);
    otherOrderId = orders.get(1);
  }

  @Test
  @DisplayName("insertIfAbsent — lần đầu trả id, lần 2 cùng (coupon, order) trả rỗng")
  void insertIsIdempotentPerCouponAndOrder() {
    List<CouponRedemption> first =
        repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);
    List<CouponRedemption> second =
        repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);

    assertThat(first).hasSize(1);
    assertThat(first.get(0).getId()).isNotNull();
    assertThat(second).isEmpty();
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("insertIfAbsent — khác order thì vẫn ghi thêm row")
  void insertAllowsDifferentOrders() {
    repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);
    repository.insertIfAbsent(couponId, otherOrderId, customerId, DISCOUNT);

    assertThat(repository.countByCouponIdAndCustomerId(couponId, customerId)).isEqualTo(2);
  }

  /**
   * {@code RETURNING *} trả về đúng row DB vừa ghi, nên caller không phải đoán lại {@code
   * redeemed_at} bằng {@code Instant.now()} của app. Test so row trả về với row đọc lại từ DB để
   * chốt điều đó.
   */
  @Test
  @DisplayName("insertIfAbsent — row trả về khớp hệt row trong DB (id, redeemed_at do DB sinh)")
  void returnedRowMatchesWhatDbStored() {
    CouponRedemption returned =
        repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT).get(0);
    em.clear();

    CouponRedemption reread = repository.findByCouponIdAndOrderId(couponId, orderId).orElseThrow();

    assertThat(returned.getId()).isNotNull().isEqualTo(reread.getId());
    assertThat(returned.getRedeemedAt()).isNotNull().isEqualTo(reread.getRedeemedAt());
    assertThat(returned.getDiscountAmount()).isEqualByComparingTo(DISCOUNT);
    assertThat(returned.getCouponId()).isEqualTo(couponId);
    assertThat(returned.getOrderId()).isEqualTo(orderId);
    assertThat(returned.getCustomerId()).isEqualTo(customerId);
  }

  @Test
  @DisplayName("FK customer_id — customer không tồn tại → DB chặn")
  void rejectsUnknownCustomer() {
    UUID ghost = UUID.randomUUID();

    assertThatThrownBy(() -> repository.insertIfAbsent(couponId, orderId, ghost, DISCOUNT))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("coupon_redemptions_customer_id_fkey");
  }

  @Test
  @DisplayName("FK order_id (V12) — order không tồn tại → DB chặn")
  void rejectsUnknownOrder() {
    UUID ghost = UUID.randomUUID();

    assertThatThrownBy(() -> repository.insertIfAbsent(couponId, ghost, customerId, DISCOUNT))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("coupon_redemptions_order_id_fkey");
  }

  @Test
  @DisplayName("ck_coupon_redemption_discount_non_neg — discount âm bị DB chặn")
  void rejectsNegativeDiscount() {
    assertThatThrownBy(
            () -> repository.insertIfAbsent(couponId, orderId, customerId, new BigDecimal("-1.00")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_coupon_redemption_discount_non_neg");
  }

  @Test
  @DisplayName("deleteByCouponAndOrder — xoá đúng 1 row, gọi lại trả 0")
  void deleteReturnsAffectedRowCount() {
    repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);
    em.clear();

    assertThat(repository.deleteByCouponAndOrder(couponId, orderId)).isEqualTo(1);
    assertThat(repository.deleteByCouponAndOrder(couponId, orderId)).isZero();
  }

  @Test
  @DisplayName("findByCustomerIdOrderByRedeemedAtDesc — mới nhất lên đầu")
  void historyIsNewestFirst() {
    repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);
    repository.insertIfAbsent(couponId, otherOrderId, customerId, new BigDecimal("100000"));
    em.clear();

    List<CouponRedemption> history = repository.findByCustomerIdOrderByRedeemedAtDesc(customerId);

    assertThat(history).hasSize(2);
    assertThat(history.get(0).getRedeemedAt()).isAfterOrEqualTo(history.get(1).getRedeemedAt());
  }

  /**
   * Lưu ý cách flush: phải đi qua {@code couponRepository.flush()} chứ không phải {@code
   * em.flush()}. Spring chỉ dịch exception của JPA sang {@code DataAccessException} ở ranh giới
   * repository proxy; gọi thẳng EntityManager thì lỗi nổi lên nguyên dạng {@code
   * org.hibernate.exception.ConstraintViolationException}.
   */
  @Test
  @DisplayName("FK coupon_id RESTRICT — không xoá được coupon còn redemption")
  void couponWithRedemptionCannotBeDeleted() {
    repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);
    em.clear();

    assertThatThrownBy(
            () -> {
              couponRepository.deleteById(couponId);
              couponRepository.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("coupon_redemptions_coupon_id_fkey");
  }

  /**
   * Chiều ngược của FK order_id (V12): redemption là audit trail nên order còn redemption thì không
   * xoá được. {@code JdbcTemplate} tự dịch SQLException sang {@code DataAccessException}, không cần
   * flush qua repository như test trên.
   */
  @Test
  @DisplayName("FK order_id RESTRICT (V12) — không xoá được order còn redemption")
  void orderWithRedemptionCannotBeDeleted() {
    repository.insertIfAbsent(couponId, orderId, customerId, DISCOUNT);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM app.orders WHERE id = ?", orderId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("coupon_redemptions_order_id_fkey");
  }
}
