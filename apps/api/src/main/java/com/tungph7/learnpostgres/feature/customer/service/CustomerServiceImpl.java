package com.tungph7.learnpostgres.feature.customer.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.ForbiddenException;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;
import com.tungph7.learnpostgres.feature.customer.dto.AddAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerProfileResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerSearchCriteria;
import com.tungph7.learnpostgres.feature.customer.dto.PagedCustomerResponse;
import com.tungph7.learnpostgres.feature.customer.dto.RegisterCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.repository.AddressRepository;
import com.tungph7.learnpostgres.feature.customer.repository.CustomerRepository;
import com.tungph7.learnpostgres.feature.customer.repository.projection.CustomerProfileRow;
import com.tungph7.learnpostgres.feature.customer.repository.projection.CustomerSearchRow;

@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

  private final CustomerRepository customerRepo;
  private final AddressRepository addressRepo;

  public CustomerServiceImpl(CustomerRepository customerRepo, AddressRepository addressRepo) {
    this.customerRepo = customerRepo;
    this.addressRepo = addressRepo;
  }

  // ================================================================
  // Customer
  // ================================================================

  @Override
  @Transactional
  public Customer register(RegisterCustomerRequest req) {
    // Pre-check email cho UX rõ; DB UNIQUE là safety net
    if (customerRepo.existsByEmail(req.email())) {
      throw new ConflictException(
          ErrorCode.CUS_EMAIL_TAKEN, "Email '%s' đã đăng ký.".formatted(req.email()));
    }
    // Pre-check phone (active only — partial unique)
    if (req.phone() != null && customerRepo.findByPhoneActive(req.phone()).isPresent()) {
      throw new ConflictException(ErrorCode.CUS_PHONE_TAKEN, "Số điện thoại đã được dùng.");
    }
    Customer c = new Customer(req.email(), req.phone(), req.firstName(), req.lastName());
    return customerRepo.save(c);
  }

  @Override
  public Customer getById(UUID id) {
    return customerRepo
        .findById(id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(id)));
  }

  @Override
  public Customer getByEmail(String email) {
    return customerRepo
        .findByEmail(email)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CUS_NOT_FOUND,
                    "Customer email '%s' không tồn tại.".formatted(email)));
  }

  @Override
  public PagedCustomerResponse search(CustomerSearchCriteria criteria, int page, int size) {
    int offset = page * size;
    List<CustomerSearchRow> rows =
        customerRepo.searchCustomers(
            criteria.q(),
            criteria.status() == null ? null : criteria.status().name(),
            criteria.emailVerified(),
            criteria.registeredFrom(),
            criteria.registeredTo(),
            size,
            offset);

    long totalElements = rows.isEmpty() ? 0 : rows.get(0).getTotalCount();
    int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

    List<CustomerResponse> content =
        rows.stream()
            .map(
                r ->
                    new CustomerResponse(
                        r.getId(),
                        r.getEmail(),
                        r.getPhone(),
                        r.getFirstName(),
                        r.getLastName(),
                        r.getStatus(),
                        Boolean.TRUE.equals(r.getEmailVerified()),
                        null, // lastLoginAt không có trong search query
                        r.getCreatedAt(),
                        0L))
            .toList();

    return new PagedCustomerResponse(content, page, size, totalElements, totalPages);
  }

  @Override
  @Transactional
  public Customer update(UUID id, UpdateCustomerRequest req) {
    Customer c = getById(id);

    // Nếu phone đổi → check unique
    if (req.phone() != null
        && !req.phone().equals(c.getPhone())
        && customerRepo.findByPhoneActive(req.phone()).isPresent()) {
      throw new ConflictException(ErrorCode.CUS_PHONE_TAKEN, "Số điện thoại đã được dùng.");
    }

    c.setPhone(req.phone());
    c.setFirstName(req.firstName());
    c.setLastName(req.lastName());
    return customerRepo.save(c);
  }

  @Override
  @Transactional
  public void suspend(UUID id, String reason) {
    Customer c = getById(id);
    if (c.getStatus() == CustomerStatus.DELETED) {
      throw new ConflictException(
          ErrorCode.CUS_INVALID_STATUS, "Customer đã DELETED — không thể suspend.");
    }
    c.setStatus(CustomerStatus.SUSPENDED);
    customerRepo.save(c);
    // TODO: log reason vào audit table khi có
  }

  @Override
  public CustomerProfileResponse getProfile(UUID id) {
    CustomerProfileRow row =
        customerRepo
            .findProfile(id)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(id)));

    return new CustomerProfileResponse(
        row.getId(),
        row.getEmail(),
        row.getFirstName(),
        row.getLastName(),
        row.getPhone(),
        row.getStatus(),
        row.getCreatedAt(),
        row.getOrdersCount(),
        row.getTotalSpent(),
        row.getLastOrderAt());
  }

  // ================================================================
  // Address
  // ================================================================

  @Override
  @Transactional
  public Address addAddress(UUID customerId, AddAddressRequest req) {
    Customer c = getById(customerId);

    Address addr =
        new Address(
            c, req.type(), req.fullName(), req.phone(), req.line1(), req.city(), req.countryCode());
    addr.setLine2(req.line2());
    addr.setWard(req.ward());
    addr.setDistrict(req.district());
    addr.setStateRegion(req.stateRegion());
    addr.setPostalCode(req.postalCode());

    boolean wantDefault = Boolean.TRUE.equals(req.isDefault());

    // Nếu user yêu cầu default → phải swap default cũ trước
    if (wantDefault) {
      // Pattern: save với isDefault=FALSE trước, sau đó swap
      addr.setDefault(false);
      UUID newId = addressRepo.save(addr).getId();
      // swapDefault khai báo flushAutomatically + clearAutomatically → INSERT được đẩy xuống
      // trước UPDATE, và persistence context được clear nên findById dưới đây đọc lại từ DB
      // (không clear thì nó trả về entity cache với is_default = false).
      addressRepo.swapDefault(customerId, newId);
      return addressRepo.findById(newId).orElseThrow();
    }

    // Nếu không có default address nào của type này → tự động set TRUE (UX)
    boolean hasAnyDefault =
        addressRepo
            .findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(customerId, req.type())
            .isPresent();
    if (!hasAnyDefault) {
      addr.setDefault(true);
    }

    return addressRepo.save(addr);
  }

  @Override
  public List<Address> listAddresses(UUID customerId, AddressType type) {
    if (!customerRepo.existsById(customerId)) {
      throw new NotFoundException(
          ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(customerId));
    }
    if (type != null) {
      return addressRepo.findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
          customerId, type);
    }
    return addressRepo.findByCustomerIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(customerId);
  }

  @Override
  @Transactional
  public void setDefaultAddress(UUID customerId, UUID addressId) {
    // Security check: address phải thuộc customer này
    if (!addressRepo.existsByIdAndCustomerId(addressId, customerId)) {
      throw new NotFoundException(
          ErrorCode.ADR_NOT_FOUND, "Address không tồn tại hoặc không thuộc customer.");
    }
    int updated = addressRepo.swapDefault(customerId, addressId);
    if (updated == 0) {
      // Hiếm — address đã deleted hoặc khác type
      throw new NotFoundException(
          ErrorCode.ADR_NOT_FOUND, "Không cập nhật được — address có thể đã xóa.");
    }
  }

  @Override
  @Transactional
  public Address updateAddress(UUID customerId, UUID addressId, UpdateAddressRequest req) {
    Address addr = loadOwnedAddress(customerId, addressId);

    if (req.fullName() != null) {
      addr.setFullName(req.fullName());
    }
    if (req.phone() != null) {
      addr.setPhone(req.phone());
    }
    if (req.line1() != null) {
      addr.setLine1(req.line1());
    }
    if (req.line2() != null) {
      addr.setLine2(req.line2());
    }
    if (req.ward() != null) {
      addr.setWard(req.ward());
    }
    if (req.district() != null) {
      addr.setDistrict(req.district());
    }
    if (req.city() != null) {
      addr.setCity(req.city());
    }
    if (req.stateRegion() != null) {
      addr.setStateRegion(req.stateRegion());
    }
    if (req.postalCode() != null) {
      addr.setPostalCode(req.postalCode());
    }
    if (req.countryCode() != null) {
      addr.setCountryCode(req.countryCode());
    }

    return addressRepo.save(addr);
  }

  @Override
  @Transactional
  public void softDeleteAddress(UUID customerId, UUID addressId) {
    Address addr = loadOwnedAddress(customerId, addressId);
    boolean wasDefault = addr.isDefault();
    AddressType type = addr.getType();

    addr.setDeleted(true);
    addr.setDefault(false);
    addressRepo.save(addr);

    // Nếu vừa xóa default → auto-promote address khác (UX)
    if (wasDefault) {
      List<Address> remaining =
          addressRepo.findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
              customerId, type);
      if (!remaining.isEmpty()) {
        addressRepo.swapDefault(customerId, remaining.get(0).getId());
      }
    }
  }

  // ================================================================
  // Helpers
  // ================================================================

  private Address loadOwnedAddress(UUID customerId, UUID addressId) {
    Address addr =
        addressRepo
            .findById(addressId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.ADR_NOT_FOUND,
                        "Address '%s' không tồn tại.".formatted(addressId)));

    if (!addr.getCustomer().getId().equals(customerId)) {
      throw new ForbiddenException(ErrorCode.ADR_FORBIDDEN, "Address không thuộc customer này.");
    }
    return addr;
  }
}
