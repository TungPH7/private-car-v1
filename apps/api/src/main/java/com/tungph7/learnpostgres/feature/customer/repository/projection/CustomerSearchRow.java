package com.tungph7.learnpostgres.feature.customer.repository.projection;

import java.time.Instant;
import java.util.UUID;

public interface CustomerSearchRow {

  UUID getId();

  String getEmail();

  String getPhone();

  String getFirstName();

  String getLastName();

  String getStatus();

  Boolean getEmailVerified();

  Instant getCreatedAt();

  Long getTotalCount();
}
