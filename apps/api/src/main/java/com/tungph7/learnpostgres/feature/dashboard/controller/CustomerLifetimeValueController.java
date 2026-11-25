package com.tungph7.learnpostgres.feature.dashboard.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.dashboard.dto.CustomerLifetimeValueResponse;
import com.tungph7.learnpostgres.feature.dashboard.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Giá trị trọn đời của một khách — {@code GET /api/v1/customers/{customerId}/ltv} (function list
 * F07 §4), báo cáo thứ 14 của dashboard.
 *
 * <p>Tách khỏi {@link DashboardController} cùng lý do {@code CustomerOrderController} (F05) tách
 * khỏi {@code OrderController}: Spring ghép đường dẫn của method vào sau {@code @RequestMapping}
 * của class, không có cách khai một method "thoát" khỏi {@code /api/v1/dashboard}. Đặt vào {@code
 * CustomerController} thì feature customer phải phụ thuộc {@link DashboardService}. URL theo quan
 * hệ tài nguyên, code theo feature sở hữu báo cáo.
 *
 * <p>Cùng tập đơn với {@code totalSpent} / {@code ordersCount} / {@code lastOrderAt} của {@code GET
 * /api/v1/customers/{id}/profile} (F02): đơn PAID / SHIPPED / DELIVERED — hai endpoint ra cùng số.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/ltv")
@Tag(name = "Dashboard", description = "Dashboard & reports — read-only (F07)")
public class CustomerLifetimeValueController {

  private final DashboardService service;

  public CustomerLifetimeValueController(DashboardService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(
      summary = "Giá trị trọn đời của khách (LTV)",
      description =
          "Mọi đơn được tính doanh thu (PAID / SHIPPED / DELIVERED) của khách, từ trước tới nay;"
              + " chưa trừ hoàn tiền. Khách chưa có đơn nào được tính: totalOrders 0, totalSpent"
              + " 0.00, các field còn lại null.")
  @ApiResponse(responseCode = "200", description = "LTV của khách")
  @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (customerId không phải UUID)")
  @ApiResponse(responseCode = "404", description = "CUS_NOT_FOUND")
  public CustomerLifetimeValueResponse customerLifetimeValue(@PathVariable UUID customerId) {
    return service.customerLifetimeValue(customerId);
  }
}
