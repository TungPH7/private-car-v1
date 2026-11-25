package com.tungph7.learnpostgres.feature.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.dto.UpdateProductRequest;
import com.tungph7.learnpostgres.feature.product.mapper.ProductMapper;
import com.tungph7.learnpostgres.feature.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository repository;
  @Mock ProductMapper mapper;
  @InjectMocks ProductService service;

  @Test
  void getById_returns_product_when_present() {
    UUID id = UUID.randomUUID();
    Product p = new Product("X", "x", new BigDecimal("1.00"), null);
    when(repository.findById(id)).thenReturn(Optional.of(p));

    Product got = service.getById(id);

    assertThat(got).isSameAs(p);
  }

  @Test
  void getById_throws_NotFoundException_when_absent() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getById(id))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void getBySlug_returns_product_when_present() {
    Product p = new Product("X", "x", new BigDecimal("1.00"), null);
    when(repository.findBySlug("x")).thenReturn(Optional.of(p));

    Product got = service.getBySlug("x");

    assertThat(got).isSameAs(p);
  }

  @Test
  void getBySlug_throws_NotFoundException_when_absent() {
    when(repository.findBySlug("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getBySlug("missing"))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void search_without_name_returns_a_page_of_every_product() {
    Product p1 = new Product("X", "x", new BigDecimal("1.00"), null);
    Product p2 = new Product("Y", "y", new BigDecimal("2.00"), null);
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
    when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(p1, p2), pageable, 2));

    Page<Product> got = service.search(null, pageable);

    assertThat(got.getContent()).containsExactly(p1, p2);
    assertThat(got.getTotalElements()).isEqualTo(2);
  }

  @Test
  void search_with_a_blank_name_does_not_filter() {
    Pageable pageable = PageRequest.of(0, 10);
    when(repository.findAll(pageable)).thenReturn(Page.empty(pageable));

    service.search("   ", pageable);

    verify(repository).findAll(pageable);
  }

  @Test
  void search_with_a_name_filters_by_the_trimmed_name_ignoring_case() {
    Product p = new Product("Postgres Book", "pg", new BigDecimal("1.00"), null);
    Pageable pageable = PageRequest.of(1, 5, Sort.by("price"));
    when(repository.findByNameContainingIgnoreCase("post", pageable))
        .thenReturn(new PageImpl<>(List.of(p), pageable, 6));

    Page<Product> got = service.search("  post ", pageable);

    assertThat(got.getContent()).containsExactly(p);
    assertThat(got.getTotalPages()).isEqualTo(2);
  }

  @Test
  void search_refuses_a_sort_on_a_property_that_is_not_listed() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by("version"));

    assertThatThrownBy(() -> service.search(null, pageable))
        .isInstanceOf(BadRequestException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_PARAMETER)
        .hasMessageContaining("version");
    verify(repository, never()).findAll(pageable);
  }

  @Test
  void update_replaces_name_slug_price_and_description() {
    UUID id = UUID.randomUUID();
    Product p = new Product("Old", "old", new BigDecimal("1.00"), "old desc");
    when(repository.findById(id)).thenReturn(Optional.of(p));
    when(repository.findBySlug("new")).thenReturn(Optional.empty());
    when(repository.save(p)).thenReturn(p);

    Product got =
        service.update(id, new UpdateProductRequest("New", "new", new BigDecimal("9.50"), null));

    assertThat(got.getName()).isEqualTo("New");
    assertThat(got.getSlug()).isEqualTo("new");
    assertThat(got.getPrice()).isEqualByComparingTo("9.50");
    assertThat(got.getDescription()).isNull();
  }

  @Test
  void update_keeps_the_slug_of_the_same_product() {
    UUID id = UUID.randomUUID();
    Product p = new Product("Old", "same", new BigDecimal("1.00"), null);
    when(repository.findById(id)).thenReturn(Optional.of(p));
    when(repository.findBySlug("same")).thenReturn(Optional.of(p));
    when(repository.save(p)).thenReturn(p);

    Product got =
        service.update(id, new UpdateProductRequest("New", "same", new BigDecimal("2.00"), null));

    assertThat(got.getName()).isEqualTo("New");
  }

  @Test
  void update_throws_ConflictException_when_another_product_has_the_slug() {
    UUID id = UUID.randomUUID();
    Product p = new Product("Old", "old", new BigDecimal("1.00"), null);
    Product other = new Product("Other", "taken", new BigDecimal("1.00"), null);
    when(repository.findById(id)).thenReturn(Optional.of(p));
    when(repository.findBySlug("taken")).thenReturn(Optional.of(other));

    assertThatThrownBy(
            () ->
                service.update(
                    id, new UpdateProductRequest("New", "taken", new BigDecimal("2.00"), null)))
        .isInstanceOf(ConflictException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_SLUG_DUPLICATED);
    verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void update_throws_NotFoundException_when_absent() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.update(
                    id, new UpdateProductRequest("New", "new", new BigDecimal("2.00"), null)))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void create_persists_when_slug_unique() {
    CreateProductRequest req = new CreateProductRequest("X", "x", new BigDecimal("1.00"), null);
    Product entity = new Product("X", "x", new BigDecimal("1.00"), null);

    when(repository.findBySlug("x")).thenReturn(Optional.empty());
    when(mapper.toEntity(req)).thenReturn(entity);
    when(repository.save(entity)).thenReturn(entity);

    Product created = service.create(req);

    assertThat(created).isSameAs(entity);
    assertThat(created.getSlug()).isEqualTo("x");
  }

  @Test
  void create_throws_ConflictException_when_slug_exists() {
    CreateProductRequest req = new CreateProductRequest("X", "x", new BigDecimal("1.00"), null);
    Product existing = new Product("Y", "x", new BigDecimal("2.00"), null);
    when(repository.findBySlug("x")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(ConflictException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_SLUG_DUPLICATED);
    verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updatePrice_updates_price_and_saves() {
    UUID id = UUID.randomUUID();
    Product existing = new Product("X", "x", new BigDecimal("1.00"), null);
    when(repository.findById(id)).thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);

    Product updated = service.updatePrice(id, new BigDecimal("9.99"));

    assertThat(updated.getPrice()).isEqualByComparingTo("9.99");
  }

  @Test
  void updatePrice_throws_NotFoundException_when_absent() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updatePrice(id, new BigDecimal("9.99")))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void bulkUpdatePriceBySlug_returns_count_when_rows_updated() {
    when(repository.bulkUpdatePriceBySlug("x", new BigDecimal("3.00"))).thenReturn(2);

    int updated = service.bulkUpdatePriceBySlug("x", new BigDecimal("3.00"));

    assertThat(updated).isEqualTo(2);
  }

  @Test
  void bulkUpdatePriceBySlug_throws_NotFoundException_when_zero_updated() {
    when(repository.bulkUpdatePriceBySlug("missing", new BigDecimal("3.00"))).thenReturn(0);

    assertThatThrownBy(() -> service.bulkUpdatePriceBySlug("missing", new BigDecimal("3.00")))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
  }

  @Test
  void delete_calls_deleteById_when_exists() {
    UUID id = UUID.randomUUID();
    when(repository.existsById(id)).thenReturn(true);

    service.delete(id);

    verify(repository).deleteById(id);
  }

  @Test
  void delete_throws_NotFoundException_when_absent() {
    UUID id = UUID.randomUUID();
    when(repository.existsById(id)).thenReturn(false);

    assertThatThrownBy(() -> service.delete(id))
        .isInstanceOf(NotFoundException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
    verify(repository, never()).deleteById(id);
  }
}
