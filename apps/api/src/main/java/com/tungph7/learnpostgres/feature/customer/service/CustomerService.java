package com.tungph7.learnpostgres.feature.customer.service;

import java.util.List;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.dto.AddAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerProfileResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerSearchCriteria;
import com.tungph7.learnpostgres.feature.customer.dto.PagedCustomerResponse;
import com.tungph7.learnpostgres.feature.customer.dto.RegisterCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateCustomerRequest;

public interface CustomerService {

  // ---- Customer ----
  Customer register(RegisterCustomerRequest req);

  Customer getById(UUID id);

  Customer getByEmail(String email);

  PagedCustomerResponse search(CustomerSearchCriteria criteria, int page, int size);

  Customer update(UUID id, UpdateCustomerRequest req);

  void suspend(UUID id, String reason);

  CustomerProfileResponse getProfile(UUID id);

  // ---- Address ----
  Address addAddress(UUID customerId, AddAddressRequest req);

  List<Address> listAddresses(UUID customerId, AddressType type);

  void setDefaultAddress(UUID customerId, UUID addressId);

  Address updateAddress(UUID customerId, UUID addressId, UpdateAddressRequest req);

  void softDeleteAddress(UUID customerId, UUID addressId);
}
