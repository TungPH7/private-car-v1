package com.tungph7.learnpostgres.feature.inventory.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.feature.inventory.dto.AdjustRequest;
import com.tungph7.learnpostgres.feature.inventory.dto.InventoryResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.LowStockResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.MovementResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.ReconcileResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.StockInRequest;
import com.tungph7.learnpostgres.feature.inventory.mapper.InventoryMapper;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin endpoint cho tồn kho.
 *
 * <p>CHỈ expose 7 thao tác admin. {@code reserve} / {@code release} / {@code commit} / {@code
 * bulkReserve} cố ý KHÔNG có endpoint — xem §2.1 của {@code 04-dto-mapper-controller}.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Inventory management — admin endpoints (F03)")
public class InventoryController {

  private final InventoryService service;
  private final InventoryMapper mapper;

  public InventoryController(InventoryService service, InventoryMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  // -----------------------------------------------------------------
  // Read
  // -----------------------------------------------------------------

  @GetMapping("/{productId}")
  @Operation(summary = "Get inventory snapshot for product")
  public InventoryResponse get(@PathVariable UUID productId) {
    return mapper.toResponse(service.getInventory(productId));
  }

  @GetMapping("/low-stock")
  @Operation(summary = "List products with available < threshold or reorder_point")
  public List<LowStockResponse> lowStock(
      @RequestParam(defaultValue = "10") @Min(1) int threshold,
      @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
    return service.getLowStockProducts(threshold, limit);
  }

  @GetMapping("/{productId}/movements")
  @Operation(summary = "Movement history (paginated, DESC by occurred_at)")
  public List<MovementResponse> movements(
      @PathVariable UUID productId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
    return service.getMovementHistory(productId, pageable).stream()
        .map(mapper::toMovementResponse)
        .toList();
  }

  @GetMapping("/{productId}/reconcile")
  @Operation(summary = "Reconcile inventory vs sum of movements (admin debug)")
  public ReconcileResponse reconcile(@PathVariable UUID productId) {
    return service.reconcile(productId);
  }

  // -----------------------------------------------------------------
  // Mutate (admin)
  // -----------------------------------------------------------------

  @PostMapping("/{productId}/init")
  @ResponseStatus(HttpStatus.OK)
  @Operation(summary = "Initialize inventory row (idempotent)")
  public InventoryResponse init(@PathVariable UUID productId) {
    return mapper.toResponse(service.initInventory(productId));
  }

  @PostMapping("/{productId}/stock-in")
  @Operation(summary = "Stock in (admin)")
  public InventoryResponse stockIn(
      @PathVariable UUID productId, @Valid @RequestBody StockInRequest req) {
    return mapper.toResponse(service.stockIn(productId, req));
  }

  @PostMapping("/{productId}/adjust")
  @Operation(summary = "Adjust inventory (admin — rare, audit fix)")
  public InventoryResponse adjust(
      @PathVariable UUID productId, @Valid @RequestBody AdjustRequest req) {
    return mapper.toResponse(service.adjust(productId, req));
  }
}
