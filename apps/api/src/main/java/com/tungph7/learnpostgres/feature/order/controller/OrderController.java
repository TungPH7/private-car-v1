package com.tungph7.learnpostgres.feature.order.controller;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.feature.order.dto.CancelOrderRequest;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderResponse;
import com.tungph7.learnpostgres.feature.order.dto.PlaceOrderRequest;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.order.dto.TransitionStatusRequest;
import com.tungph7.learnpostgres.feature.order.mapper.OrderMapper;
import com.tungph7.learnpostgres.feature.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API của đơn hàng — 7 endpoint dưới {@code /api/v1/orders}. Endpoint thứ 8 (đơn của một
 * customer) ở {@link CustomerOrderController}, vì URL của nó bắt đầu bằng {@code /customers}.
 *
 * <p>Controller mỏng: Bean Validation chặn hình dạng request, {@link OrderService} lo nghiệp vụ và
 * transaction, {@link OrderMapper} đổi entity → DTO.
 *
 * <p><b>{@code markPaid} / {@code markShipped} / {@code markDelivered} không có endpoint riêng.</b>
 * Admin đi qua {@code POST /{id}/transitions}; {@code markPaid} để F06 gọi thẳng service khi xác
 * nhận thanh toán. Job expire cũng không có HTTP.
 *
 * <p><b>Actor</b> của lịch sử trạng thái lấy từ principal của request ({@link #actorOf}), không
 * nhận từ body hay header: client tự khai mình là ai thì lịch sử mất giá trị kiểm toán.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order placement & lifecycle (F05)")
public class OrderController {

  /**
   * Actor khi request chưa xác thực. Repo chưa có đăng nhập ({@code SecurityConfig} permitAll
   * {@code /api/**}), nên hiện MỌI request đều ra giá trị này.
   */
  static final String ANONYMOUS_ACTOR = "anonymous";

  private final OrderService service;
  private final OrderMapper mapper;

  public OrderController(OrderService service, OrderMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  // -----------------------------------------------------------------
  // Đặt đơn
  // -----------------------------------------------------------------

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Đặt đơn",
      description =
          "Một transaction READ COMMITTED: kiểm customer / address / product / coupon, giữ hàng"
              + " (F03), redeem coupon (F04). Bước nào lỗi thì rollback toàn bộ — không để lại"
              + " đơn, không giữ hàng, không trừ lượt coupon.")
  @ApiResponse(responseCode = "201", description = "Đơn PENDING vừa tạo; Location trỏ tới đơn")
  @ApiResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED, MALFORMED_REQUEST, ORD_ADDRESS_INVALID, ORD_PRODUCT_INACTIVE,"
              + " CPN_EXPIRED, CPN_MIN_ORDER_NOT_MET")
  @ApiResponse(responseCode = "403", description = "ORD_CUSTOMER_INACTIVE, ORD_ADDRESS_FORBIDDEN")
  @ApiResponse(
      responseCode = "404",
      description =
          "CUS_NOT_FOUND, PRODUCT_NOT_FOUND, CPN_NOT_FOUND, INV_NOT_FOUND (product chưa khởi tạo"
              + " tồn kho)")
  @ApiResponse(
      responseCode = "409",
      description =
          "INV_INSUFFICIENT_STOCK, CPN_EXHAUSTED, CPN_PER_CUSTOMER_LIMIT,"
              + " CONCURRENT_UPDATE_CONFLICT")
  public ResponseEntity<OrderResponse> placeOrder(
      @Valid @RequestBody PlaceOrderRequest req, UriComponentsBuilder uriBuilder) {
    var saved = service.placeOrder(req);
    URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location).body(mapper.toResponse(saved));
  }

  // -----------------------------------------------------------------
  // Đọc
  // -----------------------------------------------------------------

  @GetMapping("/{id}")
  @Operation(
      summary = "Tóm tắt một đơn",
      description =
          "Cùng hình dạng với response của đặt đơn / chuyển trạng thái. Items, shipping snapshot"
              + " và lịch sử ở /{id}/detail.")
  @ApiResponse(responseCode = "200", description = "Tóm tắt đơn")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  public OrderResponse getById(@PathVariable UUID id) {
    return mapper.toResponse(service.getById(id));
  }

  @GetMapping("/{id}/detail")
  @Operation(
      summary = "Toàn bộ một đơn",
      description =
          "Header, shipping snapshot, customer, coupon, items và lịch sử trạng thái — 1 query.")
  @ApiResponse(responseCode = "200", description = "Toàn bộ đơn")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  public OrderDetailResponse getDetail(@PathVariable UUID id) {
    return service.getDetail(id);
  }

  @GetMapping("/by-number/{orderNumber}")
  @Operation(summary = "Tìm đơn theo số đơn", description = "Số đơn dạng ORD-YYYYMM-NNNNNN.")
  @ApiResponse(responseCode = "200", description = "Tóm tắt đơn")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  public OrderResponse getByOrderNumber(@PathVariable String orderNumber) {
    return mapper.toResponse(service.getByOrderNumber(orderNumber));
  }

  @GetMapping("/{id}/history")
  @Operation(
      summary = "Lịch sử trạng thái",
      description = "Cũ nhất trước. fromStatus = null là dòng khởi tạo lúc đặt đơn.")
  @ApiResponse(responseCode = "200", description = "Các lần đổi trạng thái, cũ nhất trước")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND — không phải danh sách rỗng")
  public List<StatusHistoryResponse> getStatusHistory(@PathVariable UUID id) {
    return service.getStatusHistory(id).stream().map(mapper::toHistoryResponse).toList();
  }

  // -----------------------------------------------------------------
  // Chuyển trạng thái
  // -----------------------------------------------------------------

  /**
   * 204 như các action POST khác của repo ({@code CouponController.deactivate}, {@code
   * CustomerController.suspend}): {@code cancelOrder} không trả gì, client cần trạng thái mới thì
   * {@code GET /{id}}.
   */
  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Hủy đơn",
      description =
          "PENDING / PAID / SHIPPED → CANCELLED: nhả hàng đang giữ (trừ đơn đã SHIPPED) và hoàn"
              + " lượt coupon, trong cùng một transaction.")
  @ApiResponse(responseCode = "204", description = "Đã hủy")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "ORD_INVALID_STATUS_TRANSITION (đơn đã DELIVERED / CANCELLED / EXPIRED),"
              + " ORD_RACE_CONDITION (giao dịch khác vừa đổi trạng thái — tải lại rồi thử lại)")
  public void cancel(
      @PathVariable UUID id, @Valid @RequestBody CancelOrderRequest req, Principal principal) {
    service.cancelOrder(id, req.reason(), actorOf(principal));
  }

  @PostMapping("/{id}/transitions")
  @Operation(
      summary = "Chuyển trạng thái (admin)",
      description =
          "to = PAID / SHIPPED / DELIVERED / CANCELLED. DELIVERED chốt tồn kho (reserved → sold);"
              + " CANCELLED đi đúng luồng hủy. EXPIRED chỉ job hết hạn thanh toán đặt được.")
  @ApiResponse(responseCode = "200", description = "Đơn sau khi chuyển")
  @ApiResponse(responseCode = "404", description = "ORD_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "ORD_INVALID_STATUS_TRANSITION (sai bước, vd PENDING → DELIVERED; hoặc to = EXPIRED),"
              + " ORD_RACE_CONDITION")
  public OrderResponse transition(
      @PathVariable UUID id, @Valid @RequestBody TransitionStatusRequest req, Principal principal) {
    return mapper.toResponse(
        service.transitionStatus(id, req.to(), req.reason(), actorOf(principal)));
  }

  /**
   * Tên của người gọi đã xác thực; chưa xác thực thì {@link #ANONYMOUS_ACTOR}.
   *
   * <p>{@code Principal} của Spring MVC là {@code null} với request anonymous. Khi repo thêm JWT,
   * actor tự thành subject của token — không phải sửa gì ở đây.
   */
  private static String actorOf(Principal principal) {
    return principal == null ? ANONYMOUS_ACTOR : principal.getName();
  }
}
