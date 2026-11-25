package com.tungph7.learnpostgres.feature.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.ForbiddenException;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.customer.CustomerFixtures;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.repository.AddressRepository;
import com.tungph7.learnpostgres.feature.customer.repository.CustomerRepository;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

  @Mock CustomerRepository customerRepo;
  @Mock AddressRepository addressRepo;
  @InjectMocks CustomerServiceImpl service;

  private UUID customerId;
  private Customer customer;

  @BeforeEach
  void setUp() {
    customerId = UUID.randomUUID();
    customer = CustomerFixtures.customerWithId(customerId, "john@x.com");
  }

  // ===========================================================
  // register()
  // ===========================================================

  @Nested
  @DisplayName("register()")
  class Register {

    @Test
    @DisplayName("happy path — INSERT new customer, status ACTIVE")
    void registerSuccess() {
      var req = CustomerFixtures.registerRequest("john@x.com", CustomerFixtures.PHONE);
      when(customerRepo.existsByEmail("john@x.com")).thenReturn(false);
      when(customerRepo.findByPhoneActive(CustomerFixtures.PHONE)).thenReturn(Optional.empty());
      when(customerRepo.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

      Customer result = service.register(req);

      assertThat(result.getEmail()).isEqualTo("john@x.com");
      assertThat(result.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
      assertThat(result.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("email taken → ConflictException CUS_EMAIL_TAKEN")
    void emailTaken() {
      var req = CustomerFixtures.registerRequest("dup@x.com", null);
      when(customerRepo.existsByEmail("dup@x.com")).thenReturn(true);

      assertThatThrownBy(() -> service.register(req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_EMAIL_TAKEN);

      verify(customerRepo, never()).save(any());
    }

    @Test
    @DisplayName("phone đang được customer ACTIVE dùng → ConflictException CUS_PHONE_TAKEN")
    void phoneTaken() {
      var req = CustomerFixtures.registerRequest("new@x.com", "+84901111111");
      when(customerRepo.existsByEmail("new@x.com")).thenReturn(false);
      when(customerRepo.findByPhoneActive("+84901111111")).thenReturn(Optional.of(customer));

      assertThatThrownBy(() -> service.register(req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_PHONE_TAKEN);
    }

    @Test
    @DisplayName("phone NULL → bỏ qua hẳn phone check")
    void phoneNullSkipCheck() {
      var req = CustomerFixtures.registerRequest("nophone@x.com", null);
      when(customerRepo.existsByEmail("nophone@x.com")).thenReturn(false);
      when(customerRepo.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

      service.register(req);

      verify(customerRepo, never()).findByPhoneActive(any());
    }
  }

  // ===========================================================
  // getById() / getByEmail() / update() / suspend()
  // ===========================================================

  @Nested
  @DisplayName("customer lookup + mutate")
  class LookupAndMutate {

    @Test
    @DisplayName("getById không thấy → NotFoundException CUS_NOT_FOUND")
    void getByIdNotFound() {
      when(customerRepo.findById(customerId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getById(customerId))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_NOT_FOUND);
    }

    @Test
    @DisplayName("getByEmail không thấy → NotFoundException CUS_NOT_FOUND")
    void getByEmailNotFound() {
      when(customerRepo.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.getByEmail("ghost@x.com"))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_NOT_FOUND);
    }

    @Test
    @DisplayName("update đổi sang phone của người khác → ConflictException CUS_PHONE_TAKEN")
    void updatePhoneTaken() {
      var req = new UpdateCustomerRequest("+84902222222", "John", "NewLast");
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(customerRepo.findByPhoneActive("+84902222222"))
          .thenReturn(
              Optional.of(CustomerFixtures.customerWithId(UUID.randomUUID(), "other@x.com")));

      assertThatThrownBy(() -> service.update(customerId, req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_PHONE_TAKEN);
    }

    @Test
    @DisplayName("update giữ nguyên phone → KHÔNG check unique, ghi field mới")
    void updateSamePhoneSkipsCheck() {
      var req = new UpdateCustomerRequest(CustomerFixtures.PHONE, "Johnny", "Doer");
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(customerRepo.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

      Customer updated = service.update(customerId, req);

      verify(customerRepo, never()).findByPhoneActive(any());
      assertThat(updated.getFirstName()).isEqualTo("Johnny");
      assertThat(updated.getLastName()).isEqualTo("Doer");
    }

    @Test
    @DisplayName("suspend customer đã DELETED → ConflictException CUS_INVALID_STATUS")
    void suspendDeleted() {
      customer.setStatus(CustomerStatus.DELETED);
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

      assertThatThrownBy(() -> service.suspend(customerId, "spam"))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_INVALID_STATUS);
    }

    @Test
    @DisplayName("suspend customer ACTIVE → status SUSPENDED")
    void suspendActive() {
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

      service.suspend(customerId, "spam");

      assertThat(customer.getStatus()).isEqualTo(CustomerStatus.SUSPENDED);
      verify(customerRepo).save(customer);
    }
  }

  // ===========================================================
  // addAddress()
  // ===========================================================

  @Nested
  @DisplayName("addAddress()")
  class AddAddress {

    @Test
    @DisplayName("address đầu tiên của type → tự set isDefault TRUE (UX)")
    void firstAddressAutoDefault() {
      var req = CustomerFixtures.addAddressRequest(AddressType.SHIPPING, false);
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(
              customerId, AddressType.SHIPPING))
          .thenReturn(Optional.empty());
      when(addressRepo.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

      Address saved = service.addAddress(customerId, req);

      assertThat(saved.isDefault()).isTrue();
      verify(addressRepo, never()).swapDefault(any(), any());
    }

    @Test
    @DisplayName("đã có default cùng type, không xin default → giữ FALSE")
    void subsequentNonDefault() {
      var req = CustomerFixtures.addAddressRequest(AddressType.SHIPPING, false);
      Address existingDefault =
          CustomerFixtures.addressWithId(UUID.randomUUID(), customer, AddressType.SHIPPING);
      existingDefault.setDefault(true);

      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(
              customerId, AddressType.SHIPPING))
          .thenReturn(Optional.of(existingDefault));
      when(addressRepo.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

      Address saved = service.addAddress(customerId, req);

      assertThat(saved.isDefault()).isFalse();
    }

    @Test
    @DisplayName("xin isDefault=TRUE → save FALSE, gọi swapDefault, trả bản đọc lại từ DB")
    void explicitDefaultSwap() {
      var req = CustomerFixtures.addAddressRequest(AddressType.SHIPPING, true);
      UUID newId = UUID.randomUUID();

      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.save(any(Address.class)))
          .thenAnswer(
              inv -> {
                Address a = inv.getArgument(0);
                // INSERT xong mới có id — mô phỏng @PrePersist
                org.springframework.test.util.ReflectionTestUtils.setField(a, "id", newId);
                assertThat(a.isDefault()).as("phải save FALSE trước khi swap").isFalse();
                return a;
              });
      // Bản trong DB sau khi swapDefault chạy — đã là default
      Address reloaded = CustomerFixtures.addressWithId(newId, customer, AddressType.SHIPPING);
      reloaded.setDefault(true);
      when(addressRepo.findById(newId)).thenReturn(Optional.of(reloaded));

      Address result = service.addAddress(customerId, req);

      verify(addressRepo, times(1)).swapDefault(eq(customerId), eq(newId));
      assertThat(result.isDefault())
          .as("trả entity đọc lại sau swap, không phải entity vừa save")
          .isTrue();
    }

    @Test
    @DisplayName("customer không tồn tại → NotFoundException CUS_NOT_FOUND")
    void addressForMissingCustomer() {
      var req = CustomerFixtures.addAddressRequest(AddressType.SHIPPING, false);
      when(customerRepo.findById(customerId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.addAddress(customerId, req))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_NOT_FOUND);
    }
  }

  // ===========================================================
  // listAddresses()
  // ===========================================================

  @Nested
  @DisplayName("listAddresses()")
  class ListAddresses {

    @Test
    @DisplayName("có type → dùng finder lọc theo type")
    void withType() {
      Address a = CustomerFixtures.addressWithId(UUID.randomUUID(), customer, AddressType.BILLING);
      when(customerRepo.existsById(customerId)).thenReturn(true);
      when(addressRepo.findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
              customerId, AddressType.BILLING))
          .thenReturn(List.of(a));

      assertThat(service.listAddresses(customerId, AddressType.BILLING)).containsExactly(a);
      verify(addressRepo, never())
          .findByCustomerIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(any());
    }

    @Test
    @DisplayName("type null → lấy tất cả")
    void withoutType() {
      when(customerRepo.existsById(customerId)).thenReturn(true);
      when(addressRepo.findByCustomerIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(customerId))
          .thenReturn(List.of());

      assertThat(service.listAddresses(customerId, null)).isEmpty();
    }

    @Test
    @DisplayName("customer không tồn tại → NotFoundException CUS_NOT_FOUND")
    void missingCustomer() {
      when(customerRepo.existsById(customerId)).thenReturn(false);

      assertThatThrownBy(() -> service.listAddresses(customerId, null))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CUS_NOT_FOUND);
    }
  }

  // ===========================================================
  // setDefaultAddress() — ownership + race
  // ===========================================================

  @Nested
  @DisplayName("setDefaultAddress()")
  class SetDefault {

    @Test
    @DisplayName("address không thuộc customer → NotFoundException ADR_NOT_FOUND")
    void notOwnedAddress() {
      UUID addrId = UUID.randomUUID();
      when(addressRepo.existsByIdAndCustomerId(addrId, customerId)).thenReturn(false);

      assertThatThrownBy(() -> service.setDefaultAddress(customerId, addrId))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.ADR_NOT_FOUND);

      verify(addressRepo, never()).swapDefault(any(), any());
    }

    @Test
    @DisplayName("swapDefault trả 0 (row bị xoá xen giữa) → NotFoundException ADR_NOT_FOUND")
    void swapZero() {
      UUID addrId = UUID.randomUUID();
      when(addressRepo.existsByIdAndCustomerId(addrId, customerId)).thenReturn(true);
      when(addressRepo.swapDefault(customerId, addrId)).thenReturn(0);

      assertThatThrownBy(() -> service.setDefaultAddress(customerId, addrId))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.ADR_NOT_FOUND);
    }

    @Test
    @DisplayName("swapDefault trả 1 → không throw")
    void swapOk() {
      UUID addrId = UUID.randomUUID();
      when(addressRepo.existsByIdAndCustomerId(addrId, customerId)).thenReturn(true);
      when(addressRepo.swapDefault(customerId, addrId)).thenReturn(1);

      service.setDefaultAddress(customerId, addrId);

      verify(addressRepo).swapDefault(customerId, addrId);
    }
  }

  // ===========================================================
  // updateAddress() / softDeleteAddress() — loadOwnedAddress
  // ===========================================================

  @Nested
  @DisplayName("loadOwnedAddress guard")
  class OwnershipGuard {

    @Test
    @DisplayName("update address của customer khác → ForbiddenException ADR_FORBIDDEN")
    void updateAddressForbidden() {
      UUID addrId = UUID.randomUUID();
      UUID otherCustomerId = UUID.randomUUID();
      Address ownedByOther = CustomerFixtures.addressWithId(addrId, customer, AddressType.SHIPPING);
      when(addressRepo.findById(addrId)).thenReturn(Optional.of(ownedByOther));

      var req =
          new UpdateAddressRequest("Hacker", null, null, null, null, null, null, null, null, null);

      assertThatThrownBy(() -> service.updateAddress(otherCustomerId, addrId, req))
          .isInstanceOf(ForbiddenException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.ADR_FORBIDDEN);

      verify(addressRepo, never()).save(any());
    }

    @Test
    @DisplayName("address không tồn tại → NotFoundException ADR_NOT_FOUND")
    void updateAddressNotFound() {
      UUID addrId = UUID.randomUUID();
      when(addressRepo.findById(addrId)).thenReturn(Optional.empty());

      var req =
          new UpdateAddressRequest(null, null, null, null, null, null, "HN", null, null, null);

      assertThatThrownBy(() -> service.updateAddress(customerId, addrId, req))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.ADR_NOT_FOUND);
    }

    @Test
    @DisplayName("PATCH chỉ ghi field khác null, field null giữ nguyên")
    void updateAddressPatchSemantics() {
      UUID addrId = UUID.randomUUID();
      Address addr = CustomerFixtures.addressWithId(addrId, customer, AddressType.SHIPPING);
      when(addressRepo.findById(addrId)).thenReturn(Optional.of(addr));
      when(addressRepo.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

      var req =
          new UpdateAddressRequest(null, null, null, null, null, null, "HN", null, null, null);

      Address updated = service.updateAddress(customerId, addrId, req);

      assertThat(updated.getCity()).isEqualTo("HN");
      assertThat(updated.getFullName()).as("field null → không đụng").isEqualTo("John Doe");
      assertThat(updated.getLine1()).isEqualTo("1 Main St");
    }

    @Test
    @DisplayName("soft delete address đang default → promote address còn lại")
    void softDeletePromotesNext() {
      UUID addrId = UUID.randomUUID();
      UUID remainingId = UUID.randomUUID();
      Address addr = CustomerFixtures.addressWithId(addrId, customer, AddressType.SHIPPING);
      addr.setDefault(true);
      Address remaining =
          CustomerFixtures.addressWithId(remainingId, customer, AddressType.SHIPPING);

      when(addressRepo.findById(addrId)).thenReturn(Optional.of(addr));
      when(addressRepo.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));
      when(addressRepo.findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
              customerId, AddressType.SHIPPING))
          .thenReturn(List.of(remaining));

      service.softDeleteAddress(customerId, addrId);

      assertThat(addr.isDeleted()).isTrue();
      assertThat(addr.isDefault()).as("phải nhả cờ default trước khi xoá mềm").isFalse();
      verify(addressRepo).swapDefault(customerId, remainingId);
    }

    @Test
    @DisplayName("soft delete address KHÔNG phải default → không promote ai")
    void softDeleteNonDefaultNoPromote() {
      UUID addrId = UUID.randomUUID();
      Address addr = CustomerFixtures.addressWithId(addrId, customer, AddressType.SHIPPING);
      when(addressRepo.findById(addrId)).thenReturn(Optional.of(addr));
      when(addressRepo.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

      service.softDeleteAddress(customerId, addrId);

      assertThat(addr.isDeleted()).isTrue();
      verify(addressRepo, never()).swapDefault(any(), any());
    }
  }
}
