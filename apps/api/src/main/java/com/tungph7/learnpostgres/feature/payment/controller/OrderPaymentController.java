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
import com.tungph7.learnpostgres.feature.payment.dto.CreatePaymentRequest;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentResponse;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;
import com.tungph7.learnpostgres.feature.payment.mapper.PaymentMapper;
import com.tungph7.learnpostgres.feature.payment.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Payment của một order — {@code /api/v1/orders/{orderId}/payments}: tạo và liệt kê.
 *
 * <p>Tách khỏi {@link PaymentController} vì class-level {@code @RequestMapping} là đường dẫn gốc
 * của tài nguyên, và Spring ghép path của method VÀO SAU nó (F05 bài 04 §3.4) — payment được tạo
 * dưới order nhưng địa chỉ riêng của nó là {@code /api/v1/payments/{id}}. Cả 3 controller của F06
 * chung tag {@code Payments} trong Swagger.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/payments")
@Tag(name = "Payments", description = "Payment & refund (F06)")
public class OrderPaymentController {

  private final PaymentService service;
  private final PaymentMapper mapper;

  public OrderPaymentController(PaymentService service, PaymentMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  /**
   * 201 + {@code Location} khi vừa tạo, 200 khi gửi lặp đúng key — client phân biệt bằng status
   * hoặc {@code kind}. Không có {@code @ResponseStatus}: status quyết định lúc chạy, theo kết quả
   * của service.
   *
   * <p>Header {@code Idempotency-Key} bắt buộc: thiếu → {@code MissingRequestHeaderException} → 400
   * {@code INVALID_PARAMETER} (draft IETF §2.7). Rỗng / dài hơn cột {@code VARCHAR(80)} → 400
   * {@code VALIDATION_FAILED}, báo theo tên header.
   */
  @PostMapping
  @Operation(
      summary = "Tạo payment cho order (idempotent theo Idempotency-Key)",
      description =
          "Payment PENDING, số tiền lấy từ orders.total. Gửi lặp cùng key + cùng gateway → 200"
              + " với payment đã tạo (trạng thái hiện tại), không tạo thêm. Mỗi order tối đa"
              + " một payment chưa FAILED.")
  @ApiResponse(
      responseCode = "201",
      description = "Payment vừa tạo (kind = NEW); Location trỏ tới /api/v1/payments/{id}")
  @ApiResponse(
      responseCode = "200",
      description = "Gửi lặp đúng key (kind = EXISTING) — payment đã tạo trước đó")
  @ApiResponse(
      responseCode = "400",
      description =
          "INVALID_PARAMETER (thiếu header Idempotency-Key, orderId không phải UUID),"
              + " VALIDATION_FAILED (key rỗng / quá 80 ký tự, thiếu gateway),"
              + " MALFORMED_REQUEST (body hỏng, gateway lạ)")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "PAY_ORDER_NOT_PAYABLE (order không PENDING hoặc tổng 0),"
              + " PAY_ORDER_ALREADY_HAS_PAYMENT (order đã có payment chưa FAILED)")
  @ApiResponse(
      responseCode = "422",
      description = "PAY_IDEMPOTENCY_KEY_REUSED — key đã dùng cho request khác (order / gateway)")
  public ResponseEntity<PaymentIntentResult> createPaymentIntent(
      @PathVariable UUID orderId,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
      @Valid @RequestBody CreatePaymentRequest req,
      Principal principal,
      UriComponentsBuilder uriBuilder) {
    PaymentIntentResult result =
        service.createPaymentIntent(
            orderId, req.gateway(), idempotencyKey, RequestActor.of(principal));
    if (result.kind() == ResultKind.NEW) {
      URI location =
          uriBuilder.path("/api/v1/payments/{id}").buildAndExpand(result.paymentId()).toUri();
      return ResponseEntity.created(location).body(result);
    }
    return ResponseEntity.ok(result);
  }

  @GetMapping
  @Operation(summary = "Payment của order", description = "Mới nhất trước, kể cả FAILED.")
  @ApiResponse(responseCode = "200", description = "Payment của order")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND — không phải danh sách rỗng")
  public List<PaymentResponse> listPaymentsForOrder(@PathVariable UUID orderId) {
    return service.listPaymentsForOrder(orderId).stream().map(mapper::toResponse).toList();
  }
}
