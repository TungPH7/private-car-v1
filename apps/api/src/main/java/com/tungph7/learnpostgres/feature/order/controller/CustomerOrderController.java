package com.tungph7.learnpostgres.feature.order.controller;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.common.dto.PagedResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderResponse;
import com.tungph7.learnpostgres.feature.order.mapper.OrderMapper;
import com.tungph7.learnpostgres.feature.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Đơn của một customer — {@code GET /api/v1/customers/{customerId}/orders} (spec F05 §5).
 *
 * <p>Tách khỏi {@link OrderController} vì class-level {@code @RequestMapping} của lớp đó là {@code
 * /api/v1/orders}, mà Spring ghép đường dẫn của method VÀO SAU prefix đó — không có cách khai một
 * method "thoát" ra {@code /customers}. Đặt vào {@code CustomerController} thì feature customer
 * phải phụ thuộc {@code OrderService} (lý do F04 cũng không làm vậy). Lớp này ở feature order: URL
 * theo quan hệ tài nguyên, code theo feature sở hữu dữ liệu.
 *
 * <p>Phân trang bằng 2 tham số tường minh chứ không nhận {@code Pageable}: không có {@code sort} từ
 * client (repository cố định {@code created_at DESC}, khớp index {@code
 * idx_orders_customer_created_at}), và {@code page} / {@code size} sai là 400 chứ không bị lặng lẽ
 * chỉnh về giá trị hợp lệ.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/orders")
@Tag(name = "Orders", description = "Order placement & lifecycle (F05)")
public class CustomerOrderController {

  private final OrderService service;
  private final OrderMapper mapper;

  public CustomerOrderController(OrderService service, OrderMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @GetMapping
  @Operation(summary = "Đơn của một customer", description = "Mới nhất trước. page tính từ 0.")
  @ApiResponse(responseCode = "200", description = "Một trang đơn")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED (page < 0, size ngoài 1–100), INVALID_PARAMETER")
  @ApiResponse(responseCode = "404", description = "CUS_NOT_FOUND — không phải trang rỗng")
  public PagedResponse<OrderResponse> listCustomerOrders(
      @PathVariable UUID customerId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return PagedResponse.from(
        service.listCustomerOrders(customerId, PageRequest.of(page, size)).map(mapper::toResponse));
  }
}
