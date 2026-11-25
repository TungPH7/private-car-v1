package com.tungph7.learnpostgres.feature.customer.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.dto.AddressResponse;

@Mapper
public interface AddressMapper {

  @Mapping(target = "customerId", expression = "java(address.getCustomer().getId())")
  @Mapping(target = "type", expression = "java(address.getType().name())")
  @Mapping(target = "isDefault", source = "default")
  AddressResponse toResponse(Address address);
}
