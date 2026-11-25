package com.tungph7.learnpostgres.feature.payment.controller;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.common.security.RequestActor;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentRequest;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.FailPaymentRequest;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentResponse;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResponse;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.RequestRefundRequest;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.mapper.PaymentMapper;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API của payment — {@code /api/v1/payments}: đọc, webhook thanh toán giả lập, đánh fail, và
 * tạo / liệt kê refund của payment.
 *
 * <p>Controller mỏng: Bean Validation chặn hình dạng request, {@link PaymentService} lo nghiệp vụ
 * và idempotency, {@link PaymentMapper} đổi entity → DTO.
 *
 * <p><b>{@code /confirm} là webhook GIẢ LẬP</b> — F06 không gọi gateway thật. Production: mỗi
 * gateway một endpoint webhook riêng, kiểm chữ ký trên body gốc (Stripe {@code Stripe-Signature},
 * VNPAY {@code vnp_SecureHash}) rồi mới gọi service (QnA Q16).
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment & refund (F06)")
public class PaymentController {

  private final PaymentService service;
  private final PaymentMapper mapper;

  public PaymentController(PaymentService service, PaymentMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  // -----------------------------------------------------------------
  // Payment
  // -----------------------------------------------------------------

  @GetMapping("/{id}")
  @Operation(summary = "Một payment")
  @ApiResponse(responseCode = "200", description = "Payment")
  @ApiResponse(responseCode = "404", description = "PAY_NOT_FOUND")
  public PaymentResponse getPayment(@PathVariable UUID id) {
    return mapper.toResponse(service.getById(id));
  }

  /**
   * 200 cả lần đầu lẫn khi gửi lặp: webhook gửi lặp là chuyện bình thường của gateway, và nhận lỗi
   * thì gateway sẽ gửi lại mãi. Phân biệt bằng {@code alreadyCaptured}.
   */
  @PostMapping("/{id}/confirm")
  @Operation(
      summary = "Xác nhận thanh toán (webhook giả lập)",
      description =
          "PENDING / AUTHORIZED → CAPTURED, rồi order PENDING → PAID trong cùng transaction."
              + " Gửi lặp với đúng paymentReference → 200, alreadyCaptured = true, không đổi gì."
              + " Order đã hết hạn / bị hủy thì vẫn capture (gateway đã trừ tiền), order giữ"
              + " nguyên — việc của đối soát.")
  @ApiResponse(responseCode = "200", description = "Đã capture, hoặc gửi lặp (alreadyCaptured)")
  @ApiResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED (paymentReference rỗng / quá 80 ký tự),"
              + " MALFORMED_REQUEST (gatewayResponse không phải JSON object)")
  @ApiResponse(responseCode = "404", description = "PAY_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "PAY_INVALID_STATE (payment FAILED, hoặc đã thu tiền với mã giao dịch khác),"
              + " PAY_REFERENCE_TAKEN (mã đã thuộc payment khác cùng gateway),"
              + " ORD_RACE_CONDITION (order vừa đổi trạng thái — gửi lại)")
  public ConfirmPaymentResult confirmPayment(
      @PathVariable UUID id, @Valid @RequestBody ConfirmPaymentRequest req) {
    return service.confirmPayment(id, req.paymentReference(), req.gatewayResponseJson());
  }

  /**
   * 200 + payment sau khi chuyển, như {@code POST /orders/{id}/transitions} (F05): service đọc lại
   * payment nên body có ngay {@code failedAt}, {@code failureReason}.
   */
  @PostMapping("/{id}/fail")
  @Operation(
      summary = "Đánh fail payment",
      description =
          "PENDING / AUTHORIZED → FAILED. Đã FAILED thì gọi lại là no-op (giữ lý do lần đầu)."
              + " Sau đó order tạo được payment mới với key mới.")
  @ApiResponse(responseCode = "200", description = "Payment sau khi chuyển")
  @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (reason rỗng / quá dài)")
  @ApiResponse(responseCode = "404", description = "PAY_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "PAY_INVALID_STATE — payment đã thu tiền: hoàn bằng refund, không fail được")
  public PaymentResponse failPayment(
      @PathVariable UUID id, @Valid @RequestBody FailPaymentRequest req) {
    return mapper.toResponse(service.markPaymentFailed(id, req.reason()));
  }

  // -----------------------------------------------------------------
  // Refund của payment
  // -----------------------------------------------------------------

  /**
   * Cùng khuôn {@code OrderPaymentController.createPaymentIntent}: 201 + {@code Location} khi vừa
   * tạo, 200 khi gửi lặp đúng key. {@code Location} trỏ tới {@code /api/v1/refunds/{id}} — địa chỉ
   * riêng của refund ({@link RefundController}).
   */
  @PostMapping("/{id}/refunds")
  @Operation(
      summary = "Yêu cầu hoàn tiền (idempotent theo Idempotency-Key)",
      description =
          "Refund PENDING, giữ tiền tới khi gateway xác nhận. Tổng refund PENDING + COMPLETED"
              + " không được vượt số tiền đã thu — trigger của V15 chặn ở DB. Gửi lặp cùng key +"
              + " cùng payment / số tiền / lý do → 200 với refund đã tạo.")
  @ApiResponse(
      responseCode = "201",
      description = "Refund vừa tạo (kind = NEW); Location trỏ tới /api/v1/refunds/{id}")
  @ApiResponse(
      responseCode = "200",
      description = "Gửi lặp đúng key (kind = EXISTING) — refund đã tạo trước đó")
  @ApiResponse(
      responseCode = "400",
      description =
          "INVALID_PARAMETER (thiếu header Idempotency-Key), VALIDATION_FAILED (key rỗng / quá 80"
              + " ký tự, amount ≤ 0 hoặc quá 2 chữ số lẻ, reason rỗng / quá 255 ký tự),"
              + " MALFORMED_REQUEST")
  @ApiResponse(responseCode = "404", description = "PAY_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "PAY_INVALID_STATE (payment chưa thu tiền / đã hoàn hết),"
              + " PAY_REFUND_EXCEEDS_PAYMENT (refund này, hoặc tổng refund đang giữ tiền, vượt"
              + " số đã thu)")
  @ApiResponse(
      responseCode = "422",
      description =
          "PAY_IDEMPOTENCY_KEY_REUSED — key đã dùng cho request khác (payment / số tiền / lý do)")
  public ResponseEntity<RefundResult> requestRefund(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
      @Valid @RequestBody RequestRefundRequest req,
      Principal principal,
      UriComponentsBuilder uriBuilder) {
    RefundResult result =
        service.requestRefund(
            id, req.amount(), req.reason(), idempotencyKey, RequestActor.of(principal));
    if (result.kind() == ResultKind.NEW) {
      URI location =
          uriBuilder.path("/api/v1/refunds/{id}").buildAndExpand(result.refundId()).toUri();
      return ResponseEntity.created(location).body(result);
    }
    return ResponseEntity.ok(result);
  }

  @GetMapping("/{id}/refunds")
  @Operation(summary = "Refund của payment", description = "Mới nhất trước, mọi trạng thái.")
  @ApiResponse(responseCode = "200", description = "Refund của payment")
  @ApiResponse(responseCode = "404", description = "PAY_NOT_FOUND — không phải danh sách rỗng")
  public List<RefundResponse> listRefunds(@PathVariable UUID id) {
    return service.getRefundsForPayment(id).stream().map(mapper::toRefundResponse).toList();
  }
}
