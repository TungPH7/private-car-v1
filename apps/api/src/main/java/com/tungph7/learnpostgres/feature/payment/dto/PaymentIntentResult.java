package com.tungph7.learnpostgres.feature.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.payment.domain.Payment;

/**
 * Kết quả {@code createPaymentIntent}.
 *
 * <p>Mọi field đọc từ payment trong DB, kể cả lần đầu ({@code RETURNING *}): {@code status} và
 * {@code currency} do DEFAULT của V15 điền, Java không tự đoán. Với {@link ResultKind#EXISTING},
 * {@code status} là trạng thái HIỆN TẠI của payment (có thể đã CAPTURED / FAILED) — không phải bản
 * chụp response lần đầu như Stripe lưu.
 *
 * <p>{@code status}, {@code gateway} là {@code String} như {@code OrderResponse.status} của F05.
 */
public record PaymentIntentResult(
    UUID paymentId,
    String status,
    BigDecimal amount,
    String currency,
    String gateway,
    ResultKind kind) {

  /** Lần gọi này vừa tạo payment. */
  public static PaymentIntentResult created(Payment payment) {
    return of(payment, ResultKind.NEW);
  }

  /** Idempotency key đã có payment cho đúng request này — trả lại payment đó, không tạo gì. */
  public static PaymentIntentResult existing(Payment payment) {
    return of(payment, ResultKind.EXISTING);
  }

  private static PaymentIntentResult of(Payment payment, ResultKind kind) {
    return new PaymentIntentResult(
        payment.getId(),
        payment.getStatus().name(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getGateway().name(),
        kind);
  }
}
