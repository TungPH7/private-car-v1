package com.tungph7.learnpostgres.feature.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.config.JpaAuditingConfig;
import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryPathRow;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryTreeRow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    JpaAuditingConfig.class) // @DataJpaTest không tự load auditing → import tường minh (QnA-2 Q3)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CategoryRepositoryTest extends AbstractContainerBaseTest {

  @Autowired CategoryRepository repository;

  @Test
  @DisplayName("save root category — id, audit fields populated")
  void saveRoot() {
    Category cat = new Category(null, "Electronics", "electronics", null, 1);
    Category saved = repository.saveAndFlush(cat);

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getVersion()).isEqualTo(0);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("findTree returns flat list with depth and path")
  void findTreeReturnsFlatList() {
    Category root = repository.save(new Category(null, "Root", "root", null, 0));
    Category child = repository.save(new Category(root, "Child", "child", null, 0));
    repository.save(new Category(child, "Grandchild", "gc", null, 0));

    // findTree() trả cả bảng — lọc về đúng 3 node của test này để không phụ thuộc
    // vào việc integration test có dọn sạch data hay không.
    List<String> mine = List.of("root", "child", "gc");
    List<CategoryTreeRow> tree =
        repository.findTree().stream().filter(r -> mine.contains(r.getSlug())).toList();

    assertThat(tree).hasSize(3);
    assertThat(tree.get(0).getDepth()).isEqualTo(0);
    assertThat(tree.get(0).getName()).isEqualTo("Root");
    assertThat(tree.get(1).getDepth()).isEqualTo(1);
    assertThat(tree.get(1).getPath()).isEqualTo("Root > Child");
    assertThat(tree.get(2).getDepth()).isEqualTo(2);
    assertThat(tree.get(2).getPath()).isEqualTo("Root > Child > Grandchild");
  }

  @Test
  @DisplayName("findPath returns root → leaf order")
  void findPathOrder() {
    Category root = repository.save(new Category(null, "R", "r1", null, 0));
    Category child = repository.save(new Category(root, "C", "c1", null, 0));
    Category leaf = repository.save(new Category(child, "L", "l1", null, 0));

    List<CategoryPathRow> path = repository.findPath(leaf.getId());

    assertThat(path).extracting(CategoryPathRow::getSlug).containsExactly("r1", "c1", "l1");
  }

  @Test
  @DisplayName("findDescendantIds includes self and all descendants")
  void findDescendantIdsInclSelf() {
    Category a = repository.save(new Category(null, "A", "desc-a", null, 0));
    Category b = repository.save(new Category(a, "B", "desc-b", null, 0));
    Category c = repository.save(new Category(b, "C", "desc-c", null, 0));
    repository.save(new Category(null, "Other", "desc-other", null, 0));

    List<UUID> ids = repository.findDescendantIds(a.getId());

    assertThat(ids).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
  }

  @Test
  @DisplayName("self-parent → trigger raise CAT_CYCLE_DETECTED (cycle 0-level)")
  void selfParentRejected() {
    Category cat = new Category(null, "X", "self-x", null, 0);
    Category saved = repository.saveAndFlush(cat);

    saved.setParent(saved);

    // trg_categories_no_cycle là BEFORE trigger → chạy TRƯỚC khi Postgres evaluate
    // CHECK ck_categories_self_parent. Nên self-parent luôn báo CAT_CYCLE_DETECTED;
    // CHECK constraint chỉ là net dự phòng nếu trigger bị disable.
    assertThatThrownBy(() -> repository.saveAndFlush(saved))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CAT_CYCLE_DETECTED");
  }

  @Test
  @DisplayName("cycle detection trigger — A → B → C, move A under C raises CAT_CYCLE_DETECTED")
  @DirtiesContext // trigger rollback có thể ảnh hưởng state khác
  void cycleDetected() {
    Category a = repository.saveAndFlush(new Category(null, "A", "cy-a", null, 0));
    Category b = repository.saveAndFlush(new Category(a, "B", "cy-b", null, 0));
    Category c = repository.saveAndFlush(new Category(b, "C", "cy-c", null, 0));

    a.setParent(c);

    assertThatThrownBy(() -> repository.saveAndFlush(a))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("CAT_CYCLE_DETECTED");
  }

  @Test
  @DisplayName("findBySlug case-insensitive (citext)")
  void findBySlugCaseInsensitive() {
    repository.saveAndFlush(new Category(null, "Books", "books-citext", null, 0));

    assertThat(repository.findBySlug("books-citext")).isPresent();
    assertThat(repository.findBySlug("Books-Citext")).isPresent();
    assertThat(repository.findBySlug("BOOKS-CITEXT")).isPresent();
  }
}
