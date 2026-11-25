package com.tungph7.learnpostgres.feature.payment.integration;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

/**
 * Contract OpenAPI của các endpoint payment, đọc từ {@code /v3/api-docs} do springdoc sinh trên
 * context thật.
 *
 * <p>Lý do có test này: springdoc nuốt lỗi khi dựng schema ("Graceful exception occurred" — chỉ một
 * dòng WARN) rồi lặng lẽ bỏ phần hỏng. swagger-core introspect {@code ObjectNode} của Jackson 3 như
 * một bean và vấp {@code Conflicting setter definitions for property "all"}. Thiếu khai báo {@code
 * implementation = Object.class} trên field thì {@code gatewayResponse} biến mất khỏi schema, còn
 * body của {@code POST /refunds/{id}/confirm} mất cả schema — app vẫn chạy, mọi test khác vẫn xanh.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOpenApiIntegrationTest extends AbstractContainerBaseTest {

  private static final String SCHEMAS = "$.components.schemas.";

  @Autowired MockMvc mockMvc;

  @Test
  @DisplayName("gatewayResponse của 2 webhook giả lập hiện là JSON object")
  void gatewayResponseIsDocumentedAsAnObject() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(SCHEMAS + "ConfirmPaymentRequest.properties.gatewayResponse.type")
                .value("object"))
        .andExpect(
            jsonPath(SCHEMAS + "ConfirmRefundRequest.properties.gatewayResponse.type")
                .value("object"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/refunds/{id}/confirm'].post.requestBody"
                        + ".content['application/json'].schema['$ref']")
                .value("#/components/schemas/ConfirmRefundRequest"));
  }

  @Test
  @DisplayName("2 thao tác tạo idempotent: header Idempotency-Key bắt buộc, khai 201 + 200 + 422")
  void idempotentCreatesDocumentHeaderAndStatusCodes() throws Exception {
    for (String path :
        new String[] {"/api/v1/orders/{orderId}/payments", "/api/v1/payments/{id}/refunds"}) {
      String post = "$.paths['" + path + "'].post";
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(
              jsonPath(post + ".parameters[?(@.name == 'Idempotency-Key')].in")
                  .value(hasItems("header")))
          .andExpect(
              jsonPath(post + ".parameters[?(@.name == 'Idempotency-Key')].required")
                  .value(hasItems(true)))
          .andExpect(
              jsonPath(post + ".parameters[?(@.name == 'Idempotency-Key')].schema.maxLength")
                  .value(hasItems(80)))
          .andExpect(jsonPath(post + ".responses['201']").exists())
          .andExpect(jsonPath(post + ".responses['200']").exists())
          .andExpect(jsonPath(post + ".responses['422']").exists());
    }
  }
}
