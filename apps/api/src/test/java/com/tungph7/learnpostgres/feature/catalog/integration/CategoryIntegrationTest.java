package com.tungph7.learnpostgres.feature.catalog.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class CategoryIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  /**
   * Hai cái bẫy ở đây:
   *
   * <p>1. Hikari cấu hình auto-commit=false → JdbcTemplate chạy ngoài transaction sẽ bị rollback
   * lúc trả connection về pool, DELETE thành no-op im lặng. Phải bọc trong TransactionTemplate.
   *
   * <p>2. FK categories_parent_id_fkey là ON DELETE RESTRICT — Postgres check ngay từng row, không
   * hoãn tới cuối statement. Xoá cả cây trong 1 statement sẽ vi phạm FK nếu parent bị xoá trước
   * con, nên xoá lá trước rồi lặp tới khi sạch.
   */
  @BeforeEach
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM app.products WHERE slug LIKE 'it-%'");
          int deleted;
          do {
            deleted =
                jdbc.update(
                    """
                    DELETE FROM app.categories c
                    WHERE c.slug LIKE 'it-%'
                      AND NOT EXISTS (SELECT 1 FROM app.categories ch WHERE ch.parent_id = c.id)
                    """);
          } while (deleted > 0);
        });
  }

  // -----------------------------------------------------------------
  // POST /api/v1/categories
  // -----------------------------------------------------------------

  @Test
  @DisplayName("POST create root → 201 + Location + body")
  void createRoot() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
{ "parentId": null, "name": "Electronics", "slug": "it-electronics", "sortOrder": 1 }
"""))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.slug").value("it-electronics"))
        .andExpect(jsonPath("$.parentId").isEmpty())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @DisplayName("POST create with duplicate slug → 409 CAT_SLUG_TAKEN")
  void createDuplicateSlug() throws Exception {
    String body =
        """
        { "name": "A", "slug": "it-dup" }
        """;
    mockMvc
        .perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    mockMvc
        .perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("CAT_SLUG_TAKEN"));
  }

  @Test
  @DisplayName("POST create with parentId not found → 404 CAT_PARENT_NOT_FOUND")
  void createParentNotFound() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "parentId": "00000000-0000-0000-0000-000000000000",
                      "name": "X", "slug": "it-missing-parent" }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CAT_PARENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("POST create slug with invalid pattern → 400")
  void createInvalidSlug() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "X", "slug": "INVALID Slug!" }
                    """))
        .andExpect(status().isBadRequest());
  }

  // -----------------------------------------------------------------
  // GET tree
  // -----------------------------------------------------------------

  @Test
  @DisplayName("GET /tree returns full hierarchy with depth and path")
  void getTreeReturnsHierarchy() throws Exception {
    String parentId = createCategory(null, "Root", "it-root");
    String childId = createCategory(parentId, "Child", "it-child");
    createCategory(childId, "Grandchild", "it-gc");

    mockMvc
        .perform(get("/api/v1/categories/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].depth").value(0))
        .andExpect(jsonPath("$[0].name").value("Root"))
        .andExpect(jsonPath("$[1].depth").value(1))
        .andExpect(jsonPath("$[1].path").value("Root > Child"))
        .andExpect(jsonPath("$[2].depth").value(2));
  }

  // -----------------------------------------------------------------
  // POST /move — cycle detection
  // -----------------------------------------------------------------

  @Test
  @DisplayName("POST /move creating cycle → 409 CAT_CYCLE_DETECTED")
  void moveCreatingCycle() throws Exception {
    String aId = createCategory(null, "A", "it-cy-a");
    String bId = createCategory(aId, "B", "it-cy-b");
    String cId = createCategory(bId, "C", "it-cy-c");

    mockMvc
        .perform(
            post("/api/v1/categories/" + aId + "/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"newParentId\": \"" + cId + "\" }"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAT_CYCLE_DETECTED"));
  }

  @Test
  @DisplayName("POST /move under self → 400 CAT_SELF_PARENT")
  void moveUnderSelf() throws Exception {
    String id = createCategory(null, "X", "it-self");

    mockMvc
        .perform(
            post("/api/v1/categories/" + id + "/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"newParentId\": \"" + id + "\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CAT_SELF_PARENT"));
  }

  // -----------------------------------------------------------------
  // POST /archive
  // -----------------------------------------------------------------

  @Test
  @DisplayName("POST /archive with active children → 409 CAT_HAS_ACTIVE_CHILDREN")
  void archiveWithChildren() throws Exception {
    String parentId = createCategory(null, "Parent", "it-ar-p");
    createCategory(parentId, "Child", "it-ar-c");

    mockMvc
        .perform(post("/api/v1/categories/" + parentId + "/archive"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAT_HAS_ACTIVE_CHILDREN"));
  }

  @Test
  @DisplayName("POST /archive leaf → 204")
  void archiveLeaf() throws Exception {
    String id = createCategory(null, "X", "it-ar-leaf");

    mockMvc
        .perform(post("/api/v1/categories/" + id + "/archive"))
        .andExpect(status().isNoContent());
  }

  // -----------------------------------------------------------------
  // DELETE
  // -----------------------------------------------------------------

  @Test
  @DisplayName("DELETE with FK reference (child category) → 409 CAT_HAS_REFERENCES")
  void deleteWithChildren() throws Exception {
    String parentId = createCategory(null, "P", "it-del-p");
    createCategory(parentId, "C", "it-del-c");

    mockMvc
        .perform(delete("/api/v1/categories/" + parentId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CAT_HAS_REFERENCES"));
  }

  // -----------------------------------------------------------------
  // GET /path
  // -----------------------------------------------------------------

  @Test
  @DisplayName("GET /path returns root → leaf")
  void getPath() throws Exception {
    String aId = createCategory(null, "A", "it-p-a");
    String bId = createCategory(aId, "B", "it-p-b");
    String cId = createCategory(bId, "C", "it-p-c");

    mockMvc
        .perform(get("/api/v1/categories/" + cId + "/path"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].slug").value("it-p-a"))
        .andExpect(jsonPath("$[2].slug").value("it-p-c"));
  }

  // -----------------------------------------------------------------
  // Concurrency — slug race
  // -----------------------------------------------------------------

  @Test
  @DisplayName("Concurrent POST same slug → 1 created, còn lại 409 (DB UNIQUE là chốt chặn)")
  void concurrentSameSlugRace() throws Exception {
    String body =
        """
        { "name": "Race", "slug": "it-race" }
        """;

    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return mockMvc
                    .perform(
                        post("/api/v1/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
    }

    ready.await();
    start.countDown();

    int created = 0;
    int conflict = 0;
    for (Future<Integer> f : futures) {
      int status = f.get();
      if (status == 201) {
        created++;
      } else if (status == 409) {
        conflict++;
      }
    }
    pool.shutdown();

    Assertions.assertThat(created).isEqualTo(1);
    Assertions.assertThat(conflict).isEqualTo(threads - 1);
  }

  // -----------------------------------------------------------------
  // Helper
  // -----------------------------------------------------------------

  private String createCategory(String parentId, String name, String slug) throws Exception {
    Map<String, Object> payload = new HashMap<>();
    payload.put("parentId", parentId);
    payload.put("name", name);
    payload.put("slug", slug);

    String response =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode node = objectMapper.readTree(response);
    return node.get("id").asString();
  }
}
