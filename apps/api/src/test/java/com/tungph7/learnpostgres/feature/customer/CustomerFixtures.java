package com.tungph7.learnpostgres.feature.customer;

import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.dto.AddAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.RegisterCustomerRequest;

/**
 * Fixture cho unit test của F02.
 *
 * <p>`BaseEntity.setId` là package-private (chỉ test/migration được đặt id thủ công) nên unit test
 * ở package khác phải đi qua reflection. Slice/integration test KHÔNG cần class này — ở đó id do
 * `@PrePersist` sinh thật.
 */
public final class CustomerFixtures {

  public static final String PHONE = "+84901234567";

  private CustomerFixtures() {}

  /** Customer đã "persisted" — có id để test ownership check. */
  public static Customer customerWithId(UUID id, String email) {
    Customer c = new Customer(email, PHONE, "John", "Doe");
    withId(c, id);
    return c;
  }

  /** Address thuộc `owner`, đã có id. */
  public static Address addressWithId(UUID id, Customer owner, AddressType type) {
    Address a = new Address(owner, type, "John Doe", PHONE, "1 Main St", "HCM", "VN");
    withId(a, id);
    return a;
  }

  public static RegisterCustomerRequest registerRequest(String email, String phone) {
    return new RegisterCustomerRequest(email, phone, "John", "Doe");
  }

  public static AddAddressRequest addAddressRequest(AddressType type, Boolean isDefault) {
    return new AddAddressRequest(
        type, "John Doe", PHONE, "1 Main St", null, null, null, "HCM", null, null, "VN", isDefault);
  }

  private static void withId(BaseEntity entity, UUID id) {
    ReflectionTestUtils.setField(entity, "id", id);
  }
}
