package com.tungph7.learnpostgres.feature.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.node.ObjectNode;

/**
 * Webhook thanh toán giả lập — body của {@code POST /api/v1/payments/{id}/confirm}: gateway báo đã
 * thu tiền với mã giao dịch {@code paymentReference}.
 *
 * <p>{@code gatewayResponse} là một JSON object, không phải chuỗi chứa JSON đã escape. Kiểu {@link
 * ObjectNode} chặn ngay lúc đọc body: mảng / chuỗi / số → 400 {@code MALFORMED_REQUEST}, không đi
 * tới DB. Số thập phân trong payload giữ nguyên từng chữ số nhờ {@code
 * spring.jackson.deserialization.use-big-decimal-for-floats} (application.yml) — mặc định Jackson
 * đọc chúng thành {@code double}: {@code 405000.00} thành {@code 405000.0}, {@code
 * 1234567.891234567891} thành {@code 1234567.8912345679}.
 *
 * <p>{@link Schema} ở field là bắt buộc, không chỉ để trang trí. swagger-core (introspect bằng
 * Jackson 2) coi {@code ObjectNode} của Jackson 3 là một bean, vấp {@code Conflicting setter
 * definitions for property "all"}, và springdoc lặng lẽ bỏ field — có khi cả schema của body — khỏi
 * OpenAPI. {@code implementation = Object.class} để swagger-core không đụng tới {@code ObjectNode};
 * {@code types = "object"} để OpenAPI 3.1 ghi kiểu ({@code Object.class} không có kiểu). Bỏ một
 * trong hai thì {@code PaymentOpenApiIntegrationTest} đỏ.
 *
 * @param paymentReference mã giao dịch của gateway — cột {@code payment_reference VARCHAR(80)}
 * @param gatewayResponse payload gốc của gateway; không gửi thì {@code null}
 */
public record ConfirmPaymentRequest(
    @NotBlank @Size(max = 80) String paymentReference,
    @Schema(
            types = "object",
            implementation = Object.class,
            description = "Payload gốc của gateway — một JSON object",
            example = "{\"transactionId\":\"txn_001\",\"status\":\"succeeded\"}")
        ObjectNode gatewayResponse) {

  /** Payload dạng text JSON để ghi vào cột {@code jsonb}; {@code null} nếu không gửi. */
  public String gatewayResponseJson() {
    return gatewayResponse == null ? null : gatewayResponse.toString();
  }
}
