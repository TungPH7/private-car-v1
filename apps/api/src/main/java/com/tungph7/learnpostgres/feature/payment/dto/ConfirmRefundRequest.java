package com.tungph7.learnpostgres.feature.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.node.ObjectNode;

/**
 * Webhook hoàn tiền giả lập — body của {@code POST /api/v1/refunds/{id}/confirm}: gateway báo đã
 * trả tiền lại cho khách với mã {@code refundReference}.
 *
 * <p>{@code gatewayResponse} cùng quy ước với {@link ConfirmPaymentRequest}: một JSON object, số
 * thập phân giữ nguyên từng chữ số, và cùng khai báo {@link Schema} — lý do ở Javadoc của lớp đó.
 *
 * @param refundReference mã refund của gateway — cột {@code refund_reference VARCHAR(80)}, unique
 *     toàn bảng
 * @param gatewayResponse payload gốc của gateway; không gửi thì {@code null}
 */
public record ConfirmRefundRequest(
    @NotBlank @Size(max = 80) String refundReference,
    @Schema(
            types = "object",
            implementation = Object.class,
            description = "Payload gốc của gateway — một JSON object",
            example = "{\"refundId\":\"re_001\",\"status\":\"succeeded\"}")
        ObjectNode gatewayResponse) {

  /** Payload dạng text JSON để ghi vào cột {@code jsonb}; {@code null} nếu không gửi. */
  public String gatewayResponseJson() {
    return gatewayResponse == null ? null : gatewayResponse.toString();
  }
}
