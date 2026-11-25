package com.tungph7.learnpostgres.feature.dashboard.integration;

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
import com.tungph7.learnpostgres.feature.dashboard.service.DashboardService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract OpenAPI của 14 báo cáo dashboard, đọc từ {@code /v3/api-docs} do springdoc sinh trên
 * context thật.
 *
 * <p>Giới hạn khai bằng Bean Validation trên controller là phần client đọc được của contract
 * ({@code minimum} / {@code maximum} / {@code default}). Test chốt chúng đúng bằng hằng số mà
 * service kiểm, và hai chỗ springdoc không tự suy ra: {@code cohortMonth} ({@code YearMonth}) chỉ
 * hiện {@code string} trần — cần ví dụ {@code yyyy-MM}; {@code @Digits} của {@code minTotalSpent}
 * không thành schema — giới hạn nằm trong mô tả.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardOpenApiIntegrationTest extends AbstractContainerBaseTest {

  private static final String DASHBOARD = "/api/v1/dashboard";
  private static final List<String> TOP_N =
      List.of("/top/products", "/top/customers", "/top/categories", "/inventory/turnover");
  private static final List<String> BY_YEAR =
      List.of("/revenue/monthly", "/revenue/by-category", "/growth");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private JsonNode paths;

  @BeforeEach
  void readApiDocs() throws Exception {
    String json =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    paths = objectMapper.readTree(json).path("paths");
  }

  private JsonNode parameter(String path, String name) {
    for (JsonNode parameter : paths.path(path).path("get").path("parameters")) {
      if (name.equals(parameter.path("name").asString())) {
        return parameter;
      }
    }
    throw new AssertionError("OpenAPI không có tham số '" + name + "' ở GET " + path);
  }

  @Test
  @DisplayName("14 thao tác dưới tag Dashboard, đều là GET")
  void fourteenReadOnlyOperations() {
    List<String> operations = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : paths.properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        for (JsonNode tag : operation.getValue().path("tags")) {
          if ("Dashboard".equals(tag.asString())) {
            operations.add(operation.getKey() + " " + path.getKey());
          }
        }
      }
    }

    assertThat(operations)
        .hasSize(14)
        .allMatch(operation -> operation.startsWith("get "))
        .contains(
            "get " + DASHBOARD + "/revenue/grouping-sets",
            "get " + DASHBOARD + "/top/categories",
            "get /api/v1/customers/{customerId}/ltv");
  }

  @Test
  @DisplayName(
      "minimum / maximum / default của n, year, months, minTotalSpent = hằng số service kiểm")
  void limitsMatchTheService() {
    for (String path : TOP_N) {
      JsonNode n = parameter(DASHBOARD + path, "n").path("schema");
      assertThat(List.of(n.path("minimum").asInt(), n.path("maximum").asInt()))
          .as("n của %s", path)
          .containsExactly(1, DashboardService.MAX_TOP_N);
      assertThat(n.path("default").asInt()).as("n mặc định của %s", path).isEqualTo(10);
    }
    for (String path : BY_YEAR) {
      JsonNode year = parameter(DASHBOARD + path, "year");
      assertThat(year.path("required").asBoolean()).isTrue();
      assertThat(
              List.of(
                  year.path("schema").path("minimum").asInt(),
                  year.path("schema").path("maximum").asInt()))
          .as("year của %s", path)
          .containsExactly(DashboardService.MIN_YEAR, DashboardService.MAX_YEAR);
    }
    JsonNode months = parameter(DASHBOARD + "/cohort", "months").path("schema");
    assertThat(
            List.of(
                months.path("minimum").asInt(),
                months.path("maximum").asInt(),
                months.path("default").asInt()))
        .containsExactly(1, DashboardService.MAX_COHORT_MONTHS, 12);

    JsonNode minTotalSpent = parameter(DASHBOARD + "/top/customers", "minTotalSpent");
    // asString chứ không asInt: thiếu "minimum" thì asInt() cũng ra 0
    assertThat(minTotalSpent.path("schema").path("minimum").asString()).isEqualTo("0");
    assertThat(minTotalSpent.path("description").asString())
        .contains(
            "Tối đa "
                + DashboardService.MAX_SPEND_INTEGER_DIGITS
                + " chữ số phần nguyên, "
                + DashboardService.MAX_SPEND_FRACTION_DIGITS
                + " chữ số lẻ");
  }

  @Test
  @DisplayName(
      "ngày là string format date; cohortMonth là string có ví dụ yyyy-MM, không phải object")
  void datesAndMonthAreStrings() {
    JsonNode from = parameter(DASHBOARD + "/revenue/daily", "from");
    assertThat(from.path("required").asBoolean()).isTrue();
    assertThat(from.path("schema").path("type").asString()).isEqualTo("string");
    assertThat(from.path("schema").path("format").asString()).isEqualTo("date");

    JsonNode cohortMonth = parameter(DASHBOARD + "/cohort", "cohortMonth");
    assertThat(cohortMonth.path("required").asBoolean()).isTrue();
    assertThat(cohortMonth.path("schema").path("type").asString()).isEqualTo("string");
    assertThat(cohortMonth.path("example").asString()).isEqualTo("2025-01");
  }

  @Test
  @DisplayName("503 khai ở đúng 4 báo cáo đọc materialized view, body là ProblemDetail")
  void serviceUnavailableOnlyWhereTheViewIsRead() {
    List<String> withServiceUnavailable = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : paths.properties()) {
      JsonNode unavailable = path.getValue().path("get").path("responses").path("503");
      if (!unavailable.isMissingNode()) {
        withServiceUnavailable.add(path.getKey());
        assertThat(
                unavailable
                    .path("content")
                    .path("application/problem+json")
                    .path("schema")
                    .path("$ref")
                    .asString())
            .isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(unavailable.path("description").asString()).startsWith("DASH_REVENUE_NOT_READY");
      }
    }

    assertThat(withServiceUnavailable)
        .containsExactlyInAnyOrder(
            DASHBOARD + "/revenue/daily",
            DASHBOARD + "/revenue/weekly",
            DASHBOARD + "/revenue/monthly",
            DASHBOARD + "/growth");
  }
}
