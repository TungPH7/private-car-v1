package com.tungph7.learnpostgres.feature.product.mapper;

import org.mapstruct.Mapper;

import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.dto.ProductResponse;

@Mapper
public interface ProductMapper {

  /**
   * Map request → entity. id và audit field do BaseEntity / @PrePersist / JPA Auditing tự fill —
   * KHÔNG map từ request (cũng không có trong DTO).
   */
  Product toEntity(CreateProductRequest req);

  /**
   * Map entity → response. Field `version`, `updatedAt`, `createdBy`, `updatedBy` của entity KHÔNG
   * có trong response → unmappedSourcePolicy mặc định = WARN (không ERROR).
   */
  ProductResponse toResponse(Product product);
}
