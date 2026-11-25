package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.domain.ShippingSnapshot;
import com.tungph7.learnpostgres.feature.order.repository.OrderItemRepository;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;
import com.tungph7.learnpostgres.feature.order.repository.StatusHistoryRepository;

/**
 * Unit test cho {@link OrderLifecycle}: guard của state machine, câu UPDATE có điều kiện, và bù trừ
 * tồn kho / coupon đi kèm từng lần đổi trạng thái.
 *
 * <p>Order lấy trạng thái từ DB ({@code status} chỉ đọc, không setter) — ở đây gán bằng reflection
 * để dựng đơn đang ở PAID / SHIPPED / ...
 */
@ExtendWith(MockitoExtension.class)
class OrderLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
  private static final Duration PENDING_TTL = Duration.ofMinutes(30);
  private static final int BATCH_SIZE = 100;

  @Mock OrderRepository orderRepo;
  @Mock OrderItemRepository itemRepo;
  @Mock StatusHistoryRepository historyRepo;
  @Mock InventoryService inventoryService;
  @Mock CouponService couponService;

  private OrderLifecycle lifecycle;

  private final UUID orderId = UUID.randomUUID();
  private final UUID couponId = UUID.randomUUID();

  /** 2 product mà productId "lớn" đứng trước trong danh sách item — để kiểm việc sắp xếp. */
  private final UUID smallProductId = new UUID(0, 1);

  private final UUID bigProductId = new UUID(0, 2);

  @BeforeEach
  void setUp() {
    lifecycle =
        new OrderLifecycle(
            orderRepo,
            itemRepo,
            historyRepo,
            inventoryService,
            couponService,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new OrderExpirationProperties(PENDING_TTL, BATCH_SIZE));
  }

  private Order order(OrderStatus status, UUID coupon) {
    Order order =
        new Order(
            "ORD-202609-000007",
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ShippingSnapshot("John Doe", "+84901234567", "1 Le Loi", null, "HCM", "VN"),
            new BigDecimal("300.00"),
            coupon == null ? BigDecimal.ZERO : new BigDecimal("30.00"),
            BigDecimal.ZERO,
            coupon);
    ReflectionTestUtils.setField(order, "id", orderId);
    ReflectionTestUtils.setField(order, "status", status);
    return order;
  }

  /**
   * Lần đọc đầu trả {@code before}, lần đọc lại sau khi chuyển trả {@code after}.
   *
   * <p>{@code unchecked}: {@code thenReturn(T, T...)} với {@code T = Optional<Order>} là varargs
   * generic — javac cảnh báo tạo mảng generic, vô hại ở đây.
   */
  @SuppressWarnings("unchecked")
  private void givenStoredOrder(Order before, Order after) {
    when(orderRepo.findById(orderId)).thenReturn(Optional.of(before), Optional.of(after));
  }

  private void givenStoredOrder(Order order) {
    when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
  }

  private void givenTransitionSucceeds() {
    when(orderRepo.transitionStatus(any(), anyString(), anyString(), any())).thenReturn(1);
  }

  private void givenItemsInReverseProductOrder() {
    when(itemRepo.findByOrderIdOrderByCreatedAtAsc(orderId))
        .thenReturn(
            List.of(
                new OrderItem(orderId, bigProductId, "Big", null, 2, new BigDecimal("100.00")),
                new OrderItem(orderId, smallProductId, "Small", null, 1, new BigDecimal("50.00"))));
  }

  private OrderStatusHistory savedHistory() {
    ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
    verify(historyRepo).save(history.capture());
    return history.getValue();
  }

  private static void assertError(Throwable thrown, Class<?> type, ErrorCode code) {
    assertThat(thrown).isInstanceOf(type);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  // ================================================================
  // transition
  // ================================================================

  @Nested
  @DisplayName("transition")
  class Transition {

    @Test
    @DisplayName("PENDING → PAID: UPDATE có guard from, ghi history, trả đơn ĐỌC LẠI")
    void pendingToPaid() {
      Order reloaded = order(OrderStatus.PAID, null);
      givenStoredOrder(order(OrderStatus.PENDING, null), reloaded);
      givenTransitionSucceeds();

      Order result = lifecycle.transition(orderId, OrderStatus.PAID, "Payment confirmed", "system");

      assertThat(result).isSameAs(reloaded);
      verify(orderRepo).transitionStatus(orderId, "PENDING", "PAID", "Payment confirmed");
      OrderStatusHistory history = savedHistory();
      assertThat(history.getFromStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(history.getToStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(history.getReason()).isEqualTo("Payment confirmed");
      assertThat(history.getActor()).isEqualTo("system");
      verifyNoInteractions(inventoryService, couponService);
    }

    /** Thứ tự productId phải giống bulkReserve lúc đặt đơn — nếu không hai đơn khóa chéo nhau. */
    @Test
    @DisplayName("SHIPPED → DELIVERED: chốt tồn kho từng dòng, theo thứ tự productId")
    void deliveredCommitsStockInProductOrder() {
      givenStoredOrder(
          order(OrderStatus.SHIPPED, couponId), order(OrderStatus.DELIVERED, couponId));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();

      lifecycle.transition(orderId, OrderStatus.DELIVERED, "Delivered", "ops");

      InOrder commits = inOrder(inventoryService);
      commits.verify(inventoryService).commit(smallProductId, 1, orderId, "ops");
      commits.verify(inventoryService).commit(bigProductId, 2, orderId, "ops");
      verify(inventoryService, never()).release(any(), anyInt(), any(), any());
      verifyNoInteractions(couponService);
    }

    @Test
    @DisplayName("nhảy bước PENDING → DELIVERED → ORD_INVALID_STATUS_TRANSITION, không UPDATE")
    void skippingStepIsRejected() {
      givenStoredOrder(order(OrderStatus.PENDING, null));

      assertError(
          catchThrowable(() -> lifecycle.transition(orderId, OrderStatus.DELIVERED, null, "ops")),
          ConflictException.class,
          ErrorCode.ORD_INVALID_STATUS_TRANSITION);
      verify(orderRepo, never()).transitionStatus(any(), any(), any(), any());
      verifyNoInteractions(historyRepo, inventoryService);
    }

    /** UPDATE ... WHERE status = :from ra 0 row: giao dịch khác đổi trạng thái trước. */
    @Test
    @DisplayName("UPDATE ra 0 row → ORD_RACE_CONDITION, không ghi history, không đụng tồn kho")
    void lostRace() {
      givenStoredOrder(order(OrderStatus.SHIPPED, null));
      when(orderRepo.transitionStatus(orderId, "SHIPPED", "DELIVERED", null)).thenReturn(0);

      assertError(
          catchThrowable(() -> lifecycle.transition(orderId, OrderStatus.DELIVERED, null, "ops")),
          ConflictException.class,
          ErrorCode.ORD_RACE_CONDITION);
      verifyNoInteractions(historyRepo, inventoryService);
    }

    /**
     * Endpoint admin nhận {@code to} bất kỳ. Nếu CANCELLED chỉ đổi cột status thì hàng đã giữ và
     * lượt coupon không bao giờ được trả.
     */
    @Test
    @DisplayName("to = CANCELLED → đi luồng hủy: nhả hàng + hoàn coupon")
    void cancelledGoesThroughCancelFlow() {
      givenStoredOrder(order(OrderStatus.PAID, couponId), order(OrderStatus.CANCELLED, couponId));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();

      lifecycle.transition(orderId, OrderStatus.CANCELLED, "Khách đổi ý", "admin");

      verify(orderRepo).transitionStatus(orderId, "PAID", "CANCELLED", "Khách đổi ý");
      verify(inventoryService).release(smallProductId, 1, orderId, "admin");
      verify(inventoryService).release(bigProductId, 2, orderId, "admin");
      verify(couponService).revoke(couponId, orderId);
    }

    @Test
    @DisplayName("to = EXPIRED → từ chối: chỉ job hết hạn thanh toán được đặt")
    void expiredIsReservedForTheJob() {
      givenStoredOrder(order(OrderStatus.PENDING, null));

      assertError(
          catchThrowable(() -> lifecycle.transition(orderId, OrderStatus.EXPIRED, null, "admin")),
          ConflictException.class,
          ErrorCode.ORD_INVALID_STATUS_TRANSITION);
      verify(orderRepo, never()).transitionStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("đơn không tồn tại → ORD_NOT_FOUND")
    void unknownOrder() {
      when(orderRepo.findById(orderId)).thenReturn(Optional.empty());

      assertError(
          catchThrowable(() -> lifecycle.transition(orderId, OrderStatus.PAID, null, "system")),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
    }
  }

  // ================================================================
  // cancel
  // ================================================================

  @Nested
  @DisplayName("cancel")
  class Cancel {

    @Test
    @DisplayName("PENDING có coupon → CANCELLED, nhả hàng theo productId, hoàn lượt coupon")
    void pendingWithCoupon() {
      givenStoredOrder(order(OrderStatus.PENDING, couponId));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();

      lifecycle.cancel(orderId, "Khách đổi ý", "customer");

      verify(orderRepo).transitionStatus(orderId, "PENDING", "CANCELLED", "Khách đổi ý");
      OrderStatusHistory history = savedHistory();
      assertThat(history.getFromStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(history.getToStatus()).isEqualTo(OrderStatus.CANCELLED);
      assertThat(history.getActor()).isEqualTo("customer");
      InOrder releases = inOrder(inventoryService);
      releases.verify(inventoryService).release(smallProductId, 1, orderId, "customer");
      releases.verify(inventoryService).release(bigProductId, 2, orderId, "customer");
      verify(couponService).revoke(couponId, orderId);
    }

    @Test
    @DisplayName("PAID không coupon → nhả hàng, không gọi revoke")
    void paidWithoutCoupon() {
      givenStoredOrder(order(OrderStatus.PAID, null));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();

      lifecycle.cancel(orderId, "Hết hàng ở kho", "ops");

      verify(inventoryService).release(smallProductId, 1, orderId, "ops");
      verify(inventoryService).release(bigProductId, 2, orderId, "ops");
      verifyNoInteractions(couponService);
    }

    /**
     * Tồn kho chỉ chốt ở DELIVERED, nên lúc SHIPPED hàng vẫn đang được giữ dù đã rời kho. Nhả ra là
     * cho bán lại hàng không còn trên kệ.
     */
    @Test
    @DisplayName("SHIPPED → CANCELLED: KHÔNG nhả hàng (đang trên đường), vẫn hoàn coupon")
    void shippedKeepsReservation() {
      givenStoredOrder(order(OrderStatus.SHIPPED, couponId));
      givenTransitionSucceeds();

      lifecycle.cancel(orderId, "Giao thất bại", "ops");

      verify(orderRepo).transitionStatus(orderId, "SHIPPED", "CANCELLED", "Giao thất bại");
      verifyNoInteractions(inventoryService);
      verify(couponService).revoke(couponId, orderId);
    }

    @ParameterizedTest
    @EnumSource(
        value = OrderStatus.class,
        names = {"DELIVERED", "CANCELLED", "EXPIRED"})
    @DisplayName("đơn đã kết thúc → ORD_INVALID_STATUS_TRANSITION, không bù trừ gì")
    void terminalOrderCannotBeCancelled(OrderStatus terminal) {
      givenStoredOrder(order(terminal, couponId));

      assertError(
          catchThrowable(() -> lifecycle.cancel(orderId, "muộn rồi", "customer")),
          ConflictException.class,
          ErrorCode.ORD_INVALID_STATUS_TRANSITION);
      verifyNoInteractions(historyRepo, inventoryService, couponService);
    }
  }

  // ================================================================
  // expire
  // ================================================================

  @Nested
  @DisplayName("expire")
  class Expire {

    @Test
    @DisplayName("ứng viên: cutoff = now - pendingTtl theo Clock, đúng batch size")
    void overdueIdsUseClockAndConfig() {
      List<UUID> ids = List.of(UUID.randomUUID());
      when(orderRepo.findExpirationCandidates(NOW.minus(PENDING_TTL), BATCH_SIZE)).thenReturn(ids);

      assertThat(lifecycle.findOverdueOrderIds()).isSameAs(ids);
    }

    @Test
    @DisplayName("không khóa được (đã đổi trạng thái / worker khác giữ) → false, không làm gì")
    void notLockedMeansSkip() {
      when(orderRepo.lockForExpiration(orderId, NOW.minus(PENDING_TTL)))
          .thenReturn(Optional.empty());

      assertThat(lifecycle.expireIfOverdue(orderId)).isFalse();
      verify(orderRepo, never()).transitionStatus(any(), any(), any(), any());
      verifyNoInteractions(historyRepo, inventoryService, couponService);
    }

    @Test
    @DisplayName("khóa được → EXPIRED kèm lý do, history actor system, nhả hàng, hoàn coupon")
    void expiresLockedOrder() {
      when(orderRepo.lockForExpiration(orderId, NOW.minus(PENDING_TTL)))
          .thenReturn(Optional.of(order(OrderStatus.PENDING, couponId)));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();

      assertThat(lifecycle.expireIfOverdue(orderId)).isTrue();

      verify(orderRepo)
          .transitionStatus(orderId, "PENDING", "EXPIRED", "Auto-expired: unpaid after PT30M");
      OrderStatusHistory history = savedHistory();
      assertThat(history.getToStatus()).isEqualTo(OrderStatus.EXPIRED);
      assertThat(history.getActor()).isEqualTo(OrderLifecycle.SYSTEM_ACTOR);
      InOrder releases = inOrder(inventoryService);
      releases.verify(inventoryService).release(smallProductId, 1, orderId, "system");
      releases.verify(inventoryService).release(bigProductId, 2, orderId, "system");
      verify(couponService).revoke(couponId, orderId);
    }

    /**
     * Facade dựa vào việc exception lọt ra ngoài để cô lập đơn lỗi — lớp này không được nuốt nó.
     */
    @Test
    @DisplayName("nhả hàng hỏng → exception lọt ra, không hoàn coupon")
    void releaseFailurePropagates() {
      when(orderRepo.lockForExpiration(orderId, NOW.minus(PENDING_TTL)))
          .thenReturn(Optional.of(order(OrderStatus.PENDING, couponId)));
      givenTransitionSucceeds();
      givenItemsInReverseProductOrder();
      ConflictException invalidRelease =
          new ConflictException(ErrorCode.INV_RELEASE_INVALID, "reserved không đủ");
      doThrow(invalidRelease)
          .when(inventoryService)
          .release(smallProductId, 1, orderId, OrderLifecycle.SYSTEM_ACTOR);

      assertThatThrownBy(() -> lifecycle.expireIfOverdue(orderId)).isSameAs(invalidRelease);
      verifyNoInteractions(couponService);
    }
  }
}
