package com.tungph7.learnpostgres.feature.payment.dto;

import java.util.UUID;

import com.tungph7.learnpostgres.feature.payment.domain.Payment;

/**
 * Kết quả {@code confirmPayment} (webhook thanh toán).
 *
 * <p>{@code alreadyCaptured = true}: webhook gửi lặp — payment đã thu tiền với đúng mã giao dịch
 * này từ trước, lần gọi này không đổi gì. {@code status} là trạng thái hiện tại: webhook gửi lặp
 * sau khi đã hoàn tiền một phần vẫn được ack, và lúc đó status là PARTIALLY_REFUNDED.
 */
public record ConfirmPaymentResult(UUID paymentId, String status, boolean alreadyCaptured) {

  /** Lần gọi này vừa capture. */
  public static ConfirmPaymentResult captured(Payment payment) {
    return new ConfirmPaymentResult(payment.getId(), payment.getStatus().name(), false);
  }

  /** Đã thu tiền với đúng mã giao dịch từ trước — ack, không làm gì thêm. */
  public static ConfirmPaymentResult alreadyCaptured(Payment payment) {
    return new ConfirmPaymentResult(payment.getId(), payment.getStatus().name(), true);
  }
}
