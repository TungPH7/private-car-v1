package com.tungph7.learnpostgres.feature.payment.domain;

/**
 * Trạng thái refund — mapping cột {@code status VARCHAR(16)} của {@code app.payment_refunds}, miền
 * giá trị khớp CHECK {@code ck_refunds_status} (V15).
 *
 * <pre>
 *   PENDING → COMPLETED (gateway xác nhận) | FAILED (gateway từ chối)
 *   COMPLETED, FAILED → (terminal)
 * </pre>
 *
 * <p>PENDING và COMPLETED <b>giữ tiền</b>: trigger {@code trg_payment_refunds_check_payment} cộng
 * cả hai khi kiểm "tổng refund ≤ số tiền đã thu". FAILED nhả lại phần đó. Status của payment
 * (PARTIALLY_REFUNDED / REFUNDED) thì chỉ tính theo COMPLETED — tiền đã thật sự trả lại.
 */
public enum RefundStatus {
  PENDING,
  COMPLETED,
  FAILED
}
