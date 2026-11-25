package com.tungph7.learnpostgres.feature.order.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Cài đặt {@link OrderService} — nơi đặt <b>ranh giới transaction</b>, còn thân nghiệp vụ nằm ở 2
 * lớp cùng package:
 *
 * <ul>
 *   <li>{@link OrderPlacement} — đặt đơn.
 *   <li>{@link OrderLifecycle} — chuyển trạng thái, hủy, expire.
 * </ul>
 *
 * <p>Tách ra vì gộp một lớp thì constructor cần 11 dependency (checkstyle {@code ParameterNumber}
 * tối đa 8), và vì job expire cần gọi một method {@code @Transactional} cho TỪNG đơn — gọi method
 * của chính lớp mình là self-invocation, không đi qua proxy nên không có transaction nào cả (quy
 * ước SP-014).
 *
 * <p>Mặc định readOnly; method ghi override bằng {@code @Transactional}. Method ghi ở đây chỉ ủy
 * quyền, nhưng annotation của chúng mới là thứ quyết định transaction: {@code OrderPlacement} /
 * {@code OrderLifecycle} tham gia transaction này ({@code MANDATORY}), không mở cái mới.
 */
@Service
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

  /** Trần của cả transaction placeOrder — không để một request đứng chờ khóa tồn kho vô hạn. */
  private static final int PLACE_ORDER_TIMEOUT_SECONDS = 30;

  private static final String OPS_ACTOR = "ops";

  private static final TypeReference<List<OrderItemResponse>> ITEM_LIST = new TypeReference<>() {};
  private static final TypeReference<List<StatusHistoryResponse>> HISTORY_LIST =
      new TypeReference<>() {};

  private final OrderPlacement placement;
  private final OrderLifecycle lifecycle;
  private final OrderRepository orderRepo;
  private final StatusHistoryRepository historyRepo;
  private final CustomerRepository customerRepo;
  private final ObjectMapper objectMapper;

  public OrderServiceImpl(
      OrderPlacement placement,
      OrderLifecycle lifecycle,
      OrderRepository orderRepo,
      StatusHistoryRepository historyRepo,
      CustomerRepository customerRepo,
      ObjectMapper objectMapper) {
    this.placement = placement;
    this.lifecycle = lifecycle;
    this.orderRepo = orderRepo;
    this.historyRepo = historyRepo;
    this.customerRepo = customerRepo;
    this.objectMapper = objectMapper;
  }

  // ================================================================
  // Đặt đơn
  // ================================================================

  /**
   * <b>READ COMMITTED, cố ý không phải REPEATABLE READ.</b>
   *
   * <p>Mọi chỗ tranh chấp của placeOrder là câu UPDATE có điều kiện: giữ hàng ({@code reserved +
   * qty WHERE on_hand - reserved >= qty}, F03) và tăng lượt coupon ({@code WHERE used_count <
   * max_uses}, F04). Ở READ COMMITTED, UPDATE chờ khóa xong thì Postgres đánh giá lại WHERE trên
   * row mới nhất — đó chính là thứ làm pattern này đúng: còn hàng thì ghi, hết thì 0 row → {@code
   * INV_INSUFFICIENT_STOCK}.
   *
   * <p>Ở REPEATABLE READ, row đã bị transaction khác sửa + commit sau khi snapshot được chụp thì
   * UPDATE ném {@code 40001 could not serialize access} thay vì đánh giá lại — kể cả khi kho còn
   * thừa hàng. Hai đơn cùng một product chạy song song là một đơn fail. Thứ REPEATABLE READ đổi lại
   * ("snapshot ổn định") không dùng tới: giá product chỉ đọc một lần rồi giữ trong bộ nhớ.
   *
   * <p>Ghi tường minh thay vì để mặc định để lựa chọn nằm ngay trên code, và không đổi theo {@code
   * default_transaction_isolation} của DB.
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = PLACE_ORDER_TIMEOUT_SECONDS)
  public Order placeOrder(PlaceOrderRequest req) {
    return placement.place(req);
  }

  // ================================================================
  // Chuyển trạng thái
  // ================================================================

  @Override
  @Transactional
  public void cancelOrder(UUID orderId, String reason, String actor) {
    lifecycle.cancel(orderId, reason, actor);
  }

  @Override
  @Transactional
  public Order transitionStatus(UUID orderId, OrderStatus to, String reason, String actor) {
    return lifecycle.transition(orderId, to, reason, actor);
  }

  @Override
  @Transactional
  public Order markPaid(UUID orderId) {
    return lifecycle.transition(
        orderId, OrderStatus.PAID, "Payment confirmed", OrderLifecycle.SYSTEM_ACTOR);
  }

  @Override
  @Transactional
  public Order markShipped(UUID orderId) {
    return lifecycle.transition(orderId, OrderStatus.SHIPPED, "Shipped", OPS_ACTOR);
  }

  @Override
  @Transactional
  public Order markDelivered(UUID orderId) {
    return lifecycle.transition(orderId, OrderStatus.DELIVERED, "Delivered", OPS_ACTOR);
  }

  // ================================================================
  // Job expire
  // ================================================================

  /**
   * Mỗi đơn một transaction riêng ({@code OrderLifecycle.expireIfOverdue}), nên method này phải
   * chạy NGOÀI transaction — {@code NEVER}: gọi từ trong một transaction là nổ ngay, thay vì âm
   * thầm gộp cả batch vào transaction của caller.
   *
   * <p>Vì sao không làm cả batch trong một transaction: một đơn lỗi (vd tồn kho bị chỉnh tay,
   * release không đủ reserved) kéo rollback mọi đơn khác trong batch; lần chạy sau đơn đó vẫn cũ
   * nhất, vẫn đứng đầu danh sách, vẫn lỗi — job kẹt vĩnh viễn trên một đơn. Bắt exception rồi chạy
   * tiếp trong cùng transaction cũng không cứu được: transaction đã bị đánh dấu rollback-only, tới
   * commit sẽ ném {@code UnexpectedRollbackException}.
   */
  @Override
  @Transactional(propagation = Propagation.NEVER)
  public int expirePendingOrders() {
    List<UUID> overdue = lifecycle.findOverdueOrderIds();
    int expired = 0;
    for (UUID orderId : overdue) {
      if (expireOne(orderId)) {
        expired++;
      }
    }
    if (!overdue.isEmpty()) {
      log.info("Expire job: {}/{} đơn quá hạn đã chuyển EXPIRED", expired, overdue.size());
    }
    return expired;
  }

  /**
   * Bắt {@link RuntimeException} có chủ đích — ngoại lệ duy nhất của quy ước "không bắt exception":
   * job không có request nào để {@code GlobalExceptionHandler} trả lỗi về. Transaction của đơn lỗi
   * đã rollback trong proxy trước khi exception tới đây; ghi log để vận hành xử lý, đơn vẫn PENDING
   * và được thử lại ở lần chạy sau.
   */
  private boolean expireOne(UUID orderId) {
    try {
      return lifecycle.expireIfOverdue(orderId);
    } catch (RuntimeException ex) {
      log.error("Expire đơn {} thất bại — bỏ qua, lần chạy sau thử lại", orderId, ex);
      return false;
    }
  }

  // ================================================================
  // Đọc
  // ================================================================

  @Override
  public Order getById(UUID id) {
    return orderRepo.findById(id).orElseThrow(() -> OrderLifecycle.orderNotFound(id));
  }

  @Override
  public Order getByOrderNumber(String orderNumber) {
    return orderRepo
        .findByOrderNumber(orderNumber)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.ORD_NOT_FOUND,
                    "Order number '%s' không tồn tại.".formatted(orderNumber)));
  }

  /** Customer không tồn tại → 404 (cùng quy ước {@code CustomerService.listAddresses}). */
  @Override
  public Page<Order> listCustomerOrders(UUID customerId, Pageable pageable) {
    if (!customerRepo.existsById(customerId)) {
      throw new NotFoundException(
          ErrorCode.CUS_NOT_FOUND, "Customer '%s' không tồn tại.".formatted(customerId));
    }
    return orderRepo.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
  }

  @Override
  public List<OrderStatusHistory> getStatusHistory(UUID orderId) {
    if (!orderRepo.existsById(orderId)) {
      throw OrderLifecycle.orderNotFound(orderId);
    }
    return historyRepo.findByOrderIdOrderByChangedAtAsc(orderId);
  }

  /**
   * 1 query ra toàn bộ đơn; items và history về dạng JSON array, parse thẳng thành list record.
   *
   * <p>Không bắt exception quanh {@code readValue}: JSON do chính Postgres dựng ({@code
   * json_build_object}) nên không sai cú pháp được; nếu có, đó là bug — để rơi xuống handler 500.
   * Jackson 3 ném unchecked nên không cần {@code try/catch} bọc lại.
   */
  @Override
  public OrderDetailResponse getDetail(UUID id) {
    OrderDetailRow row =
        orderRepo.findDetailById(id).orElseThrow(() -> OrderLifecycle.orderNotFound(id));
    return new OrderDetailResponse(
        row.getId(),
        row.getOrderNumber(),
        row.getStatus(),
        row.getSubtotal(),
        row.getDiscountAmount(),
        row.getShippingFee(),
        row.getTotal(),
        row.getShippingRecipient(),
        row.getShippingPhone(),
        row.getShippingLine1(),
        row.getShippingLine2(),
        row.getShippingCity(),
        row.getShippingCountry(),
        row.getCreatedAt(),
        row.getCustomerId(),
        row.getCustomerEmail(),
        row.getCustomerName(),
        row.getCouponId(),
        row.getCouponCode(),
        objectMapper.readValue(row.getItemsJson(), ITEM_LIST),
        objectMapper.readValue(row.getHistoryJson(), HISTORY_LIST));
  }
}
