package com.tungph7.learnpostgres.feature.order.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.ForbiddenException;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;
import com.tungph7.learnpostgres.feature.customer.domain.Address;
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
import com.tungph7.learnpostgres.feature.order.domain.ShippingSnapshot;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemRequest;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;
import com.tungph7.learnpostgres.feature.order.repository.OrderItemRepository;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;
import com.tungph7.learnpostgres.feature.order.repository.StatusHistoryRepository;
import com.tungph7.learnpostgres.feature.order.repository.projection.ProductSnapshotRow;

/**
 * Thân của {@link OrderService#placeOrder} — tách khỏi {@link OrderServiceImpl} để mỗi lớp giữ một
 * việc và ít dependency (checkstyle {@code ParameterNumber} tối đa 8).
 *
 * <p>{@code MANDATORY}: lớp này không tự mở transaction. Transaction — cùng isolation và timeout
 * của nó — do {@code OrderServiceImpl.placeOrder} mở; gọi {@link #place} ở ngoài transaction là lỗi
 * lập trình và nổ ngay.
 *
 * <p>Thứ tự các bước:
 *
 * <ol>
 *   <li>Kiểm danh sách hàng — chưa chạm DB.
 *   <li>Customer ACTIVE; address thuộc đúng customer và chưa bị xoá.
 *   <li>Product tồn tại và đang bán; đọc giá / tên / sku để chụp snapshot.
 *   <li>Subtotal; coupon ({@code validate} — discount tính bằng đúng công thức của F04).
 *   <li>INSERT order (flush ngay), items, history {@code NULL → PENDING}.
 *   <li>Giữ hàng, rồi redeem coupon — hai bước chạm <b>hot row</b> (row tồn kho / coupon mà nhiều
 *       đơn cùng tranh) để CUỐI CÙNG: khóa của chúng giữ tới lúc commit, làm sau cùng thì giữ ngắn
 *       nhất.
 * </ol>
 *
 * <p>Bước nào ném thì cả transaction rollback — kể cả phần giữ hàng (F03) và redeem (F04), vì chúng
 * tham gia cùng transaction chứ không mở transaction riêng.
 */
@Service
class OrderPlacement {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacement.class);

  /** Giá trị {@code app.products.status} của product đang bán ({@code ck_products_status}, V5). */
  private static final String PRODUCT_ACTIVE = "ACTIVE";

  /**
   * F05 chưa tính phí ship: mọi đơn phí 0. Hệ quả: coupon FREE_SHIPPING vẫn redeem (tính lượt dùng)
   * nhưng không làm đổi total.
   */
  private static final BigDecimal SHIPPING_FEE = BigDecimal.ZERO;

  static final String PLACED_REASON = "Order placed";

  private final CustomerRepository customerRepo;
  private final AddressRepository addressRepo;
  private final OrderRepository orderRepo;
  private final OrderItemRepository itemRepo;
  private final StatusHistoryRepository historyRepo;
  private final OrderNumberGenerator orderNumberGenerator;
  private final InventoryService inventoryService;
  private final CouponService couponService;

  OrderPlacement(
      CustomerRepository customerRepo,
      AddressRepository addressRepo,
      OrderRepository orderRepo,
      OrderItemRepository itemRepo,
      StatusHistoryRepository historyRepo,
      OrderNumberGenerator orderNumberGenerator,
      InventoryService inventoryService,
      CouponService couponService) {
    this.customerRepo = customerRepo;
    this.addressRepo = addressRepo;
    this.orderRepo = orderRepo;
    this.itemRepo = itemRepo;
    this.historyRepo = historyRepo;
    this.orderNumberGenerator = orderNumberGenerator;
    this.inventoryService = inventoryService;
    this.couponService = couponService;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Order place(PlaceOrderRequest req) {
    requireValidItems(req.items());
    requireActiveCustomer(req.customerId());
    Address shipping = requireShippingAddress(req.customerId(), req.shippingAddressId());
    Map<UUID, ProductSnapshotRow> products = loadOrderableProducts(req.items());
    BigDecimal subtotal = subtotalOf(req.items(), products);
    AppliedCoupon coupon = applyCoupon(req.couponCode(), subtotal, req.customerId());

    Order order =
        new Order(
            orderNumberGenerator.next(),
            req.customerId(),
            shipping.getId(),
            ShippingSnapshot.of(shipping),
            subtotal,
            coupon.discount(),
            SHIPPING_FEE,
            coupon.couponId());
    order.setCustomerNote(req.customerNote());
    // Flush ngay, vì 2 lẽ:
    // - items, history, redemption đều có FK tới orders. Hibernate hiện INSERT theo thứ tự
    //   persist nên order vẫn đi trước, nhưng không có association nào cho nó BIẾT điều đó
    //   (OrderItem giữ orderId dạng UUID), còn order_inserts được phép sắp lại INSERT theo
    //   entity. Flush tường minh để không dựa vào chi tiết đó.
    // - Vi phạm constraint của orders nổ ngay ở dòng này, thay vì ở câu native query kế tiếp
    //   (UPDATE tồn kho trong bulkReserve kích hoạt auto-flush).
    order = orderRepo.saveAndFlush(order);

    String actor = req.customerId().toString();
    itemRepo.saveAll(toItems(order.getId(), req.items(), products));
    historyRepo.save(
        new OrderStatusHistory(order.getId(), null, OrderStatus.PENDING, PLACED_REASON, actor));

    // Hot rows cuối cùng. bulkReserve tự sắp theo productId → mọi đơn khóa row tồn kho cùng một
    // thứ tự, không thể A chờ B trong khi B chờ A.
    inventoryService.bulkReserve(toReserveItems(req.items()), order.getId(), actor);
    if (coupon.couponId() != null) {
      couponService.redeem(coupon.couponId(), order.getId(), req.customerId(), coupon.discount());
    }

    log.info(
        "Order placed: {} customer={} total={}",
        order.getOrderNumber(),
        req.customerId(),
        order.getTotal());
    return order;
  }

  // ================================================================
  // Kiểm tra đầu vào
  // ================================================================

  /**
   * Hình dạng danh sách hàng — kiểm trước mọi truy cập DB.
   *
   * <p>{@code @NotEmpty} / {@code @Min(1)} ở DTO đã chặn ở tầng API; lặp lại ở đây vì service còn
   * được gọi từ nơi không đi qua Bean Validation, và vì "đơn phải có hàng" là bất biến mà DB không
   * tự giữ — không constraint nào cấm một order không có item. Trùng product thì Bean Validation
   * không nhìn ra được (rule trên cả danh sách); {@code uq_order_items_order_product} có chặn,
   * nhưng tới lúc đó order đã INSERT xong.
   */
  private static void requireValidItems(List<OrderItemRequest> items) {
    if (items == null || items.isEmpty()) {
      throw new BadRequestException(
          ErrorCode.ORD_EMPTY_ITEMS, "Đơn hàng phải có ít nhất 1 sản phẩm.");
    }
    Set<UUID> seen = new HashSet<>();
    for (OrderItemRequest item : items) {
      if (item.quantity() < 1) {
        throw new BadRequestException(
            ErrorCode.VALIDATION_FAILED,
            "Số lượng của product '%s' phải ≥ 1, nhận %d."
                .formatted(item.productId(), item.quantity()));
      }
      if (!seen.add(item.productId())) {
        throw new BadRequestException(
            ErrorCode.VALIDATION_FAILED,
            "Product '%s' xuất hiện nhiều lần — gộp số lượng vào một dòng."
                .formatted(item.productId()));
      }
    }
  }

  private void requireActiveCustomer(UUID customerId) {
    Customer customer =
        customerRepo
            .findById(customerId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.CUS_NOT_FOUND,
                        "Customer '%s' không tồn tại.".formatted(customerId)));
    if (customer.getStatus() != CustomerStatus.ACTIVE) {
      throw new ForbiddenException(
          ErrorCode.ORD_CUSTOMER_INACTIVE,
          "Customer '%s' đang %s — không đặt được đơn."
              .formatted(customerId, customer.getStatus()));
    }
  }

  /**
   * Address phải thuộc đúng customer và chưa bị xoá mềm.
   *
   * <p>Kiểm chủ sở hữu TRƯỚC trạng thái xoá: address của người khác luôn ra 403, không để lộ nó đã
   * bị xoá hay chưa.
   */
  private Address requireShippingAddress(UUID customerId, UUID addressId) {
    Address address =
        addressRepo
            .findById(addressId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        ErrorCode.ORD_ADDRESS_INVALID,
                        "Address '%s' không tồn tại.".formatted(addressId)));
    if (!address.getCustomer().getId().equals(customerId)) {
      throw new ForbiddenException(
          ErrorCode.ORD_ADDRESS_FORBIDDEN,
          "Address '%s' không thuộc customer '%s'.".formatted(addressId, customerId));
    }
    if (address.isDeleted()) {
      throw new BadRequestException(
          ErrorCode.ORD_ADDRESS_INVALID, "Address '%s' đã bị xoá.".formatted(addressId));
    }
    return address;
  }

  /** Mọi product trong đơn phải tồn tại và đang ACTIVE (DRAFT và ARCHIVED đều không bán). */
  private Map<UUID, ProductSnapshotRow> loadOrderableProducts(List<OrderItemRequest> items) {
    List<UUID> ids = items.stream().map(OrderItemRequest::productId).toList();
    Map<UUID, ProductSnapshotRow> byId =
        itemRepo.findProductSnapshots(ids).stream()
            .collect(Collectors.toMap(ProductSnapshotRow::getId, Function.identity()));

    for (UUID id : ids) {
      ProductSnapshotRow product = byId.get(id);
      if (product == null) {
        throw new NotFoundException(
            ErrorCode.PRODUCT_NOT_FOUND, "Product '%s' không tồn tại.".formatted(id));
      }
      if (!PRODUCT_ACTIVE.equals(product.getStatus())) {
        throw new BadRequestException(
            ErrorCode.ORD_PRODUCT_INACTIVE,
            "Product '%s' (%s) đang %s — không bán."
                .formatted(product.getName(), id, product.getStatus()));
      }
    }
    return byId;
  }

  // ================================================================
  // Tiền + coupon
  // ================================================================

  /** Giá NUMERIC(12,2) nhân số nguyên → mỗi dòng và tổng đều đúng 2 chữ số thập phân. */
  private static BigDecimal subtotalOf(
      List<OrderItemRequest> items, Map<UUID, ProductSnapshotRow> products) {
    return items.stream()
        .map(
            item ->
                products
                    .get(item.productId())
                    .getPrice()
                    .multiply(BigDecimal.valueOf(item.quantity())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Bước 1/2 của coupon: {@code validate} (chỉ đọc). Bước 2 — {@code redeem} — ở cuối {@link
   * #place}.
   *
   * <p>Kết quả validate có thể cũ đi trước lúc redeem (mã vừa hết lượt / hết hạn trong khoảng
   * giữa). Không sao: redeem tự kiểm lại trong WHERE của câu UPDATE (F04) và ném, kéo rollback cả
   * đơn.
   */
  private AppliedCoupon applyCoupon(String code, BigDecimal subtotal, UUID customerId) {
    if (code == null || code.isBlank()) {
      return AppliedCoupon.NONE;
    }
    ValidateCouponResponse result =
        couponService.validate(new ValidateCouponRequest(code, subtotal, customerId));
    if (!result.valid()) {
      throw couponRejected(code, result);
    }
    return new AppliedCoupon(result.couponId(), result.discountAmount());
  }

  /**
   * {@code validate} không ném — nó trả mã lỗi để giỏ hàng hiển thị. Tới lúc đặt đơn thì mã hỏng
   * phải chặn đơn, nên dịch lại thành exception và giữ đúng HTTP status mặc định của mã đó ({@link
   * ErrorCode} là nguồn duy nhất): {@code CPN_NOT_FOUND} 404, {@code CPN_EXHAUSTED} / {@code
   * CPN_PER_CUSTOMER_LIMIT} 409, {@code CPN_EXPIRED} / {@code CPN_MIN_ORDER_NOT_MET} 400.
   */
  private static BusinessException couponRejected(String code, ValidateCouponResponse result) {
    ErrorCode error = ErrorCode.valueOf(result.errorCode());
    String detail = "Mã giảm giá '%s' không dùng được: %s".formatted(code, result.message());
    return switch (error.defaultStatus()) {
      case NOT_FOUND -> new NotFoundException(error, detail);
      case CONFLICT -> new ConflictException(error, detail);
      default -> new BadRequestException(error, detail);
    };
  }

  // ================================================================
  // Dựng dữ liệu ghi
  // ================================================================

  private static List<OrderItem> toItems(
      UUID orderId, List<OrderItemRequest> items, Map<UUID, ProductSnapshotRow> products) {
    return items.stream()
        .map(
            item -> {
              ProductSnapshotRow product = products.get(item.productId());
              return new OrderItem(
                  orderId,
                  product.getId(),
                  product.getName(),
                  product.getSku(),
                  item.quantity(),
                  product.getPrice());
            })
        .toList();
  }

  private static List<ReserveItem> toReserveItems(List<OrderItemRequest> items) {
    return items.stream().map(item -> new ReserveItem(item.productId(), item.quantity())).toList();
  }

  /**
   * Coupon đã qua validate: id để redeem + số tiền giảm. {@link #NONE} khi đơn không dùng coupon.
   */
  private record AppliedCoupon(UUID couponId, BigDecimal discount) {
    static final AppliedCoupon NONE = new AppliedCoupon(null, BigDecimal.ZERO);
  }
}
