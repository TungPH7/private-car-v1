package com.tungph7.learnpostgres.feature.product.repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.product.domain.Product;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

  Optional<Product> findBySlug(String slug);

  /**
   * Sản phẩm có tên chứa {@code name}, không phân biệt hoa thường. Spring Data thoát {@code %} và
   * {@code _} của tham số, nên người gọi không chèn được ký tự đại diện của LIKE.
   */
  Page<Product> findByNameContainingIgnoreCase(String name, Pageable pageable);

  /**
   * Bulk update price qua JPQL @Modifying — KHÔNG fire JPA Auditing listener (đây chính là blind
   * spot cho file 01 mục 3.1).
   *
   * <p>Đặt method này để demo: trigger app.set_updated_at() bù được JPA blind spot. Production thực
   * sự nên hạn chế bulk update, nhưng đây là test minh họa defense-in-depth.
   */
  @Modifying
  @Query("UPDATE Product p SET p.price = :price WHERE p.slug = :slug")
  int bulkUpdatePriceBySlug(String slug, BigDecimal price);
}
