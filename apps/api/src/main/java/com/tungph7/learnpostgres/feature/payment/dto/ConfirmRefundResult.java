package com.tungph7.learnpostgres.feature.payment.dto;

import java.util.UUID;

import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;

/**
 * Kết quả {@code confirmRefund} (webhook hoàn tiền).
 *
 * <p>{@code paymentStatus} là status của payment SAU khi tính lại theo tổng refund COMPLETED
 * (PARTIALLY_REFUNDED / REFUNDED) — thứ người gọi thường cần biết ngay. {@code alreadyCompleted =
 * true}: webhook gửi lặp với đúng mã refund, lần gọi này không đổi gì.
 */
public record ConfirmRefundResult(
    UUID refundId, String status, UUID paymentId, String paymentStatus, boolean alreadyCompleted) {

  /** Lần gọi này vừa chuyển refund sang COMPLETED. */
  public static ConfirmRefundResult completed(PaymentRefund refund, Payment payment) {
    return of(refund, payment, false);
  }

  /** Refund đã COMPLETED với đúng mã này từ trước — ack, không làm gì thêm. */
  public static ConfirmRefundResult alreadyCompleted(PaymentRefund refund, Payment payment) {
    return of(refund, payment, true);
  }

  private static ConfirmRefundResult of(
      PaymentRefund refund, Payment payment, boolean alreadyCompleted) {
    return new ConfirmRefundResult(
        refund.getId(),
        refund.getStatus().name(),
        payment.getId(),
        payment.getStatus().name(),
        alreadyCompleted);
  }
}
