package com.tungph7.learnpostgres.feature.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;
import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.repository.projection.ProductSnapshotRow;
import com.tungph7.learnpostgres.feature.product.domain.Product;

/**
 * Mapping của {@link OrderItem} — trọng tâm là GENERATED STORED column {@code line_total} — và
 * query đọc snapshot product cho placeOrder.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class OrderItemRepositoryDataJpaTest extends AbstractContainerBaseTest {

  @Autowired OrderItemRepository repository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  private UUID orderId;
  private Product first;
  private Product second;

  @BeforeEach
  void setUp() {
    Customer customer = new Customer("order.item@example.com", null, "Item", "Probe");
    em.persist(customer);
    first = new Product("Item A", "order-item-a", new BigDecimal("100.00"), null);
    second = new Product("Item B", "order-item-b", new BigDecimal("12.50"), null);
    em.persist(first);
    em.persist(second);
    em.flush();
    orderId =
        OrderFixtures.insertOrder(
            jdbc, OrderFixtures.insertShippingAddress(jdbc, customer.getId()));
  }

  /**
   * id sinh ở Java ({@code @PrePersist}) nên {@code save()} không cần INSERT sớm để lấy id — INSERT
   * chỉ chạy lúc flush, và {@code line_total} cũng chỉ có từ lúc đó.
   */
  @Test
  @DisplayName("line_total có ngay sau flush — @Generated đọc lại giá trị DB vừa tính")
  void lineTotalIsReadBackAfterInsert() {
    OrderItem item =
        new OrderItem(orderId, first.getId(), "Item A", "SKU-A", 3, new BigDecimal("100.00"));

    repository.save(item);
    assertThat(item.getLineTotal()).isNull(); // chưa flush → chưa INSERT

    repository.flush();
    assertThat(item.getLineTotal()).isEqualByComparingTo("300");
  }

  @Test
  @DisplayName("save + reload — snapshot product, line_total khớp quantity * unit_price")
  void saveAndReload() {
    OrderItem saved =
        repository.saveAndFlush(
            new OrderItem(orderId, second.getId(), "Item B", null, 4, new BigDecimal("12.50")));
    em.clear();

    OrderItem found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getOrderId()).isEqualTo(orderId);
    assertThat(found.getProductId()).isEqualTo(second.getId());
    assertThat(found.getProductName()).isEqualTo("Item B");
    assertThat(found.getProductSku()).isNull();
    assertThat(found.getQuantity()).isEqualTo(4);
    assertThat(found.getUnitPrice()).isEqualByComparingTo("12.50");
    assertThat(found.getLineTotal()).isEqualByComparingTo("50.00");
    assertThat(found.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("snapshot độc lập với product — đổi tên/giá product sau đó, item giữ nguyên")
  void snapshotSurvivesProductChange() {
    OrderItem saved =
        repository.saveAndFlush(
            new OrderItem(orderId, first.getId(), "Item A", null, 1, new BigDecimal("100.00")));
    jdbc.update(
        "UPDATE app.products SET name = 'Item A (v2)', price = 999 WHERE id = ?", first.getId());
    em.clear();

    OrderItem found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getProductName()).isEqualTo("Item A");
    assertThat(found.getUnitPrice()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("findByOrderIdOrderByCreatedAtAsc — theo thứ tự thêm vào")
  void itemsOfOrderInInsertionOrder() {
    OrderItem a =
        repository.saveAndFlush(
            new OrderItem(orderId, first.getId(), "Item A", null, 1, new BigDecimal("100.00")));
    OrderItem b =
        repository.saveAndFlush(
            new OrderItem(orderId, second.getId(), "Item B", null, 1, new BigDecimal("12.50")));
    em.clear();

    assertThat(repository.findByOrderIdOrderByCreatedAtAsc(orderId))
        .extracting(OrderItem::getId)
        .containsExactly(a.getId(), b.getId());
  }

  // -----------------------------------------------------------------
  // findProductSnapshots — đọc app.products cho placeOrder (bài 03)
  // -----------------------------------------------------------------

  /**
   * {@code sku} và {@code status} là 2 cột entity {@code Product} không map — lý do query này tồn
   * tại. Product mới qua entity lấy {@code status} từ DB DEFAULT ({@code DRAFT}, V5).
   */
  @Test
  @DisplayName("findProductSnapshots — name, sku, price, status của nhiều product trong 1 query")
  void productSnapshotsIncludeColumnsTheEntityDoesNotMap() {
    jdbc.update(
        "UPDATE app.products SET sku = 'SKU-A', status = 'ACTIVE' WHERE id = ?", first.getId());

    var rows = repository.findProductSnapshots(List.of(first.getId(), second.getId()));

    assertThat(rows).hasSize(2);
    var a = rows.stream().filter(r -> r.getId().equals(first.getId())).findFirst().orElseThrow();
    var b = rows.stream().filter(r -> r.getId().equals(second.getId())).findFirst().orElseThrow();
    assertThat(a.getName()).isEqualTo("Item A");
    assertThat(a.getSku()).isEqualTo("SKU-A");
    assertThat(a.getPrice()).isEqualByComparingTo("100.00");
    assertThat(a.getStatus()).isEqualTo("ACTIVE");
    assertThat(b.getSku()).isNull();
    assertThat(b.getStatus()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("findProductSnapshots — id không tồn tại thì đơn giản là không có row")
  void productSnapshotsSkipUnknownIds() {
    assertThat(repository.findProductSnapshots(List.of(first.getId(), UUID.randomUUID())))
        .extracting(ProductSnapshotRow::getId)
        .containsExactly(first.getId());
  }
}
