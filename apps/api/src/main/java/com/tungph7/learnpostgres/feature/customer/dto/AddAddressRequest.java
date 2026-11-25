package com.tungph7.learnpostgres.feature.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.tungph7.learnpostgres.feature.customer.domain.AddressType;

public record AddAddressRequest(
    @NotNull AddressType type,
    @NotBlank @Size(max = 160) String fullName,
    @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
    @NotBlank @Size(max = 255) String line1,
    @Size(max = 255) String line2,
    @Size(max = 80) String ward,
    @Size(max = 80) String district,
    @NotBlank @Size(max = 80) String city,
    @Size(max = 80) String stateRegion,
    @Size(max = 20) String postalCode,
    @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
    Boolean isDefault) {}
