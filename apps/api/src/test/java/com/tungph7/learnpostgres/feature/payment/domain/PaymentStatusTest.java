package com.tungph7.learnpostgres.feature.payment.domain;

import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.AUTHORIZED;
import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.CAPTURED;
import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.FAILED;
import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.PARTIALLY_REFUNDED;
import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.PENDING;
import static com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** State machine của {@link PaymentStatus} — khớp bảng transition ở {@code 00-README §4.1}. */
class PaymentStatusTest {

  @Test
  @DisplayName("bảng transition đúng từng dòng")
  void allowedNextMatchesStateMachine() {
    assertThat(PENDING.allowedNext()).containsExactlyInAnyOrder(AUTHORIZED, CAPTURED, FAILED);
    assertThat(AUTHORIZED.allowedNext()).containsExactlyInAnyOrder(CAPTURED, FAILED);
    assertThat(CAPTURED.allowedNext()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
    assertThat(PARTIALLY_REFUNDED.allowedNext()).containsExactly(REFUNDED);
    assertThat(FAILED.allowedNext()).isEmpty();
    assertThat(REFUNDED.allowedNext()).isEmpty();
  }

  @Test
  @DisplayName("terminal: đúng FAILED và REFUNDED")
  void terminalStates() {
    assertThat(PaymentStatus.values())
        .filteredOn(PaymentStatus::isTerminal)
        .containsExactlyInAnyOrder(FAILED, REFUNDED);
  }

  /**
   * Cùng tập với {@code WHERE status IN ('PENDING', 'AUTHORIZED')} của captureAtomic / markFailed.
   */
  @Test
  @DisplayName("capture được: đúng PENDING và AUTHORIZED")
  void capturableStates() {
    assertThat(PaymentStatus.values())
        .filteredOn(PaymentStatus::isCapturable)
        .containsExactlyInAnyOrder(PENDING, AUTHORIZED);
  }

  /** Cùng tập trigger V15 chấp nhận khi tạo refund, và WHERE của recomputeRefundStatus. */
  @Test
  @DisplayName("nhận refund mới được: đúng CAPTURED và PARTIALLY_REFUNDED")
  void refundableStates() {
    assertThat(PaymentStatus.values())
        .filteredOn(PaymentStatus::isRefundable)
        .containsExactlyInAnyOrder(CAPTURED, PARTIALLY_REFUNDED);
  }

  @Test
  @DisplayName("không đi lùi: đã thu tiền thì không về PENDING / AUTHORIZED, không hồi sinh FAILED")
  void noBackwardTransition() {
    assertThat(CAPTURED.canTransitionTo(PENDING)).isFalse();
    assertThat(CAPTURED.canTransitionTo(AUTHORIZED)).isFalse();
    assertThat(PARTIALLY_REFUNDED.canTransitionTo(CAPTURED)).isFalse();
    assertThat(REFUNDED.canTransitionTo(PARTIALLY_REFUNDED)).isFalse();
    assertThat(FAILED.canTransitionTo(PENDING)).isFalse();
    assertThat(FAILED.canTransitionTo(CAPTURED)).isFalse();
  }

  @Test
  @DisplayName("không refund thẳng từ PENDING / AUTHORIZED — phải thu tiền trước")
  void refundRequiresCapture() {
    assertThat(PENDING.canTransitionTo(PARTIALLY_REFUNDED)).isFalse();
    assertThat(PENDING.canTransitionTo(REFUNDED)).isFalse();
    assertThat(AUTHORIZED.canTransitionTo(REFUNDED)).isFalse();
  }

  /**
   * Refund thêm mà vẫn chưa đủ thì payment ở lại PARTIALLY_REFUNDED — status tính lại ra cùng giá
   * trị, không phải một transition ({@code 00-README §4.1}).
   */
  @ParameterizedTest
  @EnumSource(PaymentStatus.class)
  @DisplayName("không trạng thái nào chuyển sang chính nó")
  void noSelfTransition(PaymentStatus s) {
    assertThat(s.canTransitionTo(s)).isFalse();
  }
}
