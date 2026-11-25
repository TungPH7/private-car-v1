package com.tungph7.learnpostgres.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

class ProblemDetailOpenApiCustomizerTest {

  private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
  private static final String ORDER_REF = "#/components/schemas/OrderResponse";

  private final ProblemDetailOpenApiCustomizer customizer = new ProblemDetailOpenApiCustomizer();

  private static Content returnType() {
    return new Content()
        .addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(ORDER_REF)));
  }

  private static String ref(ApiResponse response, String mediaType) {
    return response.getContent().get(mediaType).getSchema().get$ref();
  }

  private ApiResponses customise(ApiResponses responses) {
    OpenAPI api =
        new OpenAPI()
            .components(new Components())
            .paths(
                new Paths()
                    .addPathItem("/x", new PathItem().post(new Operation().responses(responses))));
    customizer.customise(api);
    return api.getPaths().get("/x").getPost().getResponses();
  }

  /**
   * Controller khai {@code @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")} — lỗi
   * phải hiện là ProblemDetail, kể cả khi model đang mang schema của kiểu trả về.
   */
  @Test
  void declared_error_responses_get_problem_detail_content() {
    ApiResponses out =
        customise(
            new ApiResponses()
                .addApiResponse(
                    "201", new ApiResponse().description("created").content(returnType()))
                .addApiResponse(
                    "404", new ApiResponse().description("ORD_NOT_FOUND").content(returnType()))
                .addApiResponse("409", new ApiResponse().description("ORD_RACE_CONDITION")));

    assertThat(out.get("404").getContent()).containsOnlyKeys("application/problem+json");
    assertThat(ref(out.get("404"), "application/problem+json")).isEqualTo(PROBLEM_REF);
    assertThat(out.get("404").getDescription()).isEqualTo("ORD_NOT_FOUND");
    assertThat(ref(out.get("409"), "application/problem+json")).isEqualTo(PROBLEM_REF);
    // Response thành công giữ nguyên schema của kiểu trả về
    assertThat(ref(out.get("201"), "*/*")).isEqualTo(ORDER_REF);
  }

  @Test
  void declared_400_keeps_its_description_and_500_is_added() {
    ApiResponses out =
        customise(
            new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok").content(returnType()))
                .addApiResponse("400", new ApiResponse().description("ORD_PRODUCT_INACTIVE")));

    assertThat(out.get("400").getDescription()).isEqualTo("ORD_PRODUCT_INACTIVE");
    assertThat(ref(out.get("400"), "application/problem+json")).isEqualTo(PROBLEM_REF);
    assertThat(ref(out.get("500"), "application/problem+json")).isEqualTo(PROBLEM_REF);
  }

  /** Hành vi cũ của mọi endpoint không khai {@code @ApiResponse} lỗi: 400 + 500 chung. */
  @Test
  void operation_without_declared_errors_gets_generic_400_and_500() {
    ApiResponses out =
        customise(
            new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok").content(returnType())));

    assertThat(out).containsOnlyKeys("200", "400", "500");
    assertThat(out.get("400").getDescription()).startsWith("Bad Request");
    assertThat(ref(out.get("200"), "*/*")).isEqualTo(ORDER_REF);
  }

  @Test
  void registers_problem_detail_schema() {
    OpenAPI api = new OpenAPI().components(new Components()).paths(new Paths());

    customizer.customise(api);

    assertThat(api.getComponents().getSchemas()).containsKey("ProblemDetail");
  }
}
