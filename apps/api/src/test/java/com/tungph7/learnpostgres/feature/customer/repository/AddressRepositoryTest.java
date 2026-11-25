package com.tungph7.learnpostgres.feature.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.test.context.TestPropertySource;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class AddressRepositoryTest extends AbstractContainerBaseTest {

  @Autowired CustomerRepository customerRepo;
  @Autowired AddressRepository addressRepo;
  @Autowired EntityManager em;

  // -----------------------------------------------------------------
  // Partial unique index uq_addresses_default_per_type
  // -----------------------------------------------------------------

  @Test
  @DisplayName("partial unique — 1 default cho mỗi (customer, type) còn sống")
  void partialUniqueDefault() {
    Customer c = customerRepo.saveAndFlush(new Customer("p1@x.com", null, "A", "B"));

    addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));

    Address second = defaultAddress(c, AddressType.SHIPPING, "2 St");
    assertThatThrownBy(() -> addressRepo.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_addresses_default_per_type");
  }

  @Test
  @DisplayName("partial unique — khác type thì mỗi type được 1 default")
  void differentTypeOk() {
    Customer c = customerRepo.saveAndFlush(new Customer("p2@x.com", null, "A", "B"));

    addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));
    addressRepo.saveAndFlush(defaultAddress(c, AddressType.BILLING, "HQ"));

    assertThat(
            addressRepo.findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(
                c.getId(), AddressType.SHIPPING))
        .isPresent();
    assertThat(
            addressRepo.findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(
                c.getId(), AddressType.BILLING))
        .isPresent();
  }

  @Test
  @DisplayName("partial unique — row đã xoá mềm không nằm trong index")
  void deletedAddressNotInIndex() {
    Customer c = customerRepo.saveAndFlush(new Customer("p3@x.com", null, "A", "B"));

    Address first = addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));
    first.setDeleted(true);
    first.setDefault(false); // service luôn nhả cờ trước khi xoá mềm
    addressRepo.saveAndFlush(first);

    // default mới cùng (customer, SHIPPING) — không đụng row đã xoá
    addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "2 St"));

    assertThat(
            addressRepo.findByCustomerIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(c.getId()))
        .hasSize(1)
        .allSatisfy(a -> assertThat(a.isDefault()).isTrue());
  }

  // -----------------------------------------------------------------
  // swapDefault
  // -----------------------------------------------------------------

  @Test
  @DisplayName("swapDefault — nhả cờ cũ, set cờ mới, trả 1")
  void swapDefaultMovesFlag() {
    Customer c = customerRepo.saveAndFlush(new Customer("p4@x.com", null, "A", "B"));
    Address a1 = addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));
    Address a2 = addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "2 St"));

    int updated = addressRepo.swapDefault(c.getId(), a2.getId());

    assertThat(updated).isEqualTo(1);
    assertThat(addressRepo.findById(a2.getId())).get().returns(true, Address::isDefault);
    assertThat(addressRepo.findById(a1.getId())).get().returns(false, Address::isDefault);
  }

  /**
   * Regression: bản cũ gộp thành 1 UPDATE `SET is_default = (id = :new)` nên khi row target nằm
   * TRƯỚC row đang default trong heap, Postgres set target = TRUE lúc row cũ vẫn TRUE → vi phạm
   * partial unique index (index check theo từng row, không hoãn tới cuối statement).
   *
   * <p>Kịch bản này chính là "set default về địa chỉ cũ hơn" — chèn a1 (default) trước, a2 sau, rồi
   * swap NGƯỢC về a1.
   */
  @Test
  @DisplayName("swapDefault — set ngược về address cũ hơn cũng phải OK (regression ctid)")
  void swapDefaultBackToOlderRow() {
    Customer c = customerRepo.saveAndFlush(new Customer("p5@x.com", null, "A", "B"));
    Address older = addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));
    Address newer = addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "2 St"));

    addressRepo.swapDefault(c.getId(), newer.getId());
    int updated = addressRepo.swapDefault(c.getId(), older.getId());

    assertThat(updated).isEqualTo(1);
    assertThat(addressRepo.findById(older.getId())).get().returns(true, Address::isDefault);
    assertThat(addressRepo.findById(newer.getId())).get().returns(false, Address::isDefault);
  }

  @Test
  @DisplayName("swapDefault — không đụng address của type khác")
  void swapDefaultScopedByType() {
    Customer c = customerRepo.saveAndFlush(new Customer("p6@x.com", null, "A", "B"));
    Address billing = addressRepo.saveAndFlush(defaultAddress(c, AddressType.BILLING, "HQ"));
    addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));
    Address ship2 = addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "2 St"));

    addressRepo.swapDefault(c.getId(), ship2.getId());

    assertThat(addressRepo.findById(billing.getId()))
        .get()
        .as("BILLING nằm ngoài scope swap")
        .returns(true, Address::isDefault);
  }

  @Test
  @DisplayName("swapDefault — id không tồn tại → 0 row, không side effect")
  void swapDefaultUnknownId() {
    Customer c = customerRepo.saveAndFlush(new Customer("p7@x.com", null, "A", "B"));
    Address a1 = addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "1 St"));

    int updated = addressRepo.swapDefault(c.getId(), UUID.randomUUID());

    assertThat(updated).isZero();
    assertThat(addressRepo.findById(a1.getId())).get().returns(true, Address::isDefault);
  }

  @Test
  @DisplayName("swapDefault — address của customer khác → 0 row (guard ở SQL)")
  void swapDefaultCrossCustomer() {
    Customer owner = customerRepo.saveAndFlush(new Customer("p8@x.com", null, "A", "B"));
    Customer other = customerRepo.saveAndFlush(new Customer("p9@x.com", null, "C", "D"));
    Address a = addressRepo.saveAndFlush(plainAddress(owner, AddressType.SHIPPING, "1 St"));

    assertThat(addressRepo.swapDefault(other.getId(), a.getId())).isZero();
    assertThat(addressRepo.findById(a.getId())).get().returns(false, Address::isDefault);
  }

  // -----------------------------------------------------------------
  // Derived finders + FK
  // -----------------------------------------------------------------

  @Test
  @DisplayName("finder sắp default lên đầu và bỏ qua row đã xoá mềm")
  void finderOrdersDefaultFirstAndSkipsDeleted() {
    Customer c = customerRepo.saveAndFlush(new Customer("p10@x.com", null, "A", "B"));
    addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "1 St"));
    Address def = addressRepo.saveAndFlush(defaultAddress(c, AddressType.SHIPPING, "2 St"));
    Address gone = addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "3 St"));
    gone.setDeleted(true);
    addressRepo.saveAndFlush(gone);

    List<Address> found =
        addressRepo.findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
            c.getId(), AddressType.SHIPPING);

    assertThat(found).hasSize(2);
    assertThat(found.get(0).getId()).isEqualTo(def.getId());
  }

  @Test
  @DisplayName("existsByIdAndCustomerId — chỉ true khi đúng chủ sở hữu")
  void existsByIdAndCustomerId() {
    Customer owner = customerRepo.saveAndFlush(new Customer("p11@x.com", null, "A", "B"));
    Customer other = customerRepo.saveAndFlush(new Customer("p12@x.com", null, "C", "D"));
    Address a = addressRepo.saveAndFlush(plainAddress(owner, AddressType.SHIPPING, "1 St"));

    assertThat(addressRepo.existsByIdAndCustomerId(a.getId(), owner.getId())).isTrue();
    assertThat(addressRepo.existsByIdAndCustomerId(a.getId(), other.getId())).isFalse();
  }

  @Test
  @DisplayName("country_code lưu CHAR(2) — đọc lại không dính space thừa")
  void countryCodeCharTwo() {
    Customer c = customerRepo.saveAndFlush(new Customer("p13@x.com", null, "A", "B"));
    Address a = addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "1 St"));

    assertThat(addressRepo.findById(a.getId())).get().returns("VN", Address::getCountryCode);
  }

  /**
   * CASCADE ở đây là của FK trong DB (`ON DELETE CASCADE` ở V7), KHÔNG phải cascade của JPA —
   * `Customer` cố tình không map `@OneToMany List<Address>`.
   *
   * <p>Vì vậy phải `em.clear()` trước khi xoá: nếu Address còn nằm trong persistence context, lúc
   * flush Hibernate thấy nó trỏ tới một Customer vừa bị remove và ném
   * `TransientPropertyValueException` — lỗi của tầng JPA, chưa kịp xuống tới DB.
   */
  @Test
  @DisplayName("FK ON DELETE CASCADE — xoá customer xoá luôn address")
  void cascadeDelete() {
    Customer c = customerRepo.saveAndFlush(new Customer("p14@x.com", null, "A", "B"));
    UUID addressId =
        addressRepo.saveAndFlush(plainAddress(c, AddressType.SHIPPING, "1 St")).getId();
    em.clear();

    customerRepo.delete(customerRepo.findById(c.getId()).orElseThrow());
    customerRepo.flush();
    em.clear(); // DELETE cascade chạy trong DB, context không tự biết

    assertThat(addressRepo.findById(addressId)).isEmpty();
  }

  // -----------------------------------------------------------------

  private static Address plainAddress(Customer c, AddressType type, String line1) {
    return new Address(c, type, "John Doe", "+84901234567", line1, "HCM", "VN");
  }

  private static Address defaultAddress(Customer c, AddressType type, String line1) {
    Address a = plainAddress(c, type, line1);
    a.setDefault(true);
    return a;
  }
}
