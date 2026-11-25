package com.tungph7.learnpostgres.feature.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import com.tungph7.learnpostgres.feature.catalog.domain.Category;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryPathItem;
import com.tungph7.learnpostgres.feature.catalog.dto.CategoryTreeNode;
import com.tungph7.learnpostgres.feature.catalog.dto.CreateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.MoveCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.dto.UpdateCategoryRequest;
import com.tungph7.learnpostgres.feature.catalog.mapper.CategoryMapper;
import com.tungph7.learnpostgres.feature.catalog.service.CategoryService;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CategoryController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class, ProblemDetailFactory.class})
@EnableConfigurationProperties(LoggingProperties.class)
class CategoryControllerWebMvcTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;

  @MockitoBean CategoryService service;

  @org.springframework.boot.test.context.TestConfiguration
  static class MapperConfig {
    @org.springframework.context.annotation.Bean
    CategoryMapper categoryMapper() {
      return Mappers.getMapper(CategoryMapper.class);
    }
  }

  // ---------------------------------------------------------------
  // POST /api/v1/categories
  // ---------------------------------------------------------------

  @Test
  void POST_create_returns_201_and_location() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead10");
    Category saved = categoryWithId(id, "Electronics", "electronics");

    when(service.create(any(CreateCategoryRequest.class))).thenReturn(saved);

    String body = "{\"name\":\"Electronics\",\"slug\":\"electronics\"}";

    mockMvc
        .perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/categories/" + id)))
        .andExpect(jsonPath("$.slug").value("electronics"));
  }

  @Test
  void POST_create_returns_400_when_name_blank() throws Exception {
    String body = "{\"name\":\"\",\"slug\":\"electronics\"}";

    mockMvc
        .perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
  }

  @Test
  void POST_create_returns_400_when_slug_invalid_pattern() throws Exception {
    String body = "{\"name\":\"Electronics\",\"slug\":\"Electronics With Spaces\"}";

    mockMvc
        .perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
  }

  // ---------------------------------------------------------------
  // PATCH /api/v1/categories/{id}
  // ---------------------------------------------------------------

  @Test
  void PATCH_update_returns_200() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead11");
    Category updated = categoryWithId(id, "Electronics v2", "electronics-v2");

    when(service.update(eq(id), any(UpdateCategoryRequest.class))).thenReturn(updated);

    String body = "{\"version\":0,\"name\":\"Electronics v2\",\"slug\":\"electronics-v2\"}";

    mockMvc
        .perform(
            patch("/api/v1/categories/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("electronics-v2"));
  }

  @Test
  void PATCH_update_returns_404_when_not_found() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead12");

    when(service.update(eq(id), any(UpdateCategoryRequest.class)))
        .thenThrow(new NotFoundException(ErrorCode.CAT_NOT_FOUND, "Category not found"));

    String body = "{\"version\":0,\"name\":\"X\",\"slug\":\"x\"}";

    mockMvc
        .perform(
            patch("/api/v1/categories/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.CAT_NOT_FOUND.code()));
  }

  // ---------------------------------------------------------------
  // POST /api/v1/categories/{id}/move
  // ---------------------------------------------------------------

  @Test
  void POST_move_returns_200() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead13");
    UUID newParentId = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead14");
    Category moved = categoryWithId(id, "Electronics", "electronics");

    when(service.move(eq(id), any(MoveCategoryRequest.class))).thenReturn(moved);

    String body = "{\"newParentId\":\"" + newParentId + "\"}";

    mockMvc
        .perform(
            post("/api/v1/categories/{id}/move", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("electronics"));
  }

  // ---------------------------------------------------------------
  // POST /api/v1/categories/{id}/archive
  // ---------------------------------------------------------------

  @Test
  void POST_archive_returns_204() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead15");
    doNothing().when(service).archive(id);

    mockMvc.perform(post("/api/v1/categories/{id}/archive", id)).andExpect(status().isNoContent());

    verify(service).archive(id);
  }

  // ---------------------------------------------------------------
  // DELETE /api/v1/categories/{id}
  // ---------------------------------------------------------------

  @Test
  void DELETE_returns_204_when_deleted() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead16");
    doNothing().when(service).delete(id);

    mockMvc.perform(delete("/api/v1/categories/{id}", id)).andExpect(status().isNoContent());

    verify(service).delete(id);
  }

  @Test
  void DELETE_returns_404_when_not_found() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead17");
    doThrow(new NotFoundException(ErrorCode.CAT_NOT_FOUND, "Category not found"))
        .when(service)
        .delete(id);

    mockMvc
        .perform(delete("/api/v1/categories/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.CAT_NOT_FOUND.code()));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/{id}
  // ---------------------------------------------------------------

  @Test
  void GET_byId_returns_200_when_found() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead18");
    Category cat = categoryWithId(id, "Electronics", "electronics");
    when(service.getById(id)).thenReturn(cat);

    mockMvc
        .perform(get("/api/v1/categories/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Electronics"))
        .andExpect(jsonPath("$.slug").value("electronics"));
  }

  @Test
  void GET_byId_returns_404_when_absent() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead19");
    when(service.getById(id))
        .thenThrow(new NotFoundException(ErrorCode.CAT_NOT_FOUND, "Category not found"));

    mockMvc
        .perform(get("/api/v1/categories/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.CAT_NOT_FOUND.code()));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/by-slug/{slug}
  // ---------------------------------------------------------------

  @Test
  void GET_bySlug_returns_200_when_found() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead20");
    Category cat = categoryWithId(id, "Electronics", "electronics");
    when(service.getBySlug("electronics")).thenReturn(cat);

    mockMvc
        .perform(get("/api/v1/categories/by-slug/{slug}", "electronics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("electronics"));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/roots
  // ---------------------------------------------------------------

  @Test
  void GET_roots_returns_list() throws Exception {
    UUID id1 = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead21");
    UUID id2 = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead22");
    when(service.listRoots())
        .thenReturn(
            List.of(
                categoryWithId(id1, "Electronics", "electronics"),
                categoryWithId(id2, "Clothing", "clothing")));

    mockMvc
        .perform(get("/api/v1/categories/roots"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].slug").value("electronics"))
        .andExpect(jsonPath("$[1].slug").value("clothing"));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/{id}/children
  // ---------------------------------------------------------------

  @Test
  void GET_children_returns_list() throws Exception {
    UUID parentId = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead23");
    UUID childId = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead24");
    when(service.listChildren(parentId))
        .thenReturn(List.of(categoryWithId(childId, "Phones", "phones")));

    mockMvc
        .perform(get("/api/v1/categories/{id}/children", parentId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].slug").value("phones"));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/tree
  // ---------------------------------------------------------------

  @Test
  void GET_tree_returns_flat_list() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead25");
    CategoryTreeNode node =
        new CategoryTreeNode(id, null, "Electronics", "electronics", true, 0, "electronics");
    when(service.tree()).thenReturn(List.of(node));

    mockMvc
        .perform(get("/api/v1/categories/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].slug").value("electronics"))
        .andExpect(jsonPath("$[0].depth").value(0));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/{id}/path
  // ---------------------------------------------------------------

  @Test
  void GET_path_returns_breadcrumb() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead26");
    UUID rootId = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead27");
    List<CategoryPathItem> path =
        List.of(
            new CategoryPathItem(rootId, "Electronics", "electronics"),
            new CategoryPathItem(id, "Phones", "phones"));
    when(service.path(id)).thenReturn(path);

    mockMvc
        .perform(get("/api/v1/categories/{id}/path", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].slug").value("electronics"))
        .andExpect(jsonPath("$[1].slug").value("phones"));
  }

  // ---------------------------------------------------------------
  // GET /api/v1/categories/{id}/descendants
  // ---------------------------------------------------------------

  @Test
  void GET_descendants_returns_uuid_list() throws Exception {
    UUID id = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead28");
    UUID child1 = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead29");
    UUID child2 = UUID.fromString("0193c1f4-7b9a-7a3c-8d2e-aa1c98dead30");
    when(service.descendantIds(id)).thenReturn(List.of(id, child1, child2));

    mockMvc
        .perform(get("/api/v1/categories/{id}/descendants", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  // ---------------------------------------------------------------
  // Helper
  // ---------------------------------------------------------------

  private static Category categoryWithId(UUID id, String name, String slug) {
    Category cat = new Category(null, name, slug, null, 0);
    try {
      Field f = BaseEntity.class.getDeclaredField("id");
      f.setAccessible(true);
      f.set(cat, id);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    return cat;
  }
}
