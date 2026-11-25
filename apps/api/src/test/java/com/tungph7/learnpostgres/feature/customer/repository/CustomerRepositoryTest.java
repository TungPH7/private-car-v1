package com.tungph7.learnpostgres.feature.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;
import com.tungph7.learnpostgres.feature.customer.repository.projection.CustomerSearchRow;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class) // @DataJpaTest không tự load auditing → import tường minh
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CustomerRepositoryTest extends AbstractContainerBaseTest {

  @Autowired CustomerRepository repository;
  @Autowired JdbcTemplate jdbc;

  // -----------------------------------------------------------------
  // citext
  // -----------------------------------------------------------------

  @Test
  @DisplayName("findByEmail — citext so sánh không phân biệt hoa thường")
  void findByEmailCitext() {
    repository.saveAndFlush(new Customer("CITX@example.com", null, "C", "X"));

    assertThat(repository.findByEmail("citx@example.com")).isPresent();
    assertThat(repository.findByEmail("CITX@EXAMPLE.COM")).isPresent();
    assertThat(repository.findByEmail("Citx@Example.Com")).isPresent();
    assertThat(repository.findByEmail("other@example.com")).isEmpty();
  }

  @Test
  @DisplayName("email đọc ra giữ nguyên case đã ghi (citext chỉ đổi cách SO SÁNH)")
  void citextPreservesStoredCase() {
    repository.saveAndFlush(new Customer("MiXeD@Example.com", null, "M", "X"));

    assertThat(repository.findByEmail("mixed@example.com"))
        .get()
        .extracting(Customer::getEmail)
        .isEqualTo("MiXeD@Example.com");
  }

  @Test
  @DisplayName("email trùng khác case → DataIntegrityViolationException")
  void duplicateEmail() {
    repository.saveAndFlush(new Customer("dup@x.com", null, "A", "B"));

    Customer dup = new Customer("DUP@X.COM", null, "C", "D");
    assertThatThrownBy(() -> repository.saveAndFlush(dup))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("customers_email_unique");
  }

  // -----------------------------------------------------------------
  // CHECK constraint
  // -----------------------------------------------------------------

  @Test
  @DisplayName("email sai format → CHECK ck_customers_email_format")
  void invalidEmailFormat() {
    Customer bad = new Customer("not-an-email", null, "A", "B");

    assertThatThrownBy(() -> repository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_customers_email_format");
  }

  @Test
  @DisplayName("phone không phải E.164 → CHECK ck_customers_phone_e164")
  void invalidPhone() {
    Customer bad = new Customer("a@x.com", "0901234567", "A", "B");

    assertThatThrownBy(() -> repository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_customers_phone_e164");
  }

  // -----------------------------------------------------------------
  // Partial unique phone (chỉ ACTIVE)
  // -----------------------------------------------------------------

  @Test
  @DisplayName("partial unique phone — customer DELETED nhả số cho người mới")
  void phoneReuseAfterDelete() {
    Customer first = repository.saveAndFlush(new Customer("a1@x.com", "+84909999999", "A", "B"));
    first.setStatus(CustomerStatus.DELETED);
    repository.saveAndFlush(first);

    // ACTIVE mới cùng phone — OK vì partial unique chỉ soi row ACTIVE
    repository.saveAndFlush(new Customer("a2@x.com", "+84909999999", "C", "D"));

    assertThat(repository.findByPhoneActive("+84909999999"))
        .get()
        .extracting(Customer::getEmail)
        .isEqualTo("a2@x.com");
  }

  @Test
  @DisplayName("partial unique phone — 2 customer ACTIVE cùng số → fail")
  void phoneDuplicateActive() {
    repository.saveAndFlush(new Customer("a3@x.com", "+84908888888", "A", "B"));

    Customer dup = new Customer("a4@x.com", "+84908888888", "C", "D");
    assertThatThrownBy(() -> repository.saveAndFlush(dup))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_customers_phone_active");
  }

  @Test
  @DisplayName("findByPhoneActive bỏ qua customer không ACTIVE")
  void findByPhoneActiveIgnoresNonActive() {
    Customer c = repository.saveAndFlush(new Customer("a5@x.com", "+84907777777", "A", "B"));
    c.setStatus(CustomerStatus.SUSPENDED);
    repository.saveAndFlush(c);

    assertThat(repository.findByPhoneActive("+84907777777")).isEmpty();
  }

  // -----------------------------------------------------------------
  // Dynamic search + COUNT(*) OVER ()
  // -----------------------------------------------------------------

  @Test
  @DisplayName("searchCustomers — lọc q khớp cả email lẫn họ tên")
  void searchWithFilter() {
    repository.saveAndFlush(new Customer("search1@x.com", null, "Alice", "Smith"));
    repository.saveAndFlush(new Customer("search2@x.com", null, "Bob", "Smith"));
    repository.saveAndFlush(new Customer("xxxx@x.com", null, "Charlie", "Brown"));

    List<CustomerSearchRow> rows =
        repository.searchCustomers("smith", "ACTIVE", null, null, null, 20, 0);

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(CustomerSearchRow::getLastName).containsOnly("Smith");
    assertThat(rows.get(0).getTotalCount()).isEqualTo(2L);
  }

  @Test
  @DisplayName("searchCustomers — mọi filter null thì không lọc gì (không nổ type inference)")
  void searchAllFiltersNull() {
    repository.saveAndFlush(new Customer("null1@x.com", null, "N", "One"));
    repository.saveAndFlush(new Customer("null2@x.com", null, "N", "Two"));

    List<CustomerSearchRow> rows = repository.searchCustomers(null, null, null, null, null, 20, 0);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getTotalCount()).isEqualTo(2L);
  }

  @Test
  @DisplayName("searchCustomers — email trả về String, không phải PGobject của citext")
  void searchReturnsPlainStringEmail() {
    repository.saveAndFlush(new Customer("Proj@X.com", null, "P", "J"));

    List<CustomerSearchRow> rows = repository.searchCustomers("proj", null, null, null, null, 5, 0);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getEmail()).isEqualTo("Proj@X.com");
  }

  @Test
  @DisplayName("searchCustomers — lọc theo status")
  void searchByStatus() {
    Customer suspended = repository.saveAndFlush(new Customer("st1@x.com", null, "Sus", "Pended"));
    suspended.setStatus(CustomerStatus.SUSPENDED);
    repository.saveAndFlush(suspended);
    repository.saveAndFlush(new Customer("st2@x.com", null, "Act", "Ive"));

    assertThat(repository.searchCustomers(null, "SUSPENDED", null, null, null, 20, 0)).hasSize(1);
    assertThat(repository.searchCustomers(null, "ACTIVE", null, null, null, 20, 0)).hasSize(1);
  }

  @Test
  @DisplayName("searchCustomers — LIMIT/OFFSET phân trang, COUNT(*) OVER () giữ nguyên total")
  void searchPaginationCount() {
    for (int i = 0; i < 15; i++) {
      repository.saveAndFlush(new Customer("p" + i + "@x.com", null, "P" + i, "X"));
    }

    List<CustomerSearchRow> page0 = repository.searchCustomers(null, null, null, null, null, 5, 0);
    List<CustomerSearchRow> page2 = repository.searchCustomers(null, null, null, null, null, 5, 10);

    assertThat(page0).hasSize(5);
    assertThat(page2).hasSize(5);
    assertThat(page0.get(0).getTotalCount()).isEqualTo(15L);
    assertThat(page2.get(0).getTotalCount())
        .as("COUNT(*) OVER () là total sau filter, không bị LIMIT cắt")
        .isEqualTo(15L);
    assertThat(page0)
        .extracting(CustomerSearchRow::getId)
        .doesNotContainAnyElementsOf(page2.stream().map(CustomerSearchRow::getId).toList());
  }

  // -----------------------------------------------------------------
  // Profile bundle
  // -----------------------------------------------------------------

  @Test
  @DisplayName("findProfile — customer chưa có đơn: 0 / 0 / null")
  void findProfileWithoutOrders() {
    Customer c = repository.saveAndFlush(new Customer("prof@x.com", null, "Pro", "File"));

    assertThat(repository.findProfile(c.getId()))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getEmail()).isEqualTo("prof@x.com");
              assertThat(row.getOrdersCount()).isZero();
              assertThat(row.getTotalSpent()).isEqualByComparingTo("0");
              assertThat(row.getLastOrderAt()).isNull();
            });
  }

  /**
   * Đơn không hợp lệ (PENDING / CANCELLED / EXPIRED) cố ý MỚI HƠN và LỚN HƠN đơn hợp lệ: nếu chúng
   * lọt vào tập tính, cả 3 chỉ số đều sai lệch rõ ràng.
   */
  @Test
  @DisplayName("findProfile — chỉ tính đơn PAID / SHIPPED / DELIVERED, cả 3 chỉ số cùng một tập")
  void findProfileCountsOnlyPaidOrders() {
    Customer c = repository.saveAndFlush(new Customer("spent@x.com", null, "Spent", "Probe"));
    UUID address = OrderFixtures.insertShippingAddress(jdbc, c.getId());
    order(address, "DELIVERED", "300.00", "3 days");
    order(address, "PAID", "100.00", "2 days");
    UUID latestPaid = order(address, "SHIPPED", "200.00", "1 day");
    order(address, "CANCELLED", "5000.00", "2 hours");
    order(address, "EXPIRED", "7000.00", "1 hour");
    order(address, "PENDING", "1000.00", "1 minute");

    Instant latestPaidAt =
        jdbc.queryForObject(
                "SELECT created_at FROM app.orders WHERE id = ?", OffsetDateTime.class, latestPaid)
            .toInstant();

    assertThat(repository.findProfile(c.getId()))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getOrdersCount()).isEqualTo(3);
              assertThat(row.getTotalSpent()).isEqualByComparingTo("600.00");
              assertThat(row.getLastOrderAt()).isEqualTo(latestPaidAt);
            });
  }

  /**
   * Canary cho lỗi đặt điều kiện status ở {@code WHERE} thay vì {@code ON}: khi đó LEFT JOIN không
   * còn giữ customer lại, query ra 0 row và endpoint profile trả 404.
   */
  @Test
  @DisplayName("findProfile — chỉ có đơn chưa thanh toán: vẫn trả row, 0 / 0 / null")
  void findProfileWithOnlyUnpaidOrders() {
    Customer c = repository.saveAndFlush(new Customer("unpaid@x.com", null, "Un", "Paid"));
    UUID address = OrderFixtures.insertShippingAddress(jdbc, c.getId());
    order(address, "PENDING", "100.00", "1 hour");
    order(address, "EXPIRED", "200.00", "2 hours");
    order(address, "CANCELLED", "300.00", "3 hours");

    assertThat(repository.findProfile(c.getId()))
        .get()
        .satisfies(
            row -> {
              assertThat(row.getOrdersCount()).isZero();
              assertThat(row.getTotalSpent()).isEqualByComparingTo("0");
              assertThat(row.getLastOrderAt()).isNull();
            });
  }

  /**
   * Order với status / tiền / created_at tuỳ ý. Đi thẳng SQL: entity {@code Order} cố ý không cho
   * set status (chỉ {@code transitionStatus} được đổi), còn ở đây chỉ cần dữ liệu sẵn cho query
   * đọc. {@code cancelled_at} / {@code paid_at} set theo đúng 2 CHECK trạng thái ↔ timestamp của
   * V12.
   */
  private UUID order(UUID addressId, String status, String total, String age) {
    UUID id = OrderFixtures.insertOrder(jdbc, addressId);
    jdbc.update(
        """
        UPDATE app.orders
        SET status       = ?,
            subtotal     = ?,
            total        = ?,
            paid_at      = CASE WHEN ? IN ('PAID', 'SHIPPED', 'DELIVERED') THEN now() END,
            cancelled_at = CASE WHEN ? IN ('CANCELLED', 'EXPIRED') THEN now() END,
            created_at   = now() - CAST(? AS interval)
        WHERE id = ?
        """,
        status,
        new BigDecimal(total),
        new BigDecimal(total),
        status,
        status,
        age,
        id);
    return id;
  }
}
