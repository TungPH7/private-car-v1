package com.tungph7.learnpostgres.feature.payment.domain;

import java.util.Set;

/**
 * Trạng thái payment — mapping cột {@code status VARCHAR(24)}, miền giá trị khớp CHECK {@code
 * ck_payments_status} (V15).
 *
 * <p>Bảng transition hợp lệ (khớp state machine ở {@code 00-README §4.1}):
 *
 * <pre>
 *   PENDING            → AUTHORIZED, CAPTURED, FAILED
 *   AUTHORIZED         → CAPTURED, FAILED
 *   CAPTURED           → PARTIALLY_REFUNDED, REFUNDED
 *   PARTIALLY_REFUNDED → REFUNDED
 *   FAILED, REFUNDED   → (terminal)
 * </pre>
 *
 * <p>Đây là lớp guard ở app. DB KHÔNG kiểm transition — V15 chỉ kiểm miền giá trị và dữ liệu đi kèm
 * từng status; lớp chặn race là {@code UPDATE ... WHERE status IN (...)} ở {@code
 * PaymentRepository}.
 */
public enum PaymentStatus {
  PENDING,
  AUTHORIZED,
  CAPTURED,
  FAILED,
  REFUNDED,
  PARTIALLY_REFUNDED;

  /** Các trạng thái được phép chuyển tới từ trạng thái này. Rỗng = terminal. */
  public Set<PaymentStatus> allowedNext() {
    return switch (this) {
      case PENDING -> Set.of(AUTHORIZED, CAPTURED, FAILED);
      case AUTHORIZED -> Set.of(CAPTURED, FAILED);
      case CAPTURED -> Set.of(PARTIALLY_REFUNDED, REFUNDED);
      case PARTIALLY_REFUNDED -> Set.of(REFUNDED);
      case FAILED, REFUNDED -> Set.of();
    };
  }

  public boolean canTransitionTo(PaymentStatus next) {
    return allowedNext().contains(next);
  }

  public boolean isTerminal() {
    return allowedNext().isEmpty();
  }

  /**
   * Gateway còn capture được (PENDING, AUTHORIZED) — cùng tập với {@code WHERE status IN
   * ('PENDING', 'AUTHORIZED')} của {@code captureAtomic} / {@code markFailed}.
   */
  public boolean isCapturable() {
    return canTransitionTo(CAPTURED);
  }

  /**
   * Còn nhận refund mới được (CAPTURED, PARTIALLY_REFUNDED) — cùng tập trigger V15 chấp nhận khi
   * tạo refund. Suy từ bảng transition: chỉ 2 trạng thái này đi tới REFUNDED được.
   */
  public boolean isRefundable() {
    return canTransitionTo(REFUNDED);
  }
}
