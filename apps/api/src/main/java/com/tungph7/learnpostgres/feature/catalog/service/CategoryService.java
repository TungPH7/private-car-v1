package com.tungph7.learnpostgres.feature.catalog.service;

import java.util.List;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryPathItem;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryTreeNode;
import com.tungph7.learnpostgres.feature.catalog.dto.CreateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.MoveCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.UpdateCategoryRequest;

public interface CategoryService {

  Category create(CreateCategoryRequest req);

  Category getById(UUID id);

  Category getBySlug(String slug);

  List<Category> listRoots();

  List<Category> listChildren(UUID parentId);

  List<CategoryTreeNode> tree();

  List<CategoryPathItem> path(UUID id);

  List<UUID> descendantIds(UUID id);

  Category update(UUID id, UpdateCategoryRequest req);

  Category move(UUID id, MoveCategoryRequest req);

  void archive(UUID id);

  void delete(UUID id);
}
