package com.tungph7.learnpostgres.feature.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.domain.ShippingSnapshot;
import com.tungph7.learnpostgres.feature.order.repository.projection.OrderDetailRow;
import com.tungph7.learnpostgres.feature.product.domain.Product;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice test cho {@link OrderRepository} trên Postgres thật, {@code ddl-auto: validate}.
 *
 * <p>Những thứ chỉ DB thật chứng minh được: mapping khớp V12 (kể cả {@code char(2)} và các cột
 * read-only), các native query (sequence, atomic transition, ứng viên expire + khóa từng đơn) và
 * {@code json_agg} của detail query.
 *
 * <p>Hành vi SKIP LOCKED — bỏ qua đơn đang bị transaction KHÁC khóa — cần 2 transaction thật đã
 * commit dữ liệu, nên nằm ở {@code OrderServiceIntegrationTest}; ở đây mọi thứ chạy trong một
 * transaction rollback cuối test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class OrderRepositoryDataJpaTest extends AbstractContainerBaseTest {

  @Autowired OrderRepository repository;
  @Autowired OrderItemRepository itemRepository;
  @Autowired StatusHistoryRepository historyRepository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  private Customer customer;
  private Address address;
  private Product product;

  @BeforeEach
  void setUp() {
    customer = new Customer("order.repo@example.com", null, "Order", "Probe");
    em.persist(customer);
    address =
        new Address(
            customer, AddressType.SHIPPING, "Order Probe", "+84901234567", "1 Le Loi", "HCM", "VN");
    address.setLine2("Tầng 3");
    em.persist(address);
    product = new Product("Probe Item", "order-probe-item", new BigDecimal("100.00"), null);
    em.persist(product);
    em.flush();
  }

  private Order newOrder(String orderNumber, UUID couponId, String discount) {
    return new Order(
        orderNumber,
        customer.getId(),
        address.getId(),
        ShippingSnapshot.of(address),
        new BigDecimal("300.00"),
        new BigDecimal(discount),
        new BigDecimal("30.00"),
        couponId);
  }

  private Order savedOrder(String orderNumber) {
    return repository.saveAndFlush(newOrder(orderNumber, null, "0"));
  }

  private String statusInDb(UUID orderId) {
    return jdbc.queryForObject("SELECT status FROM app.orders WHERE id = ?", String.class, orderId);
  }

  // -----------------------------------------------------------------
  // Mapping
  // -----------------------------------------------------------------

  @Test
  @DisplayName("save + reload — mapping khớp V12, status do DB DEFAULT điền")
  void saveAndReload() {
    Order saved = savedOrder("ORD-T-SAVE");
    em.clear();

    Order found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getOrderNumber()).isEqualTo("ORD-T-SAVE");
    assertThat(found.getCustomerId()).isEqualTo(customer.getId());
    assertThat(found.getShippingAddressId()).isEqualTo(address.getId());
    assertThat(found.getCouponId()).isNull();
    assertThat(found.getShippingRecipient()).isEqualTo("Order Probe");
    assertThat(found.getShippingPhone()).isEqualTo("+84901234567");
    assertThat(found.getShippingAddressLine1()).isEqualTo("1 Le Loi");
    assertThat(found.getShippingAddressLine2()).isEqualTo("Tầng 3");
    assertThat(found.getShippingCity()).isEqualTo("HCM");
    assertThat(found.getShippingCountry()).isEqualTo("VN");
    assertThat(found.getSubtotal()).isEqualByComparingTo("300");
    assertThat(found.getDiscountAmount()).isEqualByComparingTo("0");
    assertThat(found.getShippingFee()).isEqualByComparingTo("30");
    assertThat(found.getTotal()).isEqualByComparingTo("330");
    assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(found.getPaidAt()).isNull();
    assertThat(found.getCancelledAt()).isNull();
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getVersion()).isZero();
  }

  @Test
  @DisplayName("findByOrderNumber — tìm theo UNIQUE order_number")
  void findByOrderNumber() {
    Order saved = savedOrder("ORD-T-FIND");

    assertThat(repository.findByOrderNumber("ORD-T-FIND")).get().isEqualTo(saved);
    assertThat(repository.findByOrderNumber("ORD-T-NONE")).isEmpty();
  }

  @Test
  @DisplayName("findByCustomerId... — mới nhất trước, có đếm tổng cho paging")
  void customerOrdersNewestFirst() {
    Order oldest = savedOrder("ORD-T-P1");
    Order middle = savedOrder("ORD-T-P2");
    Order newest = savedOrder("ORD-T-P3");
    // created_at do auditing điền gần như cùng lúc → đặt tay để thứ tự không phụ thuộc đồng hồ.
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '2 hour' WHERE id = ?",
        oldest.getId());
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '1 hour' WHERE id = ?",
        middle.getId());
    em.clear();

    Page<Order> page =
        repository.findByCustomerIdOrderByCreatedAtDesc(customer.getId(), PageRequest.of(0, 2));

    assertThat(page.getContent())
        .extracting(Order::getId)
        .containsExactly(newest.getId(), middle.getId());
    assertThat(page.getTotalElements()).isEqualTo(3);
  }

  // -----------------------------------------------------------------
  // nextOrderNumberSequence
  // -----------------------------------------------------------------

  /**
   * Chỉ kiểm phần DB lo: sequence cấp số mới, tăng dần. Ghép thành {@code ORD-YYYYMM-NNNNNN} là
   * việc của {@code OrderNumberGenerator} — unit test riêng với {@code Clock} cố định.
   */
  @Test
  @DisplayName("nextOrderNumberSequence — mỗi lần gọi ra số mới, tăng dần")
  void orderNumberSequenceIncreases() {
    long first = repository.nextOrderNumberSequence();
    long second = repository.nextOrderNumberSequence();

    assertThat(first).isPositive();
    assertThat(second).isGreaterThan(first);
  }

  // -----------------------------------------------------------------
  // transitionStatus
  // -----------------------------------------------------------------

  @Test
  @DisplayName("PENDING → PAID — 1 row, set paid_at, không đụng timestamp khác")
  void transitionToPaid() {
    Order order = savedOrder("ORD-T-PAID");

    int updated =
        repository.transitionStatus(order.getId(), "PENDING", "PAID", "Payment confirmed");

    assertThat(updated).isOne();
    Order found = repository.findById(order.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(found.getPaidAt()).isNotNull();
    assertThat(found.getShippedAt()).isNull();
    assertThat(found.getCancelledAt()).isNull();
    assertThat(found.getCancelReason()).isNull();
  }

  @Test
  @DisplayName("from không khớp trạng thái hiện tại → 0 row, không đổi gì (guard chống race)")
  void transitionWithStaleFromIsNoop() {
    Order order = savedOrder("ORD-T-RACE");

    int updated = repository.transitionStatus(order.getId(), "PAID", "SHIPPED", null);

    assertThat(updated).isZero();
    assertThat(statusInDb(order.getId())).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("order không tồn tại → 0 row")
  void transitionUnknownOrder() {
    assertThat(repository.transitionStatus(UUID.randomUUID(), "PENDING", "PAID", null)).isZero();
  }

  @Test
  @DisplayName("PAID → CANCELLED — set cancelled_at + cancel_reason, GIỮ paid_at")
  void cancelAfterPaidKeepsPaidAt() {
    Order order = savedOrder("ORD-T-CANCEL");
    repository.transitionStatus(order.getId(), "PENDING", "PAID", null);

    int updated =
        repository.transitionStatus(order.getId(), "PAID", "CANCELLED", "Customer changed mind");

    assertThat(updated).isOne();
    Order found = repository.findById(order.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(found.getCancelledAt()).isNotNull();
    assertThat(found.getCancelReason()).isEqualTo("Customer changed mind");
    assertThat(found.getPaidAt()).isNotNull();
  }

  @Test
  @DisplayName("PENDING → EXPIRED — cũng set cancelled_at (ck_orders_cancelled_at_with_status)")
  void expireSetsCancelledAt() {
    Order order = savedOrder("ORD-T-EXPIRE");

    repository.transitionStatus(order.getId(), "PENDING", "EXPIRED", "Auto-expired");

    Order found = repository.findById(order.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(found.getCancelledAt()).isNotNull();
    assertThat(found.getCancelReason()).isEqualTo("Auto-expired");
    assertThat(found.getPaidAt()).isNull();
  }

  @Test
  @DisplayName("PENDING → SHIPPED → DELIVERED — mỗi bước set đúng timestamp của nó")
  void shipAndDeliverSetTheirTimestamps() {
    Order order = savedOrder("ORD-T-DELIVER");
    repository.transitionStatus(order.getId(), "PENDING", "PAID", null);
    repository.transitionStatus(order.getId(), "PAID", "SHIPPED", null);

    repository.transitionStatus(order.getId(), "SHIPPED", "DELIVERED", null);

    Order found = repository.findById(order.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    assertThat(found.getShippedAt()).isNotNull();
    assertThat(found.getDeliveredAt()).isNotNull();
    assertThat(found.getCancelledAt()).isNull();
  }

  @Test
  @DisplayName("hủy không kèm lý do — reason NULL vẫn bind được")
  void cancelWithoutReason() {
    Order order = savedOrder("ORD-T-NOREASON");

    assertThat(repository.transitionStatus(order.getId(), "PENDING", "CANCELLED", null)).isOne();
    assertThat(repository.findById(order.getId()).orElseThrow().getCancelReason()).isNull();
  }

  /**
   * {@code clearAutomatically = true}: không có nó, {@code findById} trả lại đúng instance đang
   * cache trong persistence context — vẫn mang status PENDING dù DB đã là PAID.
   */
  @Test
  @DisplayName("sau transition, findById đọc trạng thái MỚI chứ không phải instance cũ đang cache")
  void transitionClearsPersistenceContext() {
    Order managed = savedOrder("ORD-T-CLEAR");
    assertThat(em.contains(managed)).isTrue();

    repository.transitionStatus(managed.getId(), "PENDING", "PAID", null);

    assertThat(em.contains(managed)).isFalse();
    assertThat(repository.findById(managed.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
  }

  /**
   * Lý do {@code status} là {@code updatable = false}. Mô phỏng tx khác đổi trạng thái bằng UPDATE
   * native (không qua repository nên persistence context KHÔNG bị clear), rồi entity cũ trong tay
   * được sửa note và flush. Nếu status updatable, câu UPDATE của Hibernate sẽ ghi lại 'PENDING'.
   */
  @Test
  @DisplayName("entity giữ status cũ, flush thay đổi khác → KHÔNG ghi đè trạng thái mới")
  void staleEntityCannotRevertStatus() {
    Order managed = savedOrder("ORD-T-STALE");
    jdbc.update(
        "UPDATE app.orders SET status = 'PAID', paid_at = now() WHERE id = ?", managed.getId());
    assertThat(managed.getStatus()).isEqualTo(OrderStatus.PENDING); // instance cũ

    managed.setInternalNote("gọi lại cho khách");
    repository.flush();

    assertThat(statusInDb(managed.getId())).isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT internal_note FROM app.orders WHERE id = ?", String.class, managed.getId()))
        .isEqualTo("gọi lại cho khách");
  }

  // -----------------------------------------------------------------
  // findExpirationCandidates + lockForExpiration
  // -----------------------------------------------------------------

  @Test
  @DisplayName("ứng viên expire: chỉ PENDING tạo trước cutoff, cũ nhất trước, đúng batch size")
  void expirationCandidates() {
    Order oldest = savedOrder("ORD-T-EXP1");
    Order old = savedOrder("ORD-T-EXP2");
    Order fresh = savedOrder("ORD-T-EXP3");
    Order oldButPaid = savedOrder("ORD-T-EXP4");
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '2 hour' WHERE id = ?",
        oldest.getId());
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '1 hour' WHERE id = ?", old.getId());
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '3 hour' WHERE id = ?",
        oldButPaid.getId());
    repository.transitionStatus(oldButPaid.getId(), "PENDING", "PAID", null);
    Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);

    assertThat(repository.findExpirationCandidates(cutoff, 10))
        .containsExactly(oldest.getId(), old.getId())
        .doesNotContain(fresh.getId(), oldButPaid.getId());
    assertThat(repository.findExpirationCandidates(cutoff, 1)).containsExactly(oldest.getId());
  }

  @Test
  @DisplayName("lockForExpiration — đơn PENDING quá hạn → trả về chính đơn đó (đã khóa)")
  void lockForExpirationReturnsOverdueOrder() {
    Order order = savedOrder("ORD-T-LOCK1");
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '1 hour' WHERE id = ?", order.getId());
    em.clear();

    assertThat(repository.lockForExpiration(order.getId(), Instant.now().minusSeconds(60)))
        .get()
        .satisfies(
            locked -> {
              assertThat(locked.getId()).isEqualTo(order.getId());
              assertThat(locked.getStatus()).isEqualTo(OrderStatus.PENDING);
              assertThat(locked.getOrderNumber()).isEqualTo("ORD-T-LOCK1");
            });
  }

  /**
   * Danh sách ứng viên có thể đã cũ lúc tới lượt khóa — câu khóa phải tự kiểm lại status và cutoff,
   * không tin caller.
   */
  @Test
  @DisplayName("lockForExpiration — chưa quá hạn hoặc không còn PENDING → empty")
  void lockForExpirationRechecksStatusAndCutoff() {
    Order fresh = savedOrder("ORD-T-LOCK2");
    Order paid = savedOrder("ORD-T-LOCK3");
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '1 hour' WHERE id = ?", paid.getId());
    repository.transitionStatus(paid.getId(), "PENDING", "PAID", null);
    Instant cutoff = Instant.now().minusSeconds(60);

    assertThat(repository.lockForExpiration(fresh.getId(), cutoff)).isEmpty();
    assertThat(repository.lockForExpiration(paid.getId(), cutoff)).isEmpty();
    assertThat(repository.lockForExpiration(UUID.randomUUID(), cutoff)).isEmpty();
  }

  // -----------------------------------------------------------------
  // findDetailById
  // -----------------------------------------------------------------

  /**
   * 2 item × 2 history: nếu history cũng LEFT JOIN như items, mỗi item xuất hiện 2 lần trong {@code
   * itemsJson}. Assert {@code hasSize(2)} ở cả hai mảng là canary cho lỗi nhân chéo đó.
   */
  @Test
  @DisplayName("detail — 1 query: header + customer + coupon + items + history dạng JSON")
  void detailWithCouponItemsAndHistory() {
    Coupon coupon =
        new Coupon(
            "DETAIL10",
            CouponType.AMOUNT,
            new BigDecimal("10.00"),
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2027-01-01T00:00:00Z"));
    em.persist(coupon);
    Product other =
        new Product("Probe Item 2", "order-probe-item-2", new BigDecimal("50.00"), null);
    em.persist(other);
    Order order = repository.saveAndFlush(newOrder("ORD-T-DETAIL", coupon.getId(), "10.00"));
    itemRepository.saveAndFlush(
        new OrderItem(
            order.getId(), product.getId(), "Probe Item", "SKU-1", 2, new BigDecimal("100.00")));
    itemRepository.saveAndFlush(
        new OrderItem(
            order.getId(), other.getId(), "Probe Item 2", null, 2, new BigDecimal("50.00")));
    OrderStatusHistory placed =
        historyRepository.saveAndFlush(
            new OrderStatusHistory(
                order.getId(), null, OrderStatus.PENDING, "Order placed", "system"));
    historyRepository.saveAndFlush(
        new OrderStatusHistory(
            order.getId(), OrderStatus.PENDING, OrderStatus.PAID, "Payment confirmed", "system"));

    OrderDetailRow row = repository.findDetailById(order.getId()).orElseThrow();

    assertThat(row.getId()).isEqualTo(order.getId());
    assertThat(row.getOrderNumber()).isEqualTo("ORD-T-DETAIL");
    assertThat(row.getStatus()).isEqualTo("PENDING");
    assertThat(row.getSubtotal()).isEqualByComparingTo("300");
    assertThat(row.getDiscountAmount()).isEqualByComparingTo("10");
    assertThat(row.getShippingFee()).isEqualByComparingTo("30");
    assertThat(row.getTotal()).isEqualByComparingTo("320");
    assertThat(row.getShippingRecipient()).isEqualTo("Order Probe");
    assertThat(row.getShippingPhone()).isEqualTo("+84901234567");
    assertThat(row.getShippingLine1()).isEqualTo("1 Le Loi");
    assertThat(row.getShippingLine2()).isEqualTo("Tầng 3");
    assertThat(row.getShippingCity()).isEqualTo("HCM");
    assertThat(row.getShippingCountry()).isEqualTo("VN");
    assertThat(row.getCreatedAt()).isNotNull();
    assertThat(row.getCustomerId()).isEqualTo(customer.getId());
    assertThat(row.getCustomerEmail()).isEqualTo("order.repo@example.com"); // citext → text
    assertThat(row.getCustomerName()).isEqualTo("Order Probe");
    assertThat(row.getCouponId()).isEqualTo(coupon.getId());
    assertThat(row.getCouponCode()).isEqualTo("DETAIL10");

    JsonMapper json = JsonMapper.builder().build();
    JsonNode items = json.readTree(row.getItemsJson());
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("productName").asString()).isEqualTo("Probe Item");
    assertThat(items.get(0).get("productSku").asString()).isEqualTo("SKU-1");
    assertThat(items.get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(items.get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("200");
    assertThat(items.get(1).get("productName").asString()).isEqualTo("Probe Item 2");
    assertThat(items.get(1).get("productSku").isNull()).isTrue();
    assertThat(items.get(1).get("lineTotal").decimalValue()).isEqualByComparingTo("100");

    JsonNode history = json.readTree(row.getHistoryJson());
    assertThat(history).hasSize(2);
    assertThat(history.get(0).get("fromStatus").isNull()).isTrue();
    assertThat(history.get(0).get("toStatus").asString()).isEqualTo("PENDING");
    assertThat(history.get(0).get("actor").asString()).isEqualTo("system");
    assertThat(history.get(1).get("fromStatus").asString()).isEqualTo("PENDING");
    assertThat(history.get(1).get("toStatus").asString()).isEqualTo("PAID");
    assertThat(history.get(1).get("reason").asString()).isEqualTo("Payment confirmed");
    // changedAt: ISO-8601 kèm offset của session — parse ra đúng instant đã ghi (DB giữ tới µs).
    assertThat(OffsetDateTime.parse(history.get(0).get("changedAt").asString()).toInstant())
        .isCloseTo(placed.getChangedAt(), within(1, ChronoUnit.MICROS));
  }

  @Test
  @DisplayName("detail — đơn chưa có item, không coupon, không history: [] chứ không phải [null]")
  void detailWithoutItemsOrCoupon() {
    Order order = savedOrder("ORD-T-EMPTY");

    OrderDetailRow row = repository.findDetailById(order.getId()).orElseThrow();

    assertThat(row.getItemsJson()).isEqualTo("[]");
    assertThat(row.getHistoryJson()).isEqualTo("[]");
    assertThat(row.getCouponId()).isNull();
    assertThat(row.getCouponCode()).isNull();
  }

  @Test
  @DisplayName("detail — id không tồn tại → empty")
  void detailUnknownOrder() {
    assertThat(repository.findDetailById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("findExpirationCandidates trả UUID thật, dùng lại được cho transitionStatus")
  void expirationIdsAreUsable() {
    Order order = savedOrder("ORD-T-EXPUSE");
    jdbc.update(
        "UPDATE app.orders SET created_at = now() - interval '1 hour' WHERE id = ?", order.getId());

    List<UUID> ids =
        repository.findExpirationCandidates(Instant.now().minus(30, ChronoUnit.MINUTES), 10);

    assertThat(ids).containsExactly(order.getId());
    assertThat(repository.transitionStatus(ids.get(0), "PENDING", "EXPIRED", "Auto-expired"))
        .isOne();
  }
}
