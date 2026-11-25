package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.customer.repository.CustomerRepository;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemResponse;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;
import com.tungph7.learnpostgres.feature.order.repository.StatusHistoryRepository;
import com.tungph7.learnpostgres.feature.order.repository.projection.OrderDetailRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test cho {@link OrderServiceImpl}: ủy quyền đúng tham số, vòng lặp của job expire, các
 * method đọc và phần parse JSON của detail.
 *
 * <p>{@link JsonMapper} dựng mặc định (không qua Spring): thứ đang kiểm là JSON Postgres sinh ra
 * map được vào record có kiểu. {@code OrderServiceIntegrationTest} kiểm lại với {@code
 * ObjectMapper} thật của app và JSON thật từ DB.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

  @Mock OrderPlacement placement;
  @Mock OrderLifecycle lifecycle;
  @Mock OrderRepository orderRepo;
  @Mock StatusHistoryRepository historyRepo;
  @Mock CustomerRepository customerRepo;

  private OrderServiceImpl service;

  private final UUID orderId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new OrderServiceImpl(
            placement,
            lifecycle,
            orderRepo,
            historyRepo,
            customerRepo,
            JsonMapper.builder().build());
  }

  private static void assertError(Throwable thrown, Class<?> type, ErrorCode code) {
    assertThat(thrown).isInstanceOf(type);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  // ================================================================
  // Ủy quyền
  // ================================================================

  @Nested
  @DisplayName("ủy quyền cho OrderPlacement / OrderLifecycle")
  class Delegation {

    @Test
    @DisplayName("placeOrder → OrderPlacement.place")
    void placeOrder() {
      PlaceOrderRequest req = new PlaceOrderRequest(UUID.randomUUID(), List.of(), null, null, null);
      Order placed = mock(Order.class);
      when(placement.place(req)).thenReturn(placed);

      assertThat(service.placeOrder(req)).isSameAs(placed);
    }

    @Test
    @DisplayName("cancelOrder / transitionStatus → OrderLifecycle với nguyên tham số")
    void cancelAndTransition() {
      Order moved = mock(Order.class);
      when(lifecycle.transition(orderId, OrderStatus.SHIPPED, "Giao GHN", "admin"))
          .thenReturn(moved);

      service.cancelOrder(orderId, "Khách đổi ý", "customer");
      Order result = service.transitionStatus(orderId, OrderStatus.SHIPPED, "Giao GHN", "admin");

      verify(lifecycle).cancel(orderId, "Khách đổi ý", "customer");
      assertThat(result).isSameAs(moved);
    }

    @Test
    @DisplayName("markPaid / markShipped / markDelivered — lý do + actor cố định")
    void markShortcuts() {
      service.markPaid(orderId);
      service.markShipped(orderId);
      service.markDelivered(orderId);

      verify(lifecycle).transition(orderId, OrderStatus.PAID, "Payment confirmed", "system");
      verify(lifecycle).transition(orderId, OrderStatus.SHIPPED, "Shipped", "ops");
      verify(lifecycle).transition(orderId, OrderStatus.DELIVERED, "Delivered", "ops");
    }
  }

  // ================================================================
  // Job expire
  // ================================================================

  @Nested
  @DisplayName("expirePendingOrders")
  class ExpirePendingOrders {

    /**
     * Đơn thứ hai lỗi không được chặn đơn thứ ba — đó là lý do mỗi đơn chạy transaction riêng và
     * vòng lặp bắt exception từng đơn.
     */
    @Test
    @DisplayName("đếm đơn thực sự expire; đơn lỗi được bỏ qua, các đơn sau vẫn chạy")
    void failingOrderDoesNotStopTheBatch() {
      UUID expired = UUID.randomUUID();
      UUID failing = UUID.randomUUID();
      UUID skipped = UUID.randomUUID();
      UUID expiredToo = UUID.randomUUID();
      when(lifecycle.findOverdueOrderIds())
          .thenReturn(List.of(expired, failing, skipped, expiredToo));
      when(lifecycle.expireIfOverdue(expired)).thenReturn(true);
      when(lifecycle.expireIfOverdue(failing))
          .thenThrow(new ConflictException(ErrorCode.INV_RELEASE_INVALID, "reserved không đủ"));
      when(lifecycle.expireIfOverdue(skipped)).thenReturn(false);
      when(lifecycle.expireIfOverdue(expiredToo)).thenReturn(true);

      assertThat(service.expirePendingOrders()).isEqualTo(2);
      verify(lifecycle).expireIfOverdue(expiredToo);
    }

    @Test
    @DisplayName("không có đơn quá hạn → 0, không expire gì")
    void nothingOverdue() {
      when(lifecycle.findOverdueOrderIds()).thenReturn(List.of());

      assertThat(service.expirePendingOrders()).isZero();
      verify(lifecycle, never()).expireIfOverdue(any());
    }
  }

  // ================================================================
  // Đọc
  // ================================================================

  @Nested
  @DisplayName("đọc")
  class Reads {

    @Test
    @DisplayName("getById — có thì trả, không có → ORD_NOT_FOUND")
    void getById() {
      Order order = mock(Order.class);
      when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
      UUID unknown = UUID.randomUUID();
      when(orderRepo.findById(unknown)).thenReturn(Optional.empty());

      assertThat(service.getById(orderId)).isSameAs(order);
      assertError(
          catchThrowable(() -> service.getById(unknown)),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
    }

    @Test
    @DisplayName("getByOrderNumber — có thì trả, không có → ORD_NOT_FOUND")
    void getByOrderNumber() {
      Order order = mock(Order.class);
      when(orderRepo.findByOrderNumber("ORD-202609-000001")).thenReturn(Optional.of(order));
      when(orderRepo.findByOrderNumber("ORD-000000-000000")).thenReturn(Optional.empty());

      assertThat(service.getByOrderNumber("ORD-202609-000001")).isSameAs(order);
      assertError(
          catchThrowable(() -> service.getByOrderNumber("ORD-000000-000000")),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
    }

    @Test
    @DisplayName("listCustomerOrders — customer không tồn tại → CUS_NOT_FOUND, không query đơn")
    void listOrdersOfUnknownCustomer() {
      UUID customerId = UUID.randomUUID();
      when(customerRepo.existsById(customerId)).thenReturn(false);

      assertError(
          catchThrowable(() -> service.listCustomerOrders(customerId, PageRequest.of(0, 20))),
          NotFoundException.class,
          ErrorCode.CUS_NOT_FOUND);
      verifyNoInteractions(orderRepo);
    }

    @Test
    @DisplayName("listCustomerOrders — customer có → trang đơn từ repository")
    void listOrdersOfCustomer() {
      UUID customerId = UUID.randomUUID();
      Pageable pageable = PageRequest.of(0, 20);
      Page<Order> page = new PageImpl<>(List.of(mock(Order.class)), pageable, 1);
      when(customerRepo.existsById(customerId)).thenReturn(true);
      when(orderRepo.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)).thenReturn(page);

      assertThat(service.listCustomerOrders(customerId, pageable)).isSameAs(page);
    }

    @Test
    @DisplayName(
        "getStatusHistory — đơn không tồn tại → ORD_NOT_FOUND; có → lịch sử theo thời gian")
    void statusHistory() {
      List<OrderStatusHistory> history =
          List.of(new OrderStatusHistory(orderId, null, OrderStatus.PENDING, "Order placed", "c"));
      when(orderRepo.existsById(orderId)).thenReturn(true);
      when(historyRepo.findByOrderIdOrderByChangedAtAsc(orderId)).thenReturn(history);
      UUID unknown = UUID.randomUUID();
      when(orderRepo.existsById(unknown)).thenReturn(false);

      assertThat(service.getStatusHistory(orderId)).isSameAs(history);
      assertError(
          catchThrowable(() -> service.getStatusHistory(unknown)),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
    }
  }

  // ================================================================
  // Detail — parse JSON của findDetailById
  // ================================================================

  @Nested
  @DisplayName("getDetail")
  class Detail {

    private static final String ITEMS_JSON =
        """
        [{"id":"0192f3a0-0000-7000-8000-000000000001",
          "productId":"0192f3a0-0000-7000-8000-00000000000a",
          "productName":"Keyboard","productSku":null,"quantity":3,
          "unitPrice":0.10,"lineTotal":0.30}]
        """;

    /** Offset theo TimeZone của session Postgres — dev là +07:00, Testcontainers là +00:00. */
    private static final String HISTORY_JSON =
        """
        [{"fromStatus":null,"toStatus":"PENDING","reason":"Order placed","actor":"c1",
          "changedAt":"2026-09-28T17:00:00.123456+07:00"},
         {"fromStatus":"PENDING","toStatus":"PAID","reason":"Payment confirmed","actor":"system",
          "changedAt":"2026-09-28T10:05:00+00:00"}]
        """;

    @Test
    @DisplayName("không có → ORD_NOT_FOUND")
    void unknownOrder() {
      when(orderRepo.findDetailById(orderId)).thenReturn(Optional.empty());

      assertError(
          catchThrowable(() -> service.getDetail(orderId)),
          NotFoundException.class,
          ErrorCode.ORD_NOT_FOUND);
    }

    /**
     * Tiền giữ nguyên scale ({@code 0.30}, không phải {@code 0.3} như khi parse vào Map), null giữ
     * null, thời điểm kèm offset nào cũng ra đúng instant UTC.
     */
    @Test
    @DisplayName("items / history JSON → record có kiểu: BigDecimal giữ scale, offset → Instant")
    void parsesItemsAndHistory() {
      OrderDetailRow row = detailRow();
      when(orderRepo.findDetailById(orderId)).thenReturn(Optional.of(row));

      OrderDetailResponse detail = service.getDetail(orderId);

      assertThat(detail.id()).isEqualTo(orderId);
      assertThat(detail.orderNumber()).isEqualTo("ORD-202609-000001");
      assertThat(detail.status()).isEqualTo("PAID");
      assertThat(detail.total()).isEqualByComparingTo("0.30");
      assertThat(detail.shippingLine2()).isEqualTo("Tầng 3");
      assertThat(detail.shippingCountry()).isEqualTo("VN");
      assertThat(detail.customerName()).isEqualTo("John Doe");
      assertThat(detail.couponId()).isNull();

      OrderItemResponse item = detail.items().get(0);
      assertThat(detail.items()).hasSize(1);
      assertThat(item.productName()).isEqualTo("Keyboard");
      assertThat(item.productSku()).isNull();
      assertThat(item.quantity()).isEqualTo(3);
      assertThat(item.lineTotal()).isEqualTo(new BigDecimal("0.30"));

      assertThat(detail.history())
          .extracting(
              StatusHistoryResponse::fromStatus,
              StatusHistoryResponse::toStatus,
              StatusHistoryResponse::changedAt)
          .containsExactly(
              tuple(null, "PENDING", Instant.parse("2026-09-28T10:00:00.123456Z")),
              tuple("PENDING", "PAID", Instant.parse("2026-09-28T10:05:00Z")));
    }

    private OrderDetailRow detailRow() {
      OrderDetailRow row = mock(OrderDetailRow.class);
      when(row.getId()).thenReturn(orderId);
      when(row.getOrderNumber()).thenReturn("ORD-202609-000001");
      when(row.getStatus()).thenReturn("PAID");
      when(row.getSubtotal()).thenReturn(new BigDecimal("0.30"));
      when(row.getDiscountAmount()).thenReturn(new BigDecimal("0.00"));
      when(row.getShippingFee()).thenReturn(new BigDecimal("0.00"));
      when(row.getTotal()).thenReturn(new BigDecimal("0.30"));
      when(row.getShippingRecipient()).thenReturn("John Doe");
      when(row.getShippingPhone()).thenReturn("+84901234567");
      when(row.getShippingLine1()).thenReturn("1 Le Loi");
      when(row.getShippingLine2()).thenReturn("Tầng 3");
      when(row.getShippingCity()).thenReturn("HCM");
      when(row.getShippingCountry()).thenReturn("VN");
      when(row.getCreatedAt()).thenReturn(Instant.parse("2026-09-28T10:00:00Z"));
      when(row.getCustomerId()).thenReturn(UUID.randomUUID());
      when(row.getCustomerEmail()).thenReturn("john@example.com");
      when(row.getCustomerName()).thenReturn("John Doe");
      when(row.getCouponId()).thenReturn(null);
      when(row.getCouponCode()).thenReturn(null);
      when(row.getItemsJson()).thenReturn(ITEMS_JSON);
      when(row.getHistoryJson()).thenReturn(HISTORY_JSON);
      return row;
    }
  }
}
