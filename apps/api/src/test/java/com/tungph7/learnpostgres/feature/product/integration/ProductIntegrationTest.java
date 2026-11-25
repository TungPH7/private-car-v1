package com.tungph7.learnpostgres.feature.product.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.auth.AuthFixtures;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.repository.RefreshTokenRepository;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;
import com.tungph7.learnpostgres.feature.auth.service.TokenService;
import com.tungph7.learnpostgres.feature.product.ProductFixtures;
import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.repository.ProductRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ProductIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired ProductRepository repository;
  @Autowired UserAccountRepository users;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired TokenService tokenService;

  private UserAccount admin;

  /** Authorization của một quản trị (ADMIN có mọi quyền product:* của V20). */
  private String adminBearer;

  @BeforeEach
  void signIn() {
    admin =
        AuthFixtures.saveUser(
            users, passwordEncoder, "product.admin@example.com", "long password", "ADMIN");
    adminBearer = "Bearer " + tokenService.issue(admin).accessToken();
  }

  @AfterEach
  void cleanup() {
    repository
        .deleteAll(); // pg test, không transactional roll-back vì SpringBootTest gọi qua MockMvc
    refreshTokens.deleteAll();
    users.deleteAll();
  }

  // ============================================================
  // Happy path
  // ============================================================

  @Test
  void POST_create_persists_to_database() throws Exception {
    CreateProductRequest req = ProductFixtures.validRequest("Postgres", "pg-uar");

    MvcResult result =
        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(req)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("X-Correlation-Id"))
            .andExpect(jsonPath("$.id").exists())
            .andReturn();

    String location = result.getResponse().getHeader("Location");
    assertThat(location).contains("/api/products/");
    assertThat(repository.findAll()).hasSize(1);
    assertThat(repository.findBySlug("pg-uar")).isPresent();
    // JPA Auditing ghi id của tài khoản (sub của token), không còn là "system".
    assertThat(repository.findBySlug("pg-uar").orElseThrow().getCreatedBy())
        .isEqualTo(admin.getId().toString());
  }

  @Test
  void GET_list_returns_a_page_of_products() throws Exception {
    repository.save(ProductFixtures.persistedEntity("a"));
    repository.save(ProductFixtures.persistedEntity("b"));

    mockMvc
        .perform(get("/api/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.page.number").value(0))
        .andExpect(jsonPath("$.page.size").value(20))
        .andExpect(jsonPath("$.page.totalElements").value(2))
        .andExpect(jsonPath("$.page.totalPages").value(1));
  }

  @Test
  void GET_list_finds_by_part_of_the_name_and_sorts_by_price() throws Exception {
    repository.save(new Product("Postgres Guide", "pg-guide", new BigDecimal("30.00"), null));
    repository.save(new Product("postgres pocket", "pg-pocket", new BigDecimal("10.00"), null));
    repository.save(new Product("Java Book", "java", new BigDecimal("20.00"), null));

    mockMvc
        .perform(get("/api/products").param("name", "POSTGRES").param("sort", "price,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].slug").value("pg-pocket"))
        .andExpect(jsonPath("$.content[1].slug").value("pg-guide"));
  }

  @Test
  void GET_list_caps_the_page_size_at_100() throws Exception {
    mockMvc
        .perform(get("/api/products").param("size", "5000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(100));
  }

  @Test
  void GET_list_refuses_a_sort_on_another_property() throws Exception {
    mockMvc
        .perform(get("/api/products").param("sort", "version,desc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  void PUT_update_replaces_the_product() throws Exception {
    Product saved = repository.save(ProductFixtures.persistedEntity("old"));

    mockMvc
        .perform(
            put("/api/products/{id}", saved.getId())
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\",\"slug\":\"new\",\"price\":9.50}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New"))
        .andExpect(jsonPath("$.price").value(9.50));
    assertThat(repository.findBySlug("new")).isPresent();
  }

  @Test
  void DELETE_removes_the_product() throws Exception {
    Product saved = repository.save(ProductFixtures.persistedEntity("gone"));

    mockMvc
        .perform(delete("/api/products/{id}", saved.getId()).header("Authorization", adminBearer))
        .andExpect(status().isNoContent());
    assertThat(repository.findById(saved.getId())).isEmpty();
  }

  // ============================================================
  // Quyền
  // ============================================================

  @Test
  void writing_without_a_token_is_401() throws Exception {
    mockMvc
        .perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(ProductFixtures.validRequest())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    assertThat(repository.findAll()).isEmpty();
  }

  @Test
  void writing_without_the_permission_is_403() throws Exception {
    UserAccount customer =
        AuthFixtures.saveUser(
            users, passwordEncoder, "customer@example.com", "long password", "CUSTOMER");
    String customerBearer = "Bearer " + tokenService.issue(customer).accessToken();
    Product saved = repository.save(ProductFixtures.persistedEntity("kept"));

    mockMvc
        .perform(
            delete("/api/products/{id}", saved.getId()).header("Authorization", customerBearer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    assertThat(repository.findById(saved.getId())).isPresent();
  }

  @Test
  void the_access_token_header_writes_behind_basic_auth() throws Exception {
    mockMvc
        .perform(
            post("/api/products")
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .header("X-Access-Token", adminBearer.substring("Bearer ".length()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(ProductFixtures.validRequest("X", "via-header"))))
        .andExpect(status().isCreated());
  }

  // ============================================================
  // Error path — verify body schema
  // ============================================================

  @Test
  void POST_with_invalid_body_returns_400_problem_with_correlation_id() throws Exception {
    String invalid = "{\"name\":\"\",\"slug\":\"x\",\"price\":1.00}";

    mockMvc
        .perform(
            post("/api/products")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").exists())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.correlationId").exists())
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void GET_byId_unknown_returns_404_problem() throws Exception {
    UUID id = UUID.fromString("00000000-0000-7000-8000-000000000000");

    mockMvc
        .perform(get("/api/products/{id}", id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.instance").value("/api/products/" + id));
  }

  @Test
  void POST_duplicate_slug_returns_409_problem() throws Exception {
    repository.save(ProductFixtures.persistedEntity("dup"));

    CreateProductRequest req = ProductFixtures.validRequest("Other", "dup");

    mockMvc
        .perform(
            post("/api/products")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_SLUG_DUPLICATED"));
  }

  // ============================================================
  // CorrelationId propagation
  // ============================================================

  @Test
  void server_generates_correlation_id_when_client_omits_header() throws Exception {
    mockMvc
        .perform(get("/api/products"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Correlation-Id"))
        .andExpect(
            header()
                .string(
                    "X-Correlation-Id",
                    org.hamcrest.Matchers.matchesRegex(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")));
  }

  @Test
  void server_reuses_correlation_id_from_client_header() throws Exception {
    String upstream = "trace-from-upstream-001";

    mockMvc
        .perform(get("/api/products").header("X-Correlation-Id", upstream))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-Id", upstream));
  }

  @Test
  void error_response_correlation_id_matches_response_header() throws Exception {
    UUID unknown = UUID.fromString("00000000-0000-7000-8000-000000000000");

    MvcResult result =
        mockMvc
            .perform(
                get("/api/products/{id}", unknown).header("X-Correlation-Id", "trace-error-001"))
            .andExpect(status().isNotFound())
            .andReturn();

    String headerId = result.getResponse().getHeader("X-Correlation-Id");
    String bodyId =
        json.readTree(result.getResponse().getContentAsString()).get("correlationId").asText();
    assertThat(headerId).isEqualTo("trace-error-001");
    assertThat(bodyId).isEqualTo(headerId);
  }

  @Test
  void invalid_correlation_id_is_dropped_and_regenerated() throws Exception {
    mockMvc
        .perform(get("/api/products").header("X-Correlation-Id", "<script>"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.not("<script>")));
  }
}
