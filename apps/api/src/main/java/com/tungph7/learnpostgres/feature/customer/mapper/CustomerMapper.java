package com.tungph7.learnpostgres.feature.customer.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.dto.CustomerResponse;

@Mapper
public interface CustomerMapper {

  /**
   * Map Customer entity → response.
   *
   * <p>status (enum) → String tự động bởi MapStruct enum-to-string.
   */
  @Mapping(target = "status", expression = "java(customer.getStatus().name())")
  CustomerResponse toResponse(Customer customer);
}
