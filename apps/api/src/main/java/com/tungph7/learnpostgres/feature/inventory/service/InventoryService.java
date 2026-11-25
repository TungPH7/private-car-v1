package com.tungph7.learnpostgres.feature.inventory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.feature.inventory.domain.InventoryMovement;
import com.tungph7.learnpostgres.feature.inventory.domain.ProductInventory;
import com.tungph7.learnpostgres.feature.inventory.dto.AdjustRequest;
import com.tungph7.learnpostgres.feature.inventory.dto.LowStockResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.ReconcileResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.StockInRequest;

/**
 * Nghiệp vụ tồn kho.
 *
 * <p>Chia 3 nhóm theo ai được gọi:
 *
 * <ul>
 *   <li><b>Admin</b> — {@code initInventory}, {@code stockIn}, {@code adjust}: expose qua REST ở
 *       {@code 04-dto-mapper-controller}.
 *   <li><b>Internal</b> — {@code reserve}, {@code release}, {@code commit}, {@code bulkReserve}:
 *       KHÔNG expose REST. Chỉ F05 (place-order) gọi, trong transaction của nó.
 *   <li><b>Read/Audit</b> — {@code getInventory}, {@code getLowStockProducts}, {@code
 *       getMovementHistory}, {@code reconcile}.
 * </ul>
 *
 * <p>Bất biến của cả service: KHÔNG bao giờ đổi tồn kho mà không ghi một dòng movement, và cả hai
 * nằm trong cùng một transaction.
 */
public interface InventoryService {

  // ---- Read ----

  ProductInventory getInventory(UUID productId);

  List<LowStockResponse> getLowStockProducts(int defaultThreshold, int limit);

  // ---- Mutate (admin) ----

  ProductInventory initInventory(UUID productId);

  ProductInventory stockIn(UUID productId, StockInRequest req);

  ProductInventory adjust(UUID productId, AdjustRequest req);

  // ---- Mutate (internal — gọi từ F05) ----

  ProductInventory reserve(UUID productId, int qty, UUID orderId, String actor);

  ProductInventory release(UUID productId, int qty, UUID orderId, String actor);

  ProductInventory commit(UUID productId, int qty, UUID orderId, String actor);

  /**
   * Bulk reserve nhiều product trong 1 transaction — có chống deadlock.
   *
   * <p>Items được sort theo productId trước khi loop, nên mọi transaction đều khoá row theo cùng
   * một thứ tự → không thể có A chờ B trong khi B chờ A.
   *
   * <p>Bất kỳ item nào fail → ném exception → rollback toàn bộ.
   */
  void bulkReserve(List<ReserveItem> items, UUID orderId, String actor);

  /** Một dòng yêu cầu giữ hàng trong {@link #bulkReserve}. */
  record ReserveItem(UUID productId, int quantity) {}

  // ---- Audit ----

  Page<InventoryMovement> getMovementHistory(UUID productId, Pageable pageable);

  ReconcileResponse reconcile(UUID productId);
}
