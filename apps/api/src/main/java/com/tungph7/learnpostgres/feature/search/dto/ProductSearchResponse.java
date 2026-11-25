package com.tungph7.learnpostgres.feature.search.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;

/**
 * Một sản phẩm trong kết quả tìm.
 *
 * @param sku {@code null} với sản phẩm chưa có SKU
 * @param categoryId {@code null} với sản phẩm chưa phân loại
 * @param score mức giống từ khoá, 0..1, làm tròn 2 chữ số: {@code word_similarity} giữa từ khoá và
 *     tên, cả hai đã qua {@code app.search_normalize}; {@code 1} khi từ khoá trùng SKU. {@code
 *     null} khi tìm không có từ khoá
 */
public record ProductSearchResponse(
    UUID id,
    String name,
    String slug,
    String sku,
    BigDecimal price,
    ProductStatus status,
    UUID categoryId,
    Instant createdAt,
    BigDecimal score) {}
