package com.tungph7.learnpostgres.feature.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryPathItem;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryTreeNode;
import com.tungph7.learnpostgres.feature.catalog.dto.CreateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.MoveCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.UpdateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.repository.CategoryRepository;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryPathRow;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryTreeRow;

@Service
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

  private final CategoryRepository repository;

  public CategoryServiceImpl(CategoryRepository repository) {
    this.repository = repository;
  }

  // ===========================================================
  // Read
  // ===========================================================

  @Override
  public Category getById(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(id)));
  }

  @Override
  public Category getBySlug(String slug) {
    return repository
        .findBySlug(slug)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CAT_NOT_FOUND, "Category with slug '%s' not found.".formatted(slug)));
  }

  @Override
  public List<Category> listRoots() {
    return repository.findByParentIsNullOrderBySortOrderAsc();
  }

  @Override
  public List<Category> listChildren(UUID parentId) {
    // Verify parent tồn tại — nếu không, trả NotFound thay vì list rỗng (UX rõ hơn)
    if (!repository.existsById(parentId)) {
      throw new NotFoundException(
          ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(parentId));
    }
    return repository.findByParentIdOrderBySortOrderAsc(parentId);
  }

  @Override
  public List<CategoryTreeNode> tree() {
    return repository.findTree().stream().map(CategoryServiceImpl::toTreeNode).toList();
  }

  @Override
  public List<CategoryPathItem> path(UUID id) {
    if (!repository.existsById(id)) {
      throw new NotFoundException(
          ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(id));
    }
    return repository.findPath(id).stream().map(CategoryServiceImpl::toPathItem).toList();
  }

  @Override
  public List<UUID> descendantIds(UUID id) {
    if (!repository.existsById(id)) {
      throw new NotFoundException(
          ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(id));
    }
    return repository.findDescendantIds(id);
  }

  // ===========================================================
  // Write
  // ===========================================================

  @Override
  @Transactional
  public Category create(CreateCategoryRequest req) {
    // Pre-check slug để trả 409 sớm với message rõ; DB UNIQUE vẫn là safety net cho race.
    if (repository.existsBySlug(req.slug())) {
      throw new ConflictException(
          ErrorCode.CAT_SLUG_TAKEN, "Slug '%s' đã tồn tại.".formatted(req.slug()));
    }

    Category parent = null;
    if (req.parentId() != null) {
      parent =
          repository
              .findById(req.parentId())
              .orElseThrow(
                  () ->
                      new NotFoundException(
                          ErrorCode.CAT_PARENT_NOT_FOUND,
                          "Parent '%s' không tồn tại.".formatted(req.parentId())));
    }

    int sortOrder = Optional.ofNullable(req.sortOrder()).orElse(0);
    Category cat = new Category(parent, req.name(), req.slug(), req.description(), sortOrder);
    return repository.save(cat);
  }

  @Override
  @Transactional
  public Category update(UUID id, UpdateCategoryRequest req) {
    Category cat = getById(id);

    // Client gửi version lấy từ GET response. Nếu không khớp → entity đã bị sửa bởi tx khác.
    // Check sớm ở service thay vì để Hibernate throw ObjectOptimisticLockingFailureException
    // để message rõ hơn và không tốn round-trip UPDATE thừa.
    if (!cat.getVersion().equals(req.version())) {
      throw new ConflictException(
          ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
          "Resource was modified by another transaction. Please reload and retry.");
    }

    // Nếu slug đổi → check unique
    if (!cat.getSlug().equalsIgnoreCase(req.slug()) && repository.existsBySlug(req.slug())) {
      throw new ConflictException(
          ErrorCode.CAT_SLUG_TAKEN, "Slug '%s' đã tồn tại.".formatted(req.slug()));
    }

    cat.setName(req.name());
    cat.setSlug(req.slug());
    cat.setDescription(req.description());
    if (req.sortOrder() != null) {
      cat.setSortOrder(req.sortOrder());
    }
    return repository.save(cat);
  }

  @Override
  @Transactional
  public Category move(UUID id, MoveCategoryRequest req) {
    Category cat = getById(id);
    UUID newParentId = req.newParentId();

    if (newParentId == null) {
      // Move thành root
      cat.setParent(null);
    } else {
      // Pre-check: cùng id thì 400 (cycle 1-level).
      if (id.equals(newParentId)) {
        throw new BadRequestException(
            ErrorCode.CAT_SELF_PARENT, "Category không thể là parent của chính nó.");
      }
      Category newParent =
          repository
              .findById(newParentId)
              .orElseThrow(
                  () ->
                      new NotFoundException(
                          ErrorCode.CAT_PARENT_NOT_FOUND,
                          "Parent '%s' không tồn tại.".formatted(newParentId)));
      cat.setParent(newParent);
    }
    // Trigger trg_categories_no_cycle sẽ raise nếu newParent là descendant của cat.
    // → DataIntegrityViolationException → handler map sang CAT_CYCLE_DETECTED (409).
    return repository.save(cat);
  }

  @Override
  @Transactional
  public void archive(UUID id) {
    if (!repository.existsById(id)) {
      throw new NotFoundException(
          ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(id));
    }
    if (repository.existsByParentIdAndActiveTrue(id)) {
      throw new ConflictException(
          ErrorCode.CAT_HAS_ACTIVE_CHILDREN, "Còn category con đang active — không thể archive.");
    }
    int updated = repository.archive(id);
    if (updated == 0) {
      // Hiếm: race condition với DELETE — coi như NotFound
      throw new NotFoundException(ErrorCode.CAT_NOT_FOUND, "Category vừa bị xóa bởi tx khác.");
    }
  }

  @Override
  @Transactional
  public void delete(UUID id) {
    if (!repository.existsById(id)) {
      throw new NotFoundException(
          ErrorCode.CAT_NOT_FOUND, "Category with id '%s' not found.".formatted(id));
    }
    // Nếu còn FK reference (products hoặc children) → DB raise FK violation
    // → DataIntegrityViolationException → handler map CAT_HAS_REFERENCES (409).
    repository.deleteById(id);
  }

  // ===========================================================
  // Static mappers (projection → DTO)
  // ===========================================================

  private static CategoryTreeNode toTreeNode(CategoryTreeRow row) {
    return new CategoryTreeNode(
        row.getId(),
        row.getParentId(),
        row.getName(),
        row.getSlug(),
        row.getActive(),
        row.getDepth(),
        row.getPath());
  }

  private static CategoryPathItem toPathItem(CategoryPathRow row) {
    return new CategoryPathItem(row.getId(), row.getName(), row.getSlug());
  }
}
