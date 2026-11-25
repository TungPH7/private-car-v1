package com.tungph7.learnpostgres.feature.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;

/**
 * Kết quả {@code requestRefund}. Cùng quy ước với {@link PaymentIntentResult}: đọc từ row trong DB,
 * {@link ResultKind#EXISTING} mang trạng thái hiện tại của refund.
 */
public record RefundResult(UUID refundId, String status, BigDecimal amount, ResultKind kind) {

  /** Lần gọi này vừa tạo refund. */
  public static RefundResult created(PaymentRefund refund) {
    return of(refund, ResultKind.NEW);
  }

  /** Idempotency key đã có refund cho đúng request này — trả lại refund đó, không tạo gì. */
  public static RefundResult existing(PaymentRefund refund) {
    return of(refund, ResultKind.EXISTING);
  }

  private static RefundResult of(PaymentRefund refund, ResultKind kind) {
    return new RefundResult(refund.getId(), refund.getStatus().name(), refund.getAmount(), kind);
  }
}
