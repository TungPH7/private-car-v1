package com.tungph7.learnpostgres.feature.order.domain;

import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.CANCELLED;
import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.DELIVERED;
import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.EXPIRED;
import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.PAID;
import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.PENDING;
import static com.tungph7.learnpostgres.feature.order.domain.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** State machine của {@link OrderStatus} — khớp bảng transition ở {@code 00-README §4}. */
class OrderStatusTest {

  @Test
  @DisplayName("bảng transition đúng từng dòng")
  void allowedNextMatchesStateMachine() {
    assertThat(PENDING.allowedNext()).containsExactlyInAnyOrder(PAID, CANCELLED, EXPIRED);
    assertThat(PAID.allowedNext()).containsExactlyInAnyOrder(SHIPPED, CANCELLED);
    assertThat(SHIPPED.allowedNext()).containsExactlyInAnyOrder(DELIVERED, CANCELLED);
    assertThat(DELIVERED.allowedNext()).isEmpty();
    assertThat(CANCELLED.allowedNext()).isEmpty();
    assertThat(EXPIRED.allowedNext()).isEmpty();
  }

  @Test
  @DisplayName("3 trạng thái terminal, 3 trạng thái còn đi tiếp được")
  void terminalStates() {
    assertThat(DELIVERED.isTerminal()).isTrue();
    assertThat(CANCELLED.isTerminal()).isTrue();
    assertThat(EXPIRED.isTerminal()).isTrue();
    assertThat(PENDING.isTerminal()).isFalse();
    assertThat(PAID.isTerminal()).isFalse();
    assertThat(SHIPPED.isTerminal()).isFalse();
  }

  @Test
  @DisplayName("AC-18 — nhảy bước PENDING → DELIVERED bị từ chối")
  void skippingStepIsRejected() {
    assertThat(PENDING.canTransitionTo(DELIVERED)).isFalse();
    assertThat(PENDING.canTransitionTo(SHIPPED)).isFalse();
    assertThat(PAID.canTransitionTo(DELIVERED)).isFalse();
  }

  @Test
  @DisplayName("không đi lùi, không hồi sinh đơn đã kết thúc")
  void noBackwardTransition() {
    assertThat(PAID.canTransitionTo(PENDING)).isFalse();
    assertThat(SHIPPED.canTransitionTo(PAID)).isFalse();
    assertThat(CANCELLED.canTransitionTo(PENDING)).isFalse();
    assertThat(EXPIRED.canTransitionTo(PAID)).isFalse();
  }

  @Test
  @DisplayName("EXPIRED chỉ đi ra từ PENDING — đơn đã thanh toán không bao giờ hết hạn")
  void onlyPendingCanExpire() {
    for (OrderStatus s : OrderStatus.values()) {
      assertThat(s.canTransitionTo(EXPIRED)).as("%s → EXPIRED", s).isEqualTo(s == PENDING);
    }
  }

  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  @DisplayName("không trạng thái nào chuyển sang chính nó (khớp ck_osh_status_diff)")
  void noSelfTransition(OrderStatus s) {
    assertThat(s.canTransitionTo(s)).isFalse();
  }
}
