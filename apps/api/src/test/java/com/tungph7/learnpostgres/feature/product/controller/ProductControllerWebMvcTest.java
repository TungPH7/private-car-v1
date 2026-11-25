package com.tungph7.learnpostgres.feature.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.dto.UpdateProductRequest;
import com.tungph7.learnpostgres.feature.product.mapper.ProductMapper;
import com.tungph7.learnpostgres.feature.product.service.ProductService;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProductController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class, ProblemDetailFactory.class})
@EnableConfigurationProperties(LoggingProperties.class)
class ProductControllerWebMvcTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;

  @MockitoBean ProductService service;

  /** Mapper là MapStruct singleton → bind bean thật cho slice. */
  @org.springframework.boot.test.context.TestConfiguration
  static class MapperConfig {
    @org.springframework.context.annotation.Bean
    ProductMapper productMapper() {
      return Mappers.getMapper(ProductMapper.class);
    }
  }

  @Test
  void POST_create_returns_201_and_location() throws Exception {
    CreateProductRequest req = new CreateProductRequest("X", "x", new BigDecimal("1.00"), null);
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead00");
    Product saved = productWithId(id, "X", "x", new BigDecimal("1.00"));

    when(service.create(any(CreateProductRequest.class))).thenReturn(saved);

    mockMvc
        .perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.endsWith("/api/products/" + id)))
        .andExpect(jsonPath("$.slug").value("x"));
  }

  @Test
  void POST_create_returns_400_problem_when_blank_name() throws Exception {
    String body = "{\"name\":\"\",\"slug\":\"x\",\"price\":1.00}";

    mockMvc
        .perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void GET_byId_returns_404_problem_when_absent() throws Exception {
    UUID id = UUID.fromString("00000000-0000-7000-8000-000000000000");
    when(service.getById(id))
        .thenThrow(
            new NotFoundException(
                ErrorCode.PRODUCT_NOT_FOUND, "Product %s not found.".formatted(id)));

    mockMvc
        .perform(get("/api/products/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_NOT_FOUND.code()))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void GET_byId_returns_200_when_found() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead00");
    Product p = productWithId(id, "Hello", "hello", new BigDecimal("3.50"));
    when(service.getById(id)).thenReturn(p);

    mockMvc
        .perform(get("/api/products/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("hello"))
        .andExpect(jsonPath("$.name").value("Hello"));
  }

  @Test
  void GET_list_returns_a_page_of_products() throws Exception {
    Product p1 =
        productWithId(
            UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead01"),
            "A",
            "a",
            new BigDecimal("1.00"));
    Product p2 =
        productWithId(
            UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead02"),
            "B",
            "b",
            new BigDecimal("2.00"));
    when(service.search(eq(null), any(Pageable.class)))
        .thenAnswer(call -> new PageImpl<>(List.of(p1, p2), call.<Pageable>getArgument(1), 12));

    mockMvc
        .perform(get("/api/products").param("page", "1").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].slug").value("a"))
        .andExpect(jsonPath("$.content[1].slug").value("b"))
        .andExpect(jsonPath("$.page.number").value(1))
        .andExpect(jsonPath("$.page.size").value(2))
        .andExpect(jsonPath("$.page.totalElements").value(12))
        .andExpect(jsonPath("$.page.totalPages").value(6));
  }

  @Test
  void GET_list_passes_the_name_and_sorts_newest_first_by_default() throws Exception {
    when(service.search(eq("pg"), any(Pageable.class)))
        .thenAnswer(call -> new PageImpl<>(List.<Product>of(), call.<Pageable>getArgument(1), 0));

    mockMvc.perform(get("/api/products").param("name", "pg")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(service).search(eq("pg"), pageable.capture());
    org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isZero();
    org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    org.assertj.core.api.Assertions.assertThat(pageable.getValue().getSort())
        .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Test
  void PUT_update_returns_200_with_the_updated_product() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead05");
    Product updated = productWithId(id, "New", "new", new BigDecimal("9.50"));
    when(service.update(eq(id), any(UpdateProductRequest.class))).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/products/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\",\"slug\":\"new\",\"price\":9.50}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New"))
        .andExpect(jsonPath("$.slug").value("new"));
  }

  @Test
  void PUT_update_returns_400_problem_when_the_slug_is_not_lowercase() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead05");

    mockMvc
        .perform(
            put("/api/products/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\",\"slug\":\"New Slug\",\"price\":9.50}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.errors[0].field").value("slug"));
  }

  @Test
  void PUT_updatePrice_returns_200_with_updated_response() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead03");
    Product updated = productWithId(id, "X", "x", new BigDecimal("5.55"));
    when(service.updatePrice(eq(id), any(BigDecimal.class))).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/products/{id}/price", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":5.55}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.price").value(5.55));
  }

  @Test
  void PUT_bulkUpdatePrice_returns_count() throws Exception {
    when(service.bulkUpdatePriceBySlug(eq("x"), any(BigDecimal.class))).thenReturn(3);

    mockMvc
        .perform(
            put("/api/products/bulk-price/{slug}", "x")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":7.77}"))
        .andExpect(status().isOk())
        .andExpect(content().string("3"));
  }

  @Test
  void DELETE_returns_204_when_present() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead04");
    doNothing().when(service).delete(id);

    mockMvc.perform(delete("/api/products/{id}", id)).andExpect(status().isNoContent());

    verify(service).delete(id);
  }

  @Test
  void DELETE_returns_404_when_absent() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead05");
    doThrow(
            new NotFoundException(
                ErrorCode.PRODUCT_NOT_FOUND, "Product %s not found.".formatted(id)))
        .when(service)
        .delete(id);

    mockMvc
        .perform(delete("/api/products/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_NOT_FOUND.code()));
  }

  @Test
  void GET_byId_returns_400_when_invalid_uuid() throws Exception {
    mockMvc
        .perform(get("/api/products/{id}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
  }

  /** Helper: gán id qua reflection (BaseEntity.setId package-private). */
  private static Product productWithId(UUID id, String name, String slug, BigDecimal price) {
    Product p = new Product(name, slug, price, null);
    try {
      Field f = BaseEntity.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(p, id);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    return p;
  }
}
