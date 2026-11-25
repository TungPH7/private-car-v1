package com.tungph7.learnpostgres.feature.customer.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface CustomerProfileRow {

  UUID getId();

  String getEmail();

  String getFirstName();

  String getLastName();

  String getPhone();

  String getStatus();

  Instant getCreatedAt();

  Long getOrdersCount();

  BigDecimal getTotalSpent();

  Instant getLastOrderAt();
}
