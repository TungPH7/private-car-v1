package com.tungph7.learnpostgres.feature.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics — mọi field optional, null nghĩa là "không đổi".
 *
 * <p>Không có {@code type} và {@code isDefault}: đổi type coi như address khác (tạo mới), còn đổi
 * default đi qua endpoint riêng để dùng swapDefault atomic.
 */
public record UpdateAddressRequest(
    @Size(min = 1, max = 160) String fullName,
    @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
    @Size(min = 1, max = 255) String line1,
    @Size(max = 255) String line2,
    @Size(max = 80) String ward,
    @Size(max = 80) String district,
    @Size(min = 1, max = 80) String city,
    @Size(max = 80) String stateRegion,
    @Size(max = 20) String postalCode,
    @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {}
