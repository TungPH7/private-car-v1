package com.tungph7.learnpostgres.feature.catalog.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryResponse;

@Mapper
public interface CategoryMapper {

  /**
   * Map entity → response.
   *
   * <p>parentId được pull từ entity con qua `parent.id`. Khi parent NULL → ID NULL → JSON `null`.
   */
  @Mapping(
      target = "parentId",
      expression = "java(category.getParent() == null ? null : category.getParent().getId())")
  CategoryResponse toResponse(Category category);
}
