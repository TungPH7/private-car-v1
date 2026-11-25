package com.tungph7.learnpostgres.feature.catalog.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryPathItem;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryResponse;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryTreeNode;
import com.tungph7.learnpostgres.feature.catalog.dto.CreateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.MoveCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.UpdateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.mapper.CategoryMapper;
import com.tungph7.learnpostgres.feature.catalog.service.CategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Catalog category management (F01)")
public class CategoryController {

  private final CategoryService service;
  private final CategoryMapper mapper;

  public CategoryController(CategoryService service, CategoryMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  // -----------------------------------------------------------------
  // Create / Update / Move / Archive / Delete
  // -----------------------------------------------------------------

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create category", description = "parentId = null → root.")
  @ApiResponse(responseCode = "201", description = "Created")
  @ApiResponse(
      responseCode = "409",
      description = "Slug taken",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ResponseEntity<CategoryResponse> create(
      @Valid @RequestBody CreateCategoryRequest req, UriComponentsBuilder uriBuilder) {
    Category saved = service.create(req);
    URI location = uriBuilder.path("/api/v1/categories/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location).body(mapper.toResponse(saved));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update category")
  public CategoryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest req) {
    return mapper.toResponse(service.update(id, req));
  }

  @PostMapping("/{id}/move")
  @Operation(
      summary = "Move category under new parent",
      description = "newParentId = null → move to root.")
  @ApiResponse(
      responseCode = "409",
      description = "Cycle detected",
      content = @Content(mediaType = "application/problem+json"))
  public CategoryResponse move(@PathVariable UUID id, @Valid @RequestBody MoveCategoryRequest req) {
    return mapper.toResponse(service.move(id, req));
  }

  @PostMapping("/{id}/archive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Soft archive category (is_active = false)")
  public void archive(@PathVariable UUID id) {
    service.archive(id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Hard delete (only if no children/products)")
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  // -----------------------------------------------------------------
  // Read endpoints
  // -----------------------------------------------------------------

  @GetMapping("/{id}")
  @Operation(summary = "Get category by id")
  public CategoryResponse getById(@PathVariable UUID id) {
    return mapper.toResponse(service.getById(id));
  }

  @GetMapping("/by-slug/{slug}")
  @Operation(summary = "Get category by slug (case-insensitive)")
  public CategoryResponse getBySlug(@PathVariable String slug) {
    return mapper.toResponse(service.getBySlug(slug));
  }

  @GetMapping("/roots")
  @Operation(summary = "List root categories (parent_id IS NULL)")
  public List<CategoryResponse> listRoots() {
    return service.listRoots().stream().map(mapper::toResponse).toList();
  }

  @GetMapping("/{id}/children")
  @Operation(summary = "List direct children")
  public List<CategoryResponse> listChildren(@PathVariable UUID id) {
    return service.listChildren(id).stream().map(mapper::toResponse).toList();
  }

  @GetMapping("/tree")
  @Operation(
      summary = "Full tree (flat list with depth + path)",
      description = "Single recursive CTE query — DFS order by sort_key.")
  public List<CategoryTreeNode> tree() {
    return service.tree();
  }

  @GetMapping("/{id}/path")
  @Operation(summary = "Breadcrumb path from root to category")
  public List<CategoryPathItem> path(@PathVariable UUID id) {
    return service.path(id);
  }

  @GetMapping("/{id}/descendants")
  @Operation(summary = "List descendant IDs (incl. self) via recursive CTE")
  public List<UUID> descendantIds(@PathVariable UUID id) {
    return service.descendantIds(id);
  }
}
