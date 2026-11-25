package com.tungph7.learnpostgres.feature.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.domain.CitextType;

/**
 * Category — cây phân loại sản phẩm n cấp.
 *
 * <p>Quan hệ self-ref: {@link #parent} → Category cha. NULL = root. Cycle prevention: - CHECK
 * constraint `ck_categories_self_parent` chặn self-parent (1-level). - Trigger
 * `trg_categories_no_cycle` chặn cycle dài (walk-up ancestors).
 *
 * <p>Self-ref fetch type LAZY mặc định cho @ManyToOne sẽ tạo proxy parent — không eager load cả
 * nhánh. Khi cần tree đầy đủ → dùng recursive CTE qua native query (xem
 * CategoryRepository.findTree()).
 */
@Entity
@Table(name = "categories", schema = "app")
public class Category extends BaseEntity {

  /**
   * Parent category — self-ref. NULL = root.
   *
   * <p>Mapping qua @ManyToOne thay vì @Column UUID raw để: - Tiện set parent từ object (cleaner
   * API). - Hibernate quản FK constraint khi flush. - LAZY: chỉ load proxy, không tự đọc đệ quy.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  private Category parent;

  @Column(name = "name", nullable = false, length = 160)
  private String name;

  /**
   * URL-friendly identifier, UNIQUE, case-insensitive (citext). Mapping qua CitextType — xem
   * Product entity cho lý do chi tiết.
   */
  @Type(CitextType.class)
  @Column(name = "slug", nullable = false, columnDefinition = "public.citext")
  private String slug;

  @Column(name = "description")
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder = 0;

  // -----------------------------------------------------------------
  // Constructors
  // -----------------------------------------------------------------

  protected Category() {
    // for JPA
  }

  public Category(Category parent, String name, String slug, String description, int sortOrder) {
    this.parent = parent;
    this.name = name;
    this.slug = slug;
    this.description = description;
    this.sortOrder = sortOrder;
    this.active = true;
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public Category getParent() {
    return parent;
  }

  public void setParent(Category parent) {
    this.parent = parent;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }
}
