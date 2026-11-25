package com.tungph7.learnpostgres.feature.customer.dto;

import java.time.Instant;

import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;

public record CustomerSearchCriteria(
    String q,
    CustomerStatus status,
    Boolean emailVerified,
    Instant registeredFrom,
    Instant registeredTo) {}
