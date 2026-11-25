package com.tungph7.learnpostgres.feature.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CreateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.MoveCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.UpdateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.repository.CategoryRepository;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

  @Mock CategoryRepository repository;
  @InjectMocks CategoryServiceImpl service;

  private UUID id;

  @BeforeEach
  void setUp() {
    id = UUID.randomUUID();
  }

  // -----------------------------------------------------------------
  // create()
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("create()")
  class Create {

    @Test
    @DisplayName("create root category — không có parent — success")
    void createRoot() {
      var req = new CreateCategoryRequest(null, "Electronics", "electronics", null, 1);
      when(repository.existsBySlug("electronics")).thenReturn(false);
      when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

      Category result = service.create(req);

      assertThat(result.getName()).isEqualTo("Electronics");
      assertThat(result.getSlug()).isEqualTo("electronics");
      assertThat(result.getParent()).isNull();
      assertThat(result.getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("create with parent — load parent → success")
    void createWithParent() {
      UUID parentId = UUID.randomUUID();
      Category parent = new Category(null, "Electronics", "electronics", null, 0);
      var req = new CreateCategoryRequest(parentId, "Phones", "phones", null, 1);

      when(repository.existsBySlug("phones")).thenReturn(false);
      when(repository.findById(parentId)).thenReturn(Optional.of(parent));
      when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

      Category result = service.create(req);

      assertThat(result.getParent()).isEqualTo(parent);
    }

    @Test
    @DisplayName("create with duplicate slug → ConflictException CAT_SLUG_TAKEN")
    void slugDuplicate() {
      var req = new CreateCategoryRequest(null, "X", "taken", null, 0);
      when(repository.existsBySlug("taken")).thenReturn(true);

      assertThatThrownBy(() -> service.create(req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_SLUG_TAKEN);
    }

    @Test
    @DisplayName("create with non-existent parent → NotFoundException CAT_PARENT_NOT_FOUND")
    void parentNotFound() {
      UUID parentId = UUID.randomUUID();
      var req = new CreateCategoryRequest(parentId, "X", "x", null, 0);
      when(repository.existsBySlug("x")).thenReturn(false);
      when(repository.findById(parentId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.create(req))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_PARENT_NOT_FOUND);
    }
  }

  // -----------------------------------------------------------------
  // move()
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("move()")
  class Move {

    @Test
    @DisplayName("move to root — newParentId null")
    void moveToRoot() {
      Category cat = new Category(null, "X", "x", null, 0);
      when(repository.findById(id)).thenReturn(Optional.of(cat));
      when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

      service.move(id, new MoveCategoryRequest(null));

      assertThat(cat.getParent()).isNull();
    }

    @Test
    @DisplayName("move under self → BadRequestException CAT_SELF_PARENT")
    void moveUnderSelf() {
      Category cat = new Category(null, "X", "x", null, 0);
      when(repository.findById(id)).thenReturn(Optional.of(cat));

      assertThatThrownBy(() -> service.move(id, new MoveCategoryRequest(id)))
          .isInstanceOf(BadRequestException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_SELF_PARENT);
    }

    @Test
    @DisplayName("move under non-existent parent → NotFoundException CAT_PARENT_NOT_FOUND")
    void moveUnderMissingParent() {
      UUID newParentId = UUID.randomUUID();
      Category cat = new Category(null, "X", "x", null, 0);
      when(repository.findById(id)).thenReturn(Optional.of(cat));
      when(repository.findById(newParentId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.move(id, new MoveCategoryRequest(newParentId)))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_PARENT_NOT_FOUND);
    }
  }

  // -----------------------------------------------------------------
  // archive()
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("archive()")
  class Archive {

    @Test
    @DisplayName("archive — không có active children → success")
    void archiveSuccess() {
      when(repository.existsById(id)).thenReturn(true);
      when(repository.existsByParentIdAndActiveTrue(id)).thenReturn(false);
      when(repository.archive(id)).thenReturn(1);

      service.archive(id); // no exception
    }

    @Test
    @DisplayName("archive — còn active children → ConflictException CAT_HAS_ACTIVE_CHILDREN")
    void archiveWithActiveChildren() {
      when(repository.existsById(id)).thenReturn(true);
      when(repository.existsByParentIdAndActiveTrue(id)).thenReturn(true);

      assertThatThrownBy(() -> service.archive(id))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_HAS_ACTIVE_CHILDREN);
    }

    @Test
    @DisplayName("archive non-existent → NotFoundException")
    void archiveNotFound() {
      when(repository.existsById(id)).thenReturn(false);

      assertThatThrownBy(() -> service.archive(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("archive — UPDATE ra 0 row (vừa bị xóa bởi tx khác) → NotFoundException")
    void archiveLostToConcurrentDelete() {
      when(repository.existsById(id)).thenReturn(true);
      when(repository.existsByParentIdAndActiveTrue(id)).thenReturn(false);
      when(repository.archive(id)).thenReturn(0);

      assertThatThrownBy(() -> service.archive(id))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
    }
  }

  // -----------------------------------------------------------------
  // delete()
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("delete()")
  class Delete {

    @Test
    @DisplayName("delete non-existent → NotFoundException, không gọi deleteById")
    void deleteNotFound() {
      when(repository.existsById(id)).thenReturn(false);

      assertThatThrownBy(() -> service.delete(id))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
      verify(repository, never()).deleteById(any());
    }
  }

  // -----------------------------------------------------------------
  // Read — getById / getBySlug / listRoots / listChildren / path / descendantIds
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    private final Category electronics = new Category(null, "Electronics", "electronics", null, 0);

    @Test
    @DisplayName("getById — có thì trả, không có → CAT_NOT_FOUND")
    void getById() {
      UUID missing = UUID.randomUUID();
      when(repository.findById(id)).thenReturn(Optional.of(electronics));
      when(repository.findById(missing)).thenReturn(Optional.empty());

      assertThat(service.getById(id)).isSameAs(electronics);
      assertThatThrownBy(() -> service.getById(missing))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
    }

    @Test
    @DisplayName("getBySlug — có thì trả, không có → CAT_NOT_FOUND")
    void getBySlug() {
      when(repository.findBySlug("electronics")).thenReturn(Optional.of(electronics));
      when(repository.findBySlug("nope")).thenReturn(Optional.empty());

      assertThat(service.getBySlug("electronics")).isSameAs(electronics);
      assertThatThrownBy(() -> service.getBySlug("nope"))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
    }

    @Test
    @DisplayName("listRoots — category không có parent, theo sort_order")
    void listRoots() {
      when(repository.findByParentIsNullOrderBySortOrderAsc()).thenReturn(List.of(electronics));

      assertThat(service.listRoots()).containsExactly(electronics);
    }

    @Test
    @DisplayName("listChildren — parent có thì trả con; parent không có → 404 thay vì list rỗng")
    void listChildren() {
      UUID missing = UUID.randomUUID();
      when(repository.existsById(id)).thenReturn(true);
      when(repository.findByParentIdOrderBySortOrderAsc(id)).thenReturn(List.of(electronics));
      when(repository.existsById(missing)).thenReturn(false);

      assertThat(service.listChildren(id)).containsExactly(electronics);
      assertThatThrownBy(() -> service.listChildren(missing))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
      verify(repository, never()).findByParentIdOrderBySortOrderAsc(missing);
    }

    @Test
    @DisplayName("path — category không tồn tại → CAT_NOT_FOUND, không chạy CTE")
    void pathOfMissingCategory() {
      when(repository.existsById(id)).thenReturn(false);

      assertThatThrownBy(() -> service.path(id))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
      verify(repository, never()).findPath(any());
    }

    @Test
    @DisplayName("descendantIds — có thì trả id con cháu; không có → CAT_NOT_FOUND")
    void descendantIds() {
      UUID missing = UUID.randomUUID();
      List<UUID> descendants = List.of(id, UUID.randomUUID());
      when(repository.existsById(id)).thenReturn(true);
      when(repository.findDescendantIds(id)).thenReturn(descendants);
      when(repository.existsById(missing)).thenReturn(false);

      assertThat(service.descendantIds(id)).isSameAs(descendants);
      assertThatThrownBy(() -> service.descendantIds(missing))
          .isInstanceOf(NotFoundException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_NOT_FOUND);
    }
  }

  // -----------------------------------------------------------------
  // update()
  // -----------------------------------------------------------------

  @Nested
  @DisplayName("update()")
  class Update {

    /**
     * Category mới `new` chưa qua Hibernate nên version = null → set tay để qua được version check.
     */
    private Category persisted(String name, String slug, long version) {
      Category cat = new Category(null, name, slug, null, 0);
      ReflectionTestUtils.setField(cat, "version", version);
      return cat;
    }

    @Test
    @DisplayName("update với slug giữ nguyên → không check unique")
    void updateSameSlug() {
      Category cat = persisted("Old", "old-slug", 3L);
      var req = new UpdateCategoryRequest(3L, "New", "old-slug", "desc", 5);

      when(repository.findById(id)).thenReturn(Optional.of(cat));
      when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

      Category result = service.update(id, req);

      assertThat(result.getName()).isEqualTo("New");
      assertThat(result.getSortOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("update với slug mới đã tồn tại → ConflictException")
    void updateSlugTaken() {
      Category cat = persisted("Old", "old-slug", 0L);
      var req = new UpdateCategoryRequest(0L, "New", "taken-slug", null, null);

      when(repository.findById(id)).thenReturn(Optional.of(cat));
      when(repository.existsBySlug("taken-slug")).thenReturn(true);

      assertThatThrownBy(() -> service.update(id, req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.CAT_SLUG_TAKEN);
    }

    @Test
    @DisplayName("update với version cũ → ConflictException OPTIMISTIC_LOCK_CONFLICT")
    void updateStaleVersion() {
      Category cat = persisted("Old", "old-slug", 5L);
      var req = new UpdateCategoryRequest(2L, "New", "old-slug", null, null);

      when(repository.findById(id)).thenReturn(Optional.of(cat));

      assertThatThrownBy(() -> service.update(id, req))
          .isInstanceOf(ConflictException.class)
          .extracting("code")
          .isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
    }
  }
}
