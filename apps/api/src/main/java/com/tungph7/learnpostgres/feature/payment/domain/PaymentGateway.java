package com.tungph7.learnpostgres.feature.payment.domain;

/**
 * Cổng thanh toán — mapping cột {@code gateway VARCHAR(32)}, miền giá trị khớp CHECK {@code
 * ck_payments_gateway} (V15).
 *
 * <p>Mã giao dịch ({@code payment_reference}) chỉ unique TRONG một gateway — UNIQUE {@code
 * (payment_reference, gateway)}: VNPAY và MOMO đều cấp mã dạng số, có thể trùng nhau.
 */
public enum PaymentGateway {
  STRIPE,
  VNPAY,
  MOMO,
  COD,
  BANK_TRANSFER
}
