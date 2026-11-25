package com.tungph7.learnpostgres.feature.catalog.repository.projection;

import java.util.UUID;

/**
 * Projection của row trong recursive CTE cat_tree.
 *
 * <p>Field getter name = alias trong SELECT clause của native query. Spring Data tự bind tự động.
 */
public interface CategoryTreeRow {

  UUID getId();

  UUID getParentId();

  String getName();

  String getSlug();

  boolean getActive();

  int getDepth();

  String getPath();
}
