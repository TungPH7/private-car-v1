package com.tungph7.learnpostgres.feature.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.ForbiddenException;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;
import com.tungph7.learnpostgres.feature.customer.CustomerFixtures;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus;
import com.tungph7.learnpostgres.feature.customer.repository.AddressRepository;
import com.tungph7.learnpostgres.feature.customer.repository.CustomerRepository;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService.ReserveItem;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemRequest;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;
import com.tungph7.learnpostgres.feature.order.repository.OrderItemRepository;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;
import com.tungph7.learnpostgres.feature.order.repository.StatusHistoryRepository;
import com.tungph7.learnpostgres.feature.order.repository.projection.ProductSnapshotRow;

/**
 * Unit test cho {@link OrderPlacement} — mọi dependency là mock, không chạm DB.
 *
 * <p>Ở đây chỉ kiểm phần lớp này tự quyết: kiểm gì, theo thứ tự nào, ra mã lỗi nào, dựng order /
 * item / history với giá trị nào, và gọi F03/F04 với tham số nào. Transaction thật (rollback khi
 * một bước hỏng, READ COMMITTED khi tranh chấp) chỉ Postgres thật chứng minh được — xem {@code
 * OrderServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementTest {

  private static final String ORDER_NUMBER = "ORD-202609-000042";
  private static final String COUPON_CODE = "SUM10";

  @Mock CustomerRepository customerRepo;
  @Mock AddressRepository addressRepo;
  @Mock OrderRepository orderRepo;
  @Mock OrderItemRepository itemRepo;
  @Mock StatusHistoryRepository historyRepo;
  @Mock OrderNumberGenerator orderNumberGenerator;
  @Mock InventoryService inventoryService;
  @Mock CouponService couponService;

  private OrderPlacement placement;

  private final UUID customerId = UUID.randomUUID();
  private final UUID addressId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID keyboardId = UUID.randomUUID();
  private final UUID mouseId = UUID.randomUUID();

  private Customer customer;
  private Address address;

  /** Snapshot product giả — {@link ProductSnapshotRow} là interface projection. */
  private record Product(UUID id, String name, String sku, BigDecimal price, String status)
      implements ProductSnapshotRow {
    @Override
    public UUID getId() {
      return id;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getSku() {
      return sku;
    }

    @Override
    public BigDecimal getPrice() {
      return price;
    }

    @Override
    public String getStatus() {
      return status;
    }
  }

  @BeforeEach
  void setUp() {
    placement =
        new OrderPlacement(
            customerRepo,
            addressRepo,
            orderRepo,
            itemRepo,
            historyRepo,
            orderNumberGenerator,
            inventoryService,
            couponService);
    customer = CustomerFixtures.customerWithId(customerId, "buyer@example.com");
    address = CustomerFixtures.addressWithId(addressId, customer, AddressType.SHIPPING);
    address.setLine2("Tầng 3");
  }

  private PlaceOrderRequest request(String couponCode, OrderItemRequest... items) {
    return new PlaceOrderRequest(customerId, List.of(items), addressId, couponCode, "Giao giờ HC");
  }

  /** 2 bàn phím @100.00 + 1 chuột @12.50 → subtotal 212.50. */
  private PlaceOrderRequest twoProducts(String couponCode) {
    return request(
        couponCode, new OrderItemRequest(keyboardId, 2), new OrderItemRequest(mouseId, 1));
  }

  private void givenCustomerAndAddress() {
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(addressRepo.findById(addressId)).thenReturn(Optional.of(address));
  }

  private void givenProducts(Product... products) {
    when(itemRepo.findProductSnapshots(anyList())).thenReturn(List.of(products));
  }

  private void givenActiveProducts() {
    givenProducts(
        new Product(keyboardId, "Keyboard", "KB-01", new BigDecimal("100.00"), "ACTIVE"),
        new Product(mouseId, "Mouse", null, new BigDecimal("12.50"), "ACTIVE"));
  }

  /** {@code saveAndFlush} trả chính entity, id gán như {@code @PrePersist} sẽ làm. */
  private void givenOrderIsSaved() {
    when(orderNumberGenerator.next()).thenReturn(ORDER_NUMBER);
    when(orderRepo.saveAndFlush(any(Order.class)))
        .thenAnswer(
            inv -> {
              Order order = inv.getArgument(0);
              ReflectionTestUtils.setField(order, "id", orderId);
              return order;
            });
  }

  private static void assertError(Throwable thrown, Class<?> type, ErrorCode code) {
    assertThat(thrown).isInstanceOf(type);
    assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
  }

  // ================================================================
  // Happy path
  // ================================================================

  @Nested
  @DisplayName("đặt đơn thành công")
  class HappyPath {

    @Test
    @DisplayName("không coupon — order chụp đủ snapshot, total = subtotal, không đụng coupon")
    void withoutCoupon() {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();

      Order order = placement.place(twoProducts(null));

      assertThat(order.getId()).isEqualTo(orderId);
      assertThat(order.getOrderNumber()).isEqualTo(ORDER_NUMBER);
      assertThat(order.getCustomerId()).isEqualTo(customerId);
      assertThat(order.getShippingAddressId()).isEqualTo(addressId);
      assertThat(order.getShippingRecipient()).isEqualTo("John Doe");
      assertThat(order.getShippingAddressLine2()).isEqualTo("Tầng 3");
      assertThat(order.getShippingCountry()).isEqualTo("VN");
      assertThat(order.getSubtotal()).isEqualByComparingTo("212.50");
      assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
      assertThat(order.getShippingFee()).isEqualByComparingTo("0");
      assertThat(order.getTotal()).isEqualByComparingTo("212.50");
      assertThat(order.getCouponId()).isNull();
      assertThat(order.getCustomerNote()).isEqualTo("Giao giờ HC");
      assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
      verifyNoInteractions(couponService);
    }

    @Test
    @DisplayName("items chụp tên / sku / đơn giá của product, gắn orderId thật")
    @SuppressWarnings("unchecked")
    void itemsSnapshotProducts() {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();

      placement.place(twoProducts(null));

      ArgumentCaptor<List<OrderItem>> items = ArgumentCaptor.forClass(List.class);
      verify(itemRepo).saveAll(items.capture());
      assertThat(items.getValue())
          .extracting(
              OrderItem::getOrderId,
              OrderItem::getProductId,
              OrderItem::getProductName,
              OrderItem::getProductSku,
              OrderItem::getQuantity,
              OrderItem::getUnitPrice)
          .containsExactly(
              tuple(orderId, keyboardId, "Keyboard", "KB-01", 2, new BigDecimal("100.00")),
              tuple(orderId, mouseId, "Mouse", null, 1, new BigDecimal("12.50")));
    }

    @Test
    @DisplayName("history NULL → PENDING, actor là customer đặt đơn")
    void initialHistoryRow() {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();

      placement.place(twoProducts(null));

      ArgumentCaptor<OrderStatusHistory> history =
          ArgumentCaptor.forClass(OrderStatusHistory.class);
      verify(historyRepo).save(history.capture());
      assertThat(history.getValue().getOrderId()).isEqualTo(orderId);
      assertThat(history.getValue().getFromStatus()).isNull();
      assertThat(history.getValue().getToStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(history.getValue().getReason()).isEqualTo(OrderPlacement.PLACED_REASON);
      assertThat(history.getValue().getActor()).isEqualTo(customerId.toString());
    }

    @Test
    @DisplayName("giữ hàng đủ mọi dòng, gắn orderId thật — việc sắp theo productId là của F03")
    void reservesEveryLine() {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();

      placement.place(twoProducts(null));

      verify(inventoryService)
          .bulkReserve(
              List.of(new ReserveItem(keyboardId, 2), new ReserveItem(mouseId, 1)),
              orderId,
              customerId.toString());
    }

    @Test
    @DisplayName("có coupon — discount lấy từ validate, redeem đúng số tiền đó cho order thật")
    void withCoupon() {
      UUID couponId = UUID.randomUUID();
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();
      when(couponService.validate(
              new ValidateCouponRequest(COUPON_CODE, new BigDecimal("212.50"), customerId)))
          .thenReturn(
              ValidateCouponResponse.ok(
                  couponId, COUPON_CODE, CouponType.PERCENT, new BigDecimal("21.25"), false));

      Order order = placement.place(twoProducts(COUPON_CODE));

      assertThat(order.getCouponId()).isEqualTo(couponId);
      assertThat(order.getDiscountAmount()).isEqualByComparingTo("21.25");
      assertThat(order.getTotal()).isEqualByComparingTo("191.25");
      verify(couponService).redeem(couponId, orderId, customerId, new BigDecimal("21.25"));
    }

    /**
     * Thứ tự ghi là một phần của thiết kế: order phải có trước (FK), còn 2 bước chạm hot row để
     * cuối để khóa của chúng giữ ngắn nhất.
     */
    @Test
    @DisplayName("thứ tự ghi: order (flush) → items → history → giữ hàng → redeem")
    void writeOrderPutsHotRowsLast() {
      UUID couponId = UUID.randomUUID();
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();
      when(couponService.validate(any()))
          .thenReturn(
              ValidateCouponResponse.ok(
                  couponId, COUPON_CODE, CouponType.AMOUNT, new BigDecimal("10.00"), false));

      placement.place(twoProducts(COUPON_CODE));

      InOrder writes = inOrder(orderRepo, itemRepo, historyRepo, inventoryService, couponService);
      writes.verify(orderRepo).saveAndFlush(any(Order.class));
      writes.verify(itemRepo).saveAll(anyList());
      writes.verify(historyRepo).save(any(OrderStatusHistory.class));
      writes.verify(inventoryService).bulkReserve(anyList(), any(), any());
      writes.verify(couponService).redeem(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("couponCode rỗng / toàn khoảng trắng = không dùng coupon")
    void blankCouponCodeMeansNoCoupon(String blank) {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();

      Order order = placement.place(twoProducts(blank));

      assertThat(order.getCouponId()).isNull();
      verifyNoInteractions(couponService);
    }

    @Test
    @DisplayName("giữ hàng hỏng → exception đi thẳng ra, không redeem coupon")
    void reserveFailureStopsBeforeRedeem() {
      givenCustomerAndAddress();
      givenActiveProducts();
      givenOrderIsSaved();
      when(couponService.validate(any()))
          .thenReturn(
              ValidateCouponResponse.ok(
                  UUID.randomUUID(), COUPON_CODE, CouponType.AMOUNT, BigDecimal.TEN, false));
      ConflictException outOfStock =
          new ConflictException(ErrorCode.INV_INSUFFICIENT_STOCK, "hết hàng");
      doThrow(outOfStock).when(inventoryService).bulkReserve(anyList(), any(), any());

      assertThatThrownBy(() -> placement.place(twoProducts(COUPON_CODE))).isSameAs(outOfStock);
      verify(couponService, never()).redeem(any(), any(), any(), any());
    }
  }

  // ================================================================
  // Danh sách hàng — kiểm trước mọi truy cập DB
  // ================================================================

  @Nested
  @DisplayName("danh sách hàng")
  class Items {

    @Test
    @DisplayName("rỗng → ORD_EMPTY_ITEMS, chưa chạm DB")
    void emptyItems() {
      assertError(
          catchThrowable(() -> placement.place(request(null))),
          BadRequestException.class,
          ErrorCode.ORD_EMPTY_ITEMS);
      verifyNoInteractions(customerRepo, addressRepo, itemRepo, orderRepo, orderNumberGenerator);
    }

    @Test
    @DisplayName("null → ORD_EMPTY_ITEMS")
    void nullItems() {
      PlaceOrderRequest req = new PlaceOrderRequest(customerId, null, addressId, null, null);

      assertError(
          catchThrowable(() -> placement.place(req)),
          BadRequestException.class,
          ErrorCode.ORD_EMPTY_ITEMS);
    }

    @Test
    @DisplayName("quantity 0 → VALIDATION_FAILED, chưa chạm DB")
    void zeroQuantity() {
      assertError(
          catchThrowable(() -> placement.place(request(null, new OrderItemRequest(keyboardId, 0)))),
          BadRequestException.class,
          ErrorCode.VALIDATION_FAILED);
      verifyNoInteractions(customerRepo, itemRepo, orderRepo);
    }

    /** Bean Validation không nhìn ra rule này; DB có UNIQUE nhưng lúc đó order đã INSERT xong. */
    @Test
    @DisplayName("2 dòng cùng product → VALIDATION_FAILED, chưa chạm DB")
    void duplicateProduct() {
      Throwable thrown =
          catchThrowable(
              () ->
                  placement.place(
                      request(
                          null,
                          new OrderItemRequest(keyboardId, 1),
                          new OrderItemRequest(keyboardId, 2))));

      assertError(thrown, BadRequestException.class, ErrorCode.VALIDATION_FAILED);
      assertThat(thrown).hasMessageContaining(keyboardId.toString());
      verifyNoInteractions(customerRepo, itemRepo, orderRepo);
    }
  }

  // ================================================================
  // Customer + address
  // ================================================================

  @Nested
  @DisplayName("customer và address")
  class CustomerAndAddress {

    @Test
    @DisplayName("customer không tồn tại → CUS_NOT_FOUND (404)")
    void unknownCustomer() {
      when(customerRepo.findById(customerId)).thenReturn(Optional.empty());

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          NotFoundException.class,
          ErrorCode.CUS_NOT_FOUND);
    }

    @Test
    @DisplayName("customer SUSPENDED → ORD_CUSTOMER_INACTIVE (403), không đọc tiếp")
    void suspendedCustomer() {
      customer.setStatus(CustomerStatus.SUSPENDED);
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          ForbiddenException.class,
          ErrorCode.ORD_CUSTOMER_INACTIVE);
      verifyNoInteractions(addressRepo, itemRepo, orderRepo);
    }

    @Test
    @DisplayName("address không tồn tại → ORD_ADDRESS_INVALID (400)")
    void unknownAddress() {
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.findById(addressId)).thenReturn(Optional.empty());

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          BadRequestException.class,
          ErrorCode.ORD_ADDRESS_INVALID);
    }

    @Test
    @DisplayName("address của customer khác → ORD_ADDRESS_FORBIDDEN (403)")
    void addressOfAnotherCustomer() {
      Customer other = CustomerFixtures.customerWithId(UUID.randomUUID(), "other@example.com");
      Address foreign = CustomerFixtures.addressWithId(addressId, other, AddressType.SHIPPING);
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.findById(addressId)).thenReturn(Optional.of(foreign));

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          ForbiddenException.class,
          ErrorCode.ORD_ADDRESS_FORBIDDEN);
      verifyNoInteractions(itemRepo, orderRepo);
    }

    @Test
    @DisplayName("address đã xoá mềm → ORD_ADDRESS_INVALID (400)")
    void deletedAddress() {
      address.setDeleted(true);
      givenCustomerAndAddress();

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          BadRequestException.class,
          ErrorCode.ORD_ADDRESS_INVALID);
    }

    @Test
    @DisplayName("address của người khác VÀ đã xoá → vẫn 403, không lộ trạng thái xoá")
    void ownershipIsCheckedBeforeDeletion() {
      Customer other = CustomerFixtures.customerWithId(UUID.randomUUID(), "other@example.com");
      Address foreign = CustomerFixtures.addressWithId(addressId, other, AddressType.SHIPPING);
      foreign.setDeleted(true);
      when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
      when(addressRepo.findById(addressId)).thenReturn(Optional.of(foreign));

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          ForbiddenException.class,
          ErrorCode.ORD_ADDRESS_FORBIDDEN);
    }
  }

  // ================================================================
  // Product
  // ================================================================

  @Nested
  @DisplayName("product")
  class Products {

    @Test
    @DisplayName("product không tồn tại → PRODUCT_NOT_FOUND (404), chưa ghi gì")
    void unknownProduct() {
      givenCustomerAndAddress();
      givenProducts(
          new Product(keyboardId, "Keyboard", "KB-01", new BigDecimal("100.00"), "ACTIVE"));

      Throwable thrown = catchThrowable(() -> placement.place(twoProducts(null)));

      assertError(thrown, NotFoundException.class, ErrorCode.PRODUCT_NOT_FOUND);
      assertThat(thrown).hasMessageContaining(mouseId.toString());
      verifyNoInteractions(orderRepo, orderNumberGenerator, inventoryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "DRAFT"})
    @DisplayName("product không ACTIVE → ORD_PRODUCT_INACTIVE (400), chưa ghi gì")
    void inactiveProduct(String status) {
      givenCustomerAndAddress();
      givenProducts(
          new Product(keyboardId, "Keyboard", "KB-01", new BigDecimal("100.00"), "ACTIVE"),
          new Product(mouseId, "Mouse", null, new BigDecimal("12.50"), status));

      assertError(
          catchThrowable(() -> placement.place(twoProducts(null))),
          BadRequestException.class,
          ErrorCode.ORD_PRODUCT_INACTIVE);
      verifyNoInteractions(orderRepo, orderNumberGenerator, inventoryService);
    }
  }

  // ================================================================
  // Coupon bị từ chối ở validate
  // ================================================================

  @Nested
  @DisplayName("coupon bị từ chối")
  class CouponRejected {

    /**
     * validate trả mã lỗi chứ không ném; placement dịch lại thành exception với đúng HTTP status
     * mặc định của mã đó. Chưa cấp số đơn: {@code nextval} không rollback, cấp rồi mới từ chối là
     * phí một số.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
      "CPN_NOT_FOUND, NOT_FOUND",
      "CPN_EXHAUSTED, CONFLICT",
      "CPN_PER_CUSTOMER_LIMIT, CONFLICT",
      "CPN_EXPIRED, BAD_REQUEST",
      "CPN_MIN_ORDER_NOT_MET, BAD_REQUEST"
    })
    @DisplayName("mã lỗi của validate → exception mang đúng status mặc định của mã")
    void rejectionKeepsDefaultStatus(ErrorCode code, HttpStatus status) {
      givenCustomerAndAddress();
      givenActiveProducts();
      when(couponService.validate(any()))
          .thenReturn(ValidateCouponResponse.fail(code, "không dùng được"));

      Throwable thrown = catchThrowable(() -> placement.place(twoProducts(COUPON_CODE)));

      assertThat(thrown).isInstanceOf(BusinessException.class);
      assertThat(((BusinessException) thrown).getCode()).isEqualTo(code);
      assertThat(((BusinessException) thrown).getStatus()).isEqualTo(status);
      assertThat(thrown).hasMessageContaining(COUPON_CODE);
      verifyNoInteractions(orderRepo, orderNumberGenerator, inventoryService);
    }
  }
}
