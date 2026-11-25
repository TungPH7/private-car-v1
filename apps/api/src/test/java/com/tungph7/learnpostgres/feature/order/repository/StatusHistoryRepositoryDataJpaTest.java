package com.tungph7.learnpostgres.feature.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;

/** Mapping của {@link OrderStatusHistory} — enum nullable ở {@code from_status}. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class StatusHistoryRepositoryDataJpaTest extends AbstractContainerBaseTest {

  @Autowired StatusHistoryRepository repository;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;

  private UUID orderId;

  @BeforeEach
  void setUp() {
    Customer customer = new Customer("order.history@example.com", null, "History", "Probe");
    em.persist(customer);
    em.flush();
    orderId =
        OrderFixtures.insertOrder(
            jdbc, OrderFixtures.insertShippingAddress(jdbc, customer.getId()));
  }

  @Test
  @DisplayName("NULL → PENDING rồi PENDING → PAID — đọc lại đúng thứ tự, from NULL map ra null")
  void historyInChronologicalOrder() {
    repository.saveAndFlush(
        new OrderStatusHistory(orderId, null, OrderStatus.PENDING, "Order placed", "system"));
    repository.saveAndFlush(
        new OrderStatusHistory(
            orderId, OrderStatus.PENDING, OrderStatus.PAID, "Payment confirmed", "system"));
    em.clear();

    List<OrderStatusHistory> history = repository.findByOrderIdOrderByChangedAtAsc(orderId);

    assertThat(history).hasSize(2);
    OrderStatusHistory initial = history.get(0);
    assertThat(initial.getId()).isNotNull();
    assertThat(initial.getOrderId()).isEqualTo(orderId);
    assertThat(initial.getFromStatus()).isNull();
    assertThat(initial.getToStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(initial.getReason()).isEqualTo("Order placed");
    assertThat(initial.getActor()).isEqualTo("system");
    assertThat(initial.getChangedAt()).isNotNull();
    assertThat(history.get(1).getFromStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(history.get(1).getToStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(history.get(1).getChangedAt()).isAfterOrEqualTo(initial.getChangedAt());
  }

  @Test
  @DisplayName("enum lưu dạng tên (EnumType.STRING), khớp CHECK ck_osh_from/to_status")
  void enumIsStoredByName() {
    repository.saveAndFlush(
        new OrderStatusHistory(orderId, OrderStatus.PAID, OrderStatus.SHIPPED, null, "ops"));

    assertThat(
            jdbc.queryForObject(
                "SELECT from_status || '>' || to_status FROM app.order_status_history"
                    + " WHERE order_id = ?",
                String.class,
                orderId))
        .isEqualTo("PAID>SHIPPED");
  }
}
