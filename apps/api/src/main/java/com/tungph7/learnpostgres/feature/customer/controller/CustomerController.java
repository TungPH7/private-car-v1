package com.tungph7.learnpostgres.feature.customer.controller;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;
import com.tungph7.learnpostgres.feature.customer.dto.AddAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.AddressResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerProfileResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerResponse;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerSearchCriteria;
import com.tungph7.learnpostgres.feature.customer.dto.PagedCustomerResponse;
import com.tungph7.learnpostgres.feature.customer.dto.RegisterCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateAddressRequest;
import com.tungph7.learnpostgres.feature.customer.dto.UpdateCustomerRequest;
import com.tungph7.learnpostgres.feature.customer.mapper.AddressMapper;
import com.tungph7.learnpostgres.feature.customer.mapper.CustomerMapper;
import com.tungph7.learnpostgres.feature.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer & address management (F02)")
public class CustomerController {

  private final CustomerService service;
  private final CustomerMapper customerMapper;
  private final AddressMapper addressMapper;

  public CustomerController(
      CustomerService service, CustomerMapper customerMapper, AddressMapper addressMapper) {
    this.service = service;
    this.customerMapper = customerMapper;
    this.addressMapper = addressMapper;
  }

  // ================================================================
  // Customer
  // ================================================================

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register customer")
  public ResponseEntity<CustomerResponse> register(
      @Valid @RequestBody RegisterCustomerRequest req, UriComponentsBuilder uriBuilder) {
    var saved = service.register(req);
    URI location = uriBuilder.path("/api/v1/customers/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location).body(customerMapper.toResponse(saved));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get customer by id")
  public CustomerResponse getById(@PathVariable UUID id) {
    return customerMapper.toResponse(service.getById(id));
  }

  @GetMapping("/by-email/{email}")
  @Operation(summary = "Get customer by email (case-insensitive citext)")
  public CustomerResponse getByEmail(@PathVariable String email) {
    return customerMapper.toResponse(service.getByEmail(email));
  }

  @GetMapping
  @Operation(summary = "Search customers with dynamic filter + pagination")
  public PagedCustomerResponse search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) CustomerStatus status,
      @RequestParam(required = false) Boolean emailVerified,
      @RequestParam(required = false) Instant registeredFrom,
      @RequestParam(required = false) Instant registeredTo,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var criteria =
        new CustomerSearchCriteria(q, status, emailVerified, registeredFrom, registeredTo);
    return service.search(criteria, page, size);
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update customer (name, phone)")
  public CustomerResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest req) {
    return customerMapper.toResponse(service.update(id, req));
  }

  @PostMapping("/{id}/suspend")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Suspend customer (admin)")
  public void suspend(
      @PathVariable UUID id, @RequestParam(required = false, defaultValue = "") String reason) {
    service.suspend(id, reason);
  }

  @GetMapping("/{id}/profile")
  @Operation(summary = "Customer profile bundle (orders count, total spent, last order)")
  public CustomerProfileResponse profile(@PathVariable UUID id) {
    return service.getProfile(id);
  }

  // ================================================================
  // Address (nested under customer)
  // ================================================================

  @PostMapping("/{customerId}/addresses")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add address for customer")
  public ResponseEntity<AddressResponse> addAddress(
      @PathVariable UUID customerId,
      @Valid @RequestBody AddAddressRequest req,
      UriComponentsBuilder uriBuilder) {
    var saved = service.addAddress(customerId, req);
    URI location =
        uriBuilder
            .path("/api/v1/customers/{cid}/addresses/{aid}")
            .buildAndExpand(customerId, saved.getId())
            .toUri();
    return ResponseEntity.created(location).body(addressMapper.toResponse(saved));
  }

  @GetMapping("/{customerId}/addresses")
  @Operation(summary = "List addresses (optionally filter by type)")
  public List<AddressResponse> listAddresses(
      @PathVariable UUID customerId, @RequestParam(required = false) AddressType type) {
    return service.listAddresses(customerId, type).stream().map(addressMapper::toResponse).toList();
  }

  @PostMapping("/{customerId}/addresses/{addressId}/default")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Set address as default for its type")
  public void setDefault(@PathVariable UUID customerId, @PathVariable UUID addressId) {
    service.setDefaultAddress(customerId, addressId);
  }

  @PatchMapping("/{customerId}/addresses/{addressId}")
  @Operation(summary = "Update address fields")
  public AddressResponse updateAddress(
      @PathVariable UUID customerId,
      @PathVariable UUID addressId,
      @Valid @RequestBody UpdateAddressRequest req) {
    return addressMapper.toResponse(service.updateAddress(customerId, addressId, req));
  }

  @DeleteMapping("/{customerId}/addresses/{addressId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Soft delete address")
  public void deleteAddress(@PathVariable UUID customerId, @PathVariable UUID addressId) {
    service.softDeleteAddress(customerId, addressId);
  }
}
