package com.tungph7.learnpostgres.feature.customer.dto;

import java.util.List;

public record PagedCustomerResponse(
    List<CustomerResponse> content, int page, int size, long totalElements, int totalPages) {}
