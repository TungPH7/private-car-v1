package com.tungph7.learnpostgres.feature.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.admin.service.AdminService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract OpenAPI của 8 endpoint admin, đọc từ {@code /v3/api-docs} do springdoc sinh trên context
 * thật.
 *
 * <p>Ba lỗi của OpenAPI không làm test nào khác đỏ — app chạy đúng, chỉ tài liệu sai:
 *
 * <ul>
 *   <li>{@code ExplainResponse.plan} kiểu {@code JsonNode} của Jackson 3: swagger-core soi lớp đó
 *       như một bean — {@code plan} thành object có field boolean {@code array}, {@code empty},
 *       {@code nodeType}... thay vì một mảng JSON.
 *   <li>{@code SlowQueryResponse.queryId} ra JSON là chuỗi ({@code @JsonFormat}) nhưng OpenAPI vẫn
 *       ghi {@code integer} nếu thiếu {@code @Schema} — client sinh từ OpenAPI đọc sai kiểu.
 *   <li>Giới hạn, mặc định khai bằng hằng số của {@link AdminService} — lệch là client chặn sai.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOpenApiIntegrationTest extends AbstractContainerBaseTest {

  private static final String ADMIN = "/api/v1/admin";
  private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private JsonNode apiDocs;

  @BeforeEach
  void readApiDocs() throws Exception {
    String json =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    apiDocs = objectMapper.readTree(json);
  }

  private JsonNode operation(String method, String path) {
    JsonNode operation = apiDocs.path("paths").path(ADMIN + path).path(method);
    assertThat(operation.isMissingNode()).as("%s %s", method, path).isFalse();
    return operation;
  }

  private static JsonNode parameter(JsonNode operation, String name) {
    for (JsonNode parameter : operation.path("parameters")) {
      if (name.equals(parameter.path("name").asString())) {
        return parameter;
      }
    }
    throw new AssertionError("Thao tác không có tham số '" + name + "'");
  }

  private JsonNode schema(String name) {
    return apiDocs.path("components").path("schemas").path(name);
  }

  @Test
  @DisplayName("8 thao tác dưới tag Admin: 3 GET đọc, 3 hành động trên bảng, EXPLAIN, benchmark")
  void eightOperations() {
    List<String> operations = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : apiDocs.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        for (JsonNode tag : operation.getValue().path("tags")) {
          if ("Admin".equals(tag.asString())) {
            operations.add(operation.getKey() + " " + path.getKey());
          }
        }
      }
    }

    assertThat(operations)
        .containsExactlyInAnyOrder(
            "get " + ADMIN + "/indexes",
            "get " + ADMIN + "/indexes/usage",
            "get " + ADMIN + "/slow-queries",
            "get " + ADMIN + "/tables/{name}/stats",
            "post " + ADMIN + "/tables/{name}/analyze",
            "post " + ADMIN + "/tables/{name}/vacuum",
            "post " + ADMIN + "/search/products/explain",
            "post " + ADMIN + "/search/products/benchmark");
  }

  @Test
  @DisplayName("limit, iterations: minimum / maximum / default = hằng số service kiểm")
  void limitsMatchTheService() {
    JsonNode limit = parameter(operation("get", "/slow-queries"), "limit").path("schema");
    assertThat(
            List.of(
                limit.path("minimum").asInt(),
                limit.path("maximum").asInt(),
                limit.path("default").asInt()))
        .containsExactly(1, AdminService.MAX_SLOW_QUERIES, 20);

    JsonNode iterations =
        parameter(operation("post", "/search/products/benchmark"), "iterations").path("schema");
    assertThat(
            List.of(
                iterations.path("minimum").asInt(),
                iterations.path("maximum").asInt(),
                iterations.path("default").asInt()))
        .containsExactly(1, AdminService.MAX_BENCHMARK_ITERATIONS, 100);

    JsonNode full = parameter(operation("post", "/tables/{name}/vacuum"), "full");
    assertThat(full.path("in").asString()).isEqualTo("query");
    assertThat(full.path("schema").path("default").asString()).isEqualTo("false");
  }

  @Test
  @DisplayName("EXPLAIN / benchmark: body ProductSearchRequest bắt buộc; matchMode bắt buộc")
  void searchBodyAndMatchMode() {
    for (String action : List.of("explain", "benchmark")) {
      JsonNode body = operation("post", "/search/products/" + action).path("requestBody");
      assertThat(body.path("required").asBoolean()).as(action).isTrue();
      assertThat(
              body.path("content").path("application/json").path("schema").path("$ref").asString())
          .as(action)
          .isEqualTo("#/components/schemas/ProductSearchRequest");
    }

    JsonNode matchMode = parameter(operation("post", "/search/products/explain"), "matchMode");
    assertThat(matchMode.path("required").asBoolean()).isTrue();
    assertThat(matchMode.path("schema").path("default").isMissingNode()).isTrue();
    List<String> modes = new ArrayList<>();
    matchMode.path("schema").path("enum").forEach(mode -> modes.add(mode.asString()));
    assertThat(modes).containsExactly("STRICT", "RELAXED");
  }

  @Test
  @DisplayName("ExplainResponse.plan là mảng JSON; không có schema 'JsonNode' soi như bean")
  void planIsAJsonArray() {
    JsonNode plan = schema("ExplainResponse").path("properties").path("plan");

    assertThat(plan.path("type").asString()).isEqualTo("array");
    assertThat(plan.has("$ref")).isFalse();
    assertThat(schema("JsonNode").isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("SlowQueryResponse.queryId là string (int64) — như JSON thật")
  void queryIdIsDocumentedAsAString() {
    JsonNode queryId = schema("SlowQueryResponse").path("properties").path("queryId");

    assertThat(queryId.path("type").asString()).isEqualTo("string");
    assertThat(queryId.path("format").asString()).isEqualTo("int64");
  }

  /** 404 / 409 / 503 chỉ khai ở đúng những thao tác ném chúng, body là ProblemDetail. */
  @Test
  @DisplayName("404 ở 3 thao tác trên bảng, 409 ở ANALYZE / VACUUM, 503 chỉ ở EXPLAIN")
  void errorStatusesWhereTheyHappen() {
    Map<String, List<String>> expected =
        Map.of(
            "404",
            List.of(
                "get /tables/{name}/stats",
                "post /tables/{name}/analyze",
                "post /tables/{name}/vacuum"),
            "409",
            List.of("post /tables/{name}/analyze", "post /tables/{name}/vacuum"),
            "503",
            List.of("post /search/products/explain"));

    expected.forEach(
        (code, operations) -> {
          List<String> declared = new ArrayList<>();
          for (Map.Entry<String, JsonNode> path : apiDocs.path("paths").properties()) {
            if (!path.getKey().startsWith(ADMIN)) {
              continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
              JsonNode response = operation.getValue().path("responses").path(code);
              if (!response.isMissingNode()) {
                declared.add(operation.getKey() + " " + path.getKey().substring(ADMIN.length()));
                assertThat(
                        response
                            .path("content")
                            .path("application/problem+json")
                            .path("schema")
                            .path("$ref")
                            .asString())
                    .isEqualTo(PROBLEM_REF);
              }
            }
          }
          assertThat(declared).as(code).containsExactlyInAnyOrderElementsOf(operations);
        });
  }
}
