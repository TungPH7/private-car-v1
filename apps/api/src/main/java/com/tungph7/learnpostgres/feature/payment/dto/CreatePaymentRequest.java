package com.tungph7.learnpostgres.feature.payment.dto;

import jakarta.validation.constraints.NotNull;

import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;

/**
 * Tạo payment cho order — body của {@code POST /api/v1/orders/{orderId}/payments}.
 *
 * <p>Không có số tiền: {@code amount} copy từ {@code orders.total} lúc tạo — nhận tiền từ client là
 * để client tự định giá. Idempotency key đi qua header {@code Idempotency-Key}, không qua body.
 *
 * <p>{@code gateway} là enum: giá trị lạ (vd {@code "PAYPAL"}) thì Jackson không đọc được body →
 * 400 {@code MALFORMED_REQUEST}; thiếu → 400 {@code VALIDATION_FAILED} ở field {@code gateway}.
 */
public record CreatePaymentRequest(@NotNull PaymentGateway gateway) {}
