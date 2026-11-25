package com.tungph7.learnpostgres.feature.payment.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundRequest;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResponse;
import com.tungph7.learnpostgres.feature.payment.mapper.PaymentMapper;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Một refund theo địa chỉ riêng của nó — {@code /api/v1/refunds/{id}}.
 *
 * <p>Refund được tạo / liệt kê dưới payment ({@code /payments/{id}/refunds}), nhưng id của refund
 * là UUID toàn cục nên địa chỉ riêng không cần payment id — như payment tạo dưới order mà đọc ở
 * {@code /payments/{id}}. Lồng {@code /payments/{paymentId}/refunds/{refundId}} thì mọi request
 * phải kiểm thêm refund có thuộc đúng payment trên URL không.
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Payments", description = "Payment & refund (F06)")
public class RefundController {

  private final PaymentService service;
  private final PaymentMapper mapper;

  public RefundController(PaymentService service, PaymentMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Một refund",
      description = "Theo dõi refund PENDING tới khi gateway xác nhận (COMPLETED).")
  @ApiResponse(responseCode = "200", description = "Refund")
  @ApiResponse(responseCode = "404", description = "PAY_REFUND_NOT_FOUND")
  public RefundResponse getRefund(@PathVariable UUID id) {
    return mapper.toRefundResponse(service.getRefund(id));
  }

  /** Cùng quy ước {@code PaymentController.confirmPayment}: 200 cả lần đầu lẫn khi gửi lặp. */
  @PostMapping("/{id}/confirm")
  @Operation(
      summary = "Xác nhận hoàn tiền (webhook giả lập)",
      description =
          "Refund PENDING → COMPLETED, rồi tính lại status payment theo tổng refund COMPLETED"
              + " (PARTIALLY_REFUNDED / REFUNDED) trong cùng transaction. Gửi lặp với đúng"
              + " refundReference → 200, alreadyCompleted = true.")
  @ApiResponse(
      responseCode = "200",
      description = "Refund COMPLETED kèm status mới của payment, hoặc gửi lặp (alreadyCompleted)")
  @ApiResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED (refundReference rỗng / quá 80 ký tự),"
              + " MALFORMED_REQUEST (gatewayResponse không phải JSON object)")
  @ApiResponse(responseCode = "404", description = "PAY_REFUND_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "PAY_INVALID_STATE (refund FAILED, hoặc đã COMPLETED với mã khác),"
              + " PAY_REFERENCE_TAKEN (mã refund đã thuộc refund khác)")
  public ConfirmRefundResult confirmRefund(
      @PathVariable UUID id, @Valid @RequestBody ConfirmRefundRequest req) {
    return service.confirmRefund(id, req.refundReference(), req.gatewayResponseJson());
  }
}
