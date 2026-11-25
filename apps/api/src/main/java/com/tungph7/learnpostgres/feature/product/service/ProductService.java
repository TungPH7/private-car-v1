package com.tungph7.learnpostgres.feature.product.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.mapper.ProductMapper;
import com.tungph7.learnpostgres.feature.product.repository.ProductRepository;

/**
 * Business logic cho Product.
 *
 * <p>Trách nhiệm: - Áp dụng business rule (vd: slug đã tồn tại → ConflictException thay vì để DB
 * throw lên dạng raw). - Quản lý transaction boundary (@Transactional ở method level). - Throw
 * custom exception cho controller.
 *
 * <p>Read-only mặc định (class-level @Transactional(readOnly = true)) để giảm lock taken; method
 * ghi tự override readOnly = false.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

  private final ProductRepository repository;
  private final ProductMapper mapper;

  public ProductService(ProductRepository repository, ProductMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  public Product getById(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PRODUCT_NOT_FOUND, "Product with id '%s' not found.".formatted(id)));
  }

  public Product getBySlug(String slug) {
    return repository
        .findBySlug(slug)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PRODUCT_NOT_FOUND,
                    "Product with slug '%s' not found.".formatted(slug)));
  }

  public List<Product> list() {
    return repository.findAll();
  }

  @Transactional
  public Product create(CreateProductRequest req) {
    // Pre-check slug để trả ConflictException tường minh thay vì để
    // DB UNIQUE constraint throw DataIntegrityViolationException.
    // Vẫn có race condition: 2 request POST cùng slug song song có thể
    // qua check rồi cả 2 INSERT — DB constraint sẽ catch (handler đã map
    // sang 409 ở file 03). Pre-check là UX (lỗi sớm + message rõ),
    // không thay thế DB constraint.
    repository
        .findBySlug(req.slug())
        .ifPresent(
            p -> {
              throw new ConflictException(
                  ErrorCode.PRODUCT_SLUG_DUPLICATED,
                  "Product slug '%s' already exists.".formatted(req.slug()));
            });
    return repository.save(mapper.toEntity(req));
  }

  @Transactional
  public Product updatePrice(UUID id, BigDecimal newPrice) {
    Product product = getById(id);
    product.setPrice(newPrice);
    return repository.save(product);
    // Optimistic lock: nếu version trong DB đã tăng so với khi load
    // (do request khác update song song), Hibernate throw
    // ObjectOptimisticLockingFailureException → handler map 409.
  }

  @Transactional
  public int bulkUpdatePriceBySlug(String slug, BigDecimal newPrice) {
    // Bulk update qua JPQL — bypass JPA Auditing listener;
    // trigger app.set_updated_at() (folder 05) đảm bảo updated_at refresh.
    int updated = repository.bulkUpdatePriceBySlug(slug, newPrice);
    if (updated == 0) {
      throw new NotFoundException(
          ErrorCode.PRODUCT_NOT_FOUND, "No product with slug '%s'.".formatted(slug));
    }
    return updated;
  }

  @Transactional
  public void delete(UUID id) {
    if (!repository.existsById(id)) {
      throw new NotFoundException(
          ErrorCode.PRODUCT_NOT_FOUND, "Product with id '%s' not found.".formatted(id));
    }
    repository.deleteById(id);
  }
}
