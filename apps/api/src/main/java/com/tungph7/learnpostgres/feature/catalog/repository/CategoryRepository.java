package com.tungph7.learnpostgres.feature.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryPathRow;
import com.tungph7.learnpostgres.feature.catalog.repository.projection.CategoryTreeRow;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

  // -----------------------------------------------------------------
  // Simple Spring Data finder (derived query)
  // -----------------------------------------------------------------

  Optional<Category> findBySlug(String slug);

  boolean existsBySlug(String slug);

  List<Category> findByParentIsNullOrderBySortOrderAsc();

  List<Category> findByParentIdOrderBySortOrderAsc(UUID parentId);

  /**
   * Kiểm tra category có children active nào không — dùng khi archive. Trả TRUE nếu CÒN child
   * active → không cho archive.
   */
  boolean existsByParentIdAndActiveTrue(UUID parentId);

  // -----------------------------------------------------------------
  // Recursive CTE native queries — trả projection (DTO interface)
  // -----------------------------------------------------------------

  /**
   * Trả về cây từ root xuống, dạng flat list có depth + path.
   *
   * <p>Recursive CTE: - Anchor: rows có parent_id IS NULL (root). - Recursive: JOIN child với
   * parent đã có trong CTE. - Ordering: dùng sort_key array để giữ thứ tự "cha trước, con sau, anh
   * em theo sort_order".
   *
   * <p>UNION ALL (không phải UNION) — tiết kiệm hash dedup; cycle chặn ở trigger DB.
   */
  @Query(
      value =
          """
WITH RECURSIVE cat_tree AS (
    SELECT
        c.id, c.parent_id, c.name, c.slug, c.is_active, c.sort_order,
        0                                    AS depth,
        c.name::text                         AS path,
        ARRAY[c.sort_order, 0]::int[]        AS sort_key
    FROM app.categories c
    WHERE c.parent_id IS NULL

    UNION ALL

    SELECT
        child.id, child.parent_id, child.name, child.slug, child.is_active, child.sort_order,
        parent.depth + 1,
        parent.path || ' > ' || child.name,
        parent.sort_key || child.sort_order
    FROM app.categories child
    JOIN cat_tree parent ON child.parent_id = parent.id
)
SELECT
    id        AS id,
    parent_id AS parentId,
    name      AS name,
    slug::text AS slug,
    is_active AS active,
    depth     AS depth,
    path      AS path
FROM cat_tree
ORDER BY sort_key
""",
      nativeQuery = true)
  List<CategoryTreeRow> findTree();

  /**
   * Trả về path từ root xuống tới category :id (breadcrumb).
   *
   * <p>Recursive CTE NGƯỢC: anchor = node có id = :id, recursive walk lên qua parent_id. Khi
   * parent_id IS NULL → đến root → stop.
   *
   * <p>rev_depth: 0 ở node bắt đầu (leaf), tăng dần khi đi lên. ORDER BY rev_depth DESC → root xuất
   * hiện trước, leaf cuối → đúng thứ tự breadcrumb hiển thị.
   */
  @Query(
      value =
          """
          WITH RECURSIVE ancestors AS (
              SELECT id, parent_id, name, slug::text AS slug, 0 AS rev_depth
              FROM app.categories
              WHERE id = :id

              UNION ALL

              SELECT c.id, c.parent_id, c.name, c.slug::text AS slug, a.rev_depth + 1
              FROM app.categories c
              JOIN ancestors a ON c.id = a.parent_id
          )
          SELECT
              id   AS id,
              name AS name,
              slug AS slug
          FROM ancestors
          ORDER BY rev_depth DESC
          """,
      nativeQuery = true)
  List<CategoryPathRow> findPath(@Param("id") UUID id);

  /**
   * List ID của category :id và toàn bộ descendants (con cháu). Dùng để filter products theo
   * category với rollup descendants.
   */
  @Query(
      value =
          """
          WITH RECURSIVE descendants AS (
              SELECT id FROM app.categories WHERE id = :id
              UNION ALL
              SELECT c.id
              FROM app.categories c
              JOIN descendants d ON c.parent_id = d.id
          )
          SELECT id FROM descendants
          """,
      nativeQuery = true)
  List<UUID> findDescendantIds(@Param("id") UUID id);

  // -----------------------------------------------------------------
  // Mass operation (archive, deactivate)
  // -----------------------------------------------------------------

  /**
   * Archive — set is_active = FALSE. Caller phải check không còn active children trước khi gọi (qua
   * existsByParentIdAndActiveTrue).
   *
   * <p>Bulk update qua JPQL @Modifying — bypass JPA Auditing listener, nhưng trigger
   * trg_categories_set_updated_at bù được updated_at.
   */
  @Modifying
  @Query("UPDATE Category c SET c.active = false WHERE c.id = :id")
  int archive(@Param("id") UUID id);
}
