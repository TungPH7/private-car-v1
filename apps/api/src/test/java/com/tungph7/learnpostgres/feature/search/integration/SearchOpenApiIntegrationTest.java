package com.tungph7.learnpostgres.feature.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.product.domain.ProductStatus;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchRequest;
import com.tungph7.learnpostgres.feature.search.service.SearchService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract OpenAPI của tìm sản phẩm, đọc từ {@code /v3/api-docs} do springdoc sinh trên context
 * thật.
 *
 * <p>Giới hạn và mặc định là phần client đọc được của contract — client sinh từ OpenAPI chặn theo
 * chúng. Test chốt chúng đúng bằng hằng số service kiểm, và chốt rằng body {@link
 * ProductSearchRequest} của EXPLAIN / benchmark (admin) mô tả đúng những tham số của {@code GET
 * /api/v1/search/products}: admin dán tham số của một lần tìm thật vào body là được.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchOpenApiIntegrationTest extends AbstractContainerBaseTest {

  private static final String SEARCH = "/api/v1/search/products";

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

  private JsonNode search() {
    return apiDocs.path("paths").path(SEARCH).path("get");
  }

  private JsonNode parameter(String name) {
    for (JsonNode parameter : search().path("parameters")) {
      if (name.equals(parameter.path("name").asString())) {
        return parameter;
      }
    }
    throw new AssertionError("OpenAPI không có tham số '" + name + "' ở GET " + SEARCH);
  }

  private JsonNode bodyProperty(String name) {
    JsonNode property =
        apiDocs
            .path("components")
            .path("schemas")
            .path("ProductSearchRequest")
            .path("properties")
            .path(name);
    assertThat(property.isMissingNode()).as("ProductSearchRequest.%s", name).isFalse();
    return property;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asString()));
    return values;
  }

  @Test
  @DisplayName("GET /api/v1/search/products dưới tag Search; giới hạn = hằng số service kiểm")
  void limitsMatchTheService() {
    assertThat(strings(search().path("tags"))).containsExactly("Search");

    JsonNode q = parameter("q");
    assertThat(q.path("required").asBoolean()).isFalse();
    assertThat(q.path("schema").path("maxLength").asInt())
        .isEqualTo(SearchService.MAX_QUERY_LENGTH);
    assertThat(q.path("description").asString())
        .contains(
            SearchService.MIN_QUERY_LENGTH
                + " – "
                + SearchService.MAX_QUERY_LENGTH
                + " ký tự, không tính khoảng trắng hai đầu");

    JsonNode size = parameter("size").path("schema");
    assertThat(
            List.of(
                size.path("minimum").asInt(),
                size.path("maximum").asInt(),
                size.path("default").asInt()))
        .containsExactly(1, SearchService.MAX_PAGE_SIZE, ProductSearchRequest.DEFAULT_PAGE_SIZE);

    JsonNode page = parameter("page").path("schema");
    // asString chứ không asInt: thiếu "minimum" thì asInt() cũng ra 0
    assertThat(List.of(page.path("minimum").asString(), page.path("default").asString()))
        .containsExactly("0", "0");

    for (String price : List.of("priceMin", "priceMax")) {
      assertThat(parameter(price).path("schema").path("minimum").asString())
          .as(price)
          .isEqualTo("0");
      // @Digits không thành gì trong schema — giới hạn chữ số nằm trong mô tả
      assertThat(parameter(price).path("description").asString())
          .as(price)
          .contains(
              "tối đa "
                  + SearchService.MAX_PRICE_INTEGER_DIGITS
                  + " chữ số phần nguyên, "
                  + SearchService.MAX_PRICE_FRACTION_DIGITS
                  + " chữ số lẻ");
    }
  }

  @Test
  @DisplayName("status, sort là enum đủ giá trị; sort mặc định NEWEST; categoryId là uuid")
  void enumsAndFormats() {
    assertThat(strings(parameter("status").path("schema").path("enum")))
        .containsExactlyElementsOf(Arrays.stream(ProductStatus.values()).map(Enum::name).toList());

    JsonNode sort = parameter("sort").path("schema");
    assertThat(strings(sort.path("enum")))
        .containsExactlyElementsOf(Arrays.stream(SearchSort.values()).map(Enum::name).toList());
    assertThat(sort.path("default").asString()).isEqualTo(SearchSort.NEWEST.name());

    assertThat(parameter("categoryId").path("schema").path("format").asString()).isEqualTo("uuid");
  }

  @Test
  @DisplayName("200 là ProductSearchPageResponse: content, page, matchMode (STRICT / RELAXED)")
  void responseCarriesMatchMode() {
    assertThat(
            search()
                .path("responses")
                .path("200")
                .path("content")
                .path("*/*")
                .path("schema")
                .path("$ref")
                .asString())
        .isEqualTo("#/components/schemas/ProductSearchPageResponse");

    JsonNode page = apiDocs.path("components").path("schemas").path("ProductSearchPageResponse");
    assertThat(page.path("properties").propertyNames())
        .containsExactly("content", "page", "matchMode");
    assertThat(strings(page.path("properties").path("matchMode").path("enum")))
        .containsExactlyElementsOf(Arrays.stream(MatchMode.values()).map(Enum::name).toList());
  }

  /**
   * Body của admin và query param của GET là cùng một lần tìm: mọi giới hạn, mặc định, enum, mô tả
   * phải trùng — lệch là admin EXPLAIN / đo một lần tìm khác lần client gửi.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"q", "categoryId", "priceMin", "priceMax", "status", "sort", "page", "size"})
  @DisplayName("ProductSearchRequest (body EXPLAIN / benchmark) mô tả đúng tham số của GET")
  void bodyDescribesTheSameSearch(String name) {
    JsonNode query = parameter(name);
    JsonNode body = bodyProperty(name);

    for (String keyword : List.of("type", "format", "maxLength", "minimum", "maximum", "default")) {
      assertThat(body.path(keyword).asString())
          .as("%s.%s", name, keyword)
          .isEqualTo(query.path("schema").path(keyword).asString());
    }
    assertThat(strings(body.path("enum")))
        .as("%s.enum", name)
        .isEqualTo(strings(query.path("schema").path("enum")));
    assertThat(body.path("description").asString())
        .as("%s.description", name)
        .isEqualTo(query.path("description").asString());
  }
}
