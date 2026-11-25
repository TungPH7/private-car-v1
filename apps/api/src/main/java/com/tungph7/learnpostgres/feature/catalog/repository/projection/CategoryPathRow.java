package com.tungph7.learnpostgres.feature.catalog.repository.projection;

import java.util.UUID;

public interface CategoryPathRow {

  UUID getId();

  String getName();

  String getSlug();
}
