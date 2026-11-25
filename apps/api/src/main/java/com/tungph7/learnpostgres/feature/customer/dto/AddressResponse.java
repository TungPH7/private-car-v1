package com.tungph7.learnpostgres.feature.customer.dto;

import java.util.UUID;

public record AddressResponse(
    UUID id,
    UUID customerId,
    String type,
    String fullName,
    String phone,
    String line1,
    String line2,
    String ward,
    String district,
    String city,
    String stateRegion,
    String postalCode,
    String countryCode,
    boolean isDefault) {}
