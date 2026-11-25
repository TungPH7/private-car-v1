package com.tungph7.learnpostgres.feature.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(
    @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
    @NotBlank @Size(max = 80) String firstName,
    @NotBlank @Size(max = 80) String lastName) {}
