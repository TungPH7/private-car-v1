package com.tungph7.learnpostgres.feature.product.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.domain.CitextType;

/**
 * Catalog product — entity nghiệp vụ đầu tiên kế thừa BaseEntity.
 *
 * <p>Mục tiêu: - Verify pipeline BaseEntity (UUIDv7) + JPA Auditing + DB trigger end-to-end. - Làm
 * running example cho folder 06+ (exception, validation, OpenAPI).
 *
 * <p>Naming: schema 'app' khai báo tường minh ở @Table — khớp phần tử đầu của JDBC URL
 * currentSchema=app,public (folder 03) nhưng explicit = an toàn hơn implicit. 'public' có trong
 * search_path là để thấy operator của citext, không phải để resolve tên bảng.
 */
@Entity
@Table(name = "products", schema = "app")
public class Product extends BaseEntity {

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /**
   * URL-friendly identifier, case-insensitive UNIQUE.
   *
   * <p>Dùng @Type(CitextType.class) thay vì @JdbcTypeCode(SqlTypes.OTHER): -
   * CitextType.getSqlType() = Types.OTHER → ddl-auto=validate match metadata citext. -
   * CitextType.nullSafeSet() dùng setString() tường minh → pgjdbc gửi text với Oid.UNSPECIFIED (do
   * `stringtype=unspecified` ở URL) → server cast về citext → DB lưu plain text.
   * - @JdbcTypeCode(SqlTypes.OTHER) đơn lẻ KHÔNG đủ vì Hibernate's ObjectJdbcType bind String qua
   * đường binary (bytea) → DB lưu hex string (xem QnA-2.md Q3).
   */
  @Type(CitextType.class)
  @Column(name = "slug", nullable = false, columnDefinition = "public.citext")
  private String slug;

  /**
   * Tiền — BigDecimal mapping numeric(12,2). KHÔNG dùng double/float. precision = total digits,
   * scale = digits sau decimal point.
   */
  @Column(name = "price", nullable = false, precision = 12, scale = 2)
  private BigDecimal price;

  @Column(name = "description")
  private String description;

  // -----------------------------------------------------------------
  // Constructor
  // -----------------------------------------------------------------

  /** JPA cần default constructor (protected để tránh business code dùng). */
  protected Product() {
    // for JPA
  }

  public Product(String name, String slug, BigDecimal price, String description) {
    this.name = name;
    this.slug = slug;
    this.price = price;
    this.description = description;
  }

  // -----------------------------------------------------------------
  // Getters / setters — viết tay (Lombok có thể dùng nếu thích)
  // -----------------------------------------------------------------

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

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
