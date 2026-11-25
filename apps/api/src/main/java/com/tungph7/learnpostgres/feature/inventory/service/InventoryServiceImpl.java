package com.tungph7.learnpostgres.feature.inventory.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.inventory.domain.InventoryMovement;
import com.tungph7.learnpostgres.feature.inventory.domain.ProductInventory;
import com.tungph7.learnpostgres.feature.inventory.dto.AdjustRequest;
import com.tungph7.learnpostgres.feature.inventory.dto.LowStockResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.ReconcileResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.StockInRequest;
import com.tungph7.learnpostgres.feature.inventory.repository.InventoryRepository;
import com.tungph7.learnpostgres.feature.inventory.repository.MovementRepository;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.InventorySnapshot;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.LowStockRow;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.ReconcileRow;

/**
 * Mặc định readOnly; từng method ghi mới override bằng {@code @Transactional}.
 *
 * <p>Mọi method ghi đều theo đúng 3 nhịp: atomic UPDATE → nếu 0 row thì ném lỗi → ghi movement từ
 * snapshot mà RETURNING trả về. Nhờ snapshot, {@code on_hand_after} / {@code reserved_after} luôn
 * khớp chính xác row sau khi ghi, không cần SELECT lại và không có khoảng hở cho request khác chen
 * vào giữa.
 */
@Service
@Transactional(readOnly = true)
public class InventoryServiceImpl implements InventoryService {

  private final InventoryRepository inventoryRepo;
  private final MovementRepository movementRepo;
  private final EntityManager entityManager;

  public InventoryServiceImpl(
      InventoryRepository inventoryRepo,
      MovementRepository movementRepo,
      EntityManager entityManager) {
    this.inventoryRepo = inventoryRepo;
    this.movementRepo = movementRepo;
    this.entityManager = entityManager;
  }

  // ================================================================
  // Read
  // ================================================================

  @Override
  public ProductInventory getInventory(UUID productId) {
    return inventoryRepo.findById(productId).orElseThrow(() -> notFound(productId));
  }

  @Override
  public List<LowStockResponse> getLowStockProducts(int defaultThreshold, int limit) {
    return inventoryRepo.findLowStock(defaultThreshold, limit).stream()
        .map(InventoryServiceImpl::toLowStockResponse)
        .toList();
  }

  // ================================================================
  // Init
  // ================================================================

  /**
   * Không cần {@link #reloadAfterWrite} ở đây: {@code initIfAbsent} chỉ có thể TẠO row mới (ON
   * CONFLICT DO NOTHING), không bao giờ đổi giá trị của row đang có. Mà row chưa tồn tại thì cũng
   * không thể nằm sẵn trong persistence context → {@code findById} chắc chắn đọc DB.
   */
  @Override
  @Transactional
  public ProductInventory initInventory(UUID productId) {
    inventoryRepo.initIfAbsent(productId);
    return getInventory(productId);
  }

  // ================================================================
  // Admin operations
  // ================================================================

  @Override
  @Transactional
  public ProductInventory stockIn(UUID productId, StockInRequest req) {
    InventorySnapshot snap =
        applyOrThrow(inventoryRepo.stockIn(productId, req.quantity()), productId);

    movementRepo.save(
        InventoryMovement.stockIn(
            productId,
            req.quantity(),
            snap.getOnHand(),
            snap.getReserved(),
            req.reason(),
            req.actor()));

    return reloadAfterWrite(productId);
  }

  @Override
  @Transactional
  public ProductInventory adjust(UUID productId, AdjustRequest req) {
    InventorySnapshot snap =
        applyOrThrow(
            inventoryRepo.adjust(productId, req.deltaOnHand(), req.deltaReserved()), productId);

    movementRepo.save(
        InventoryMovement.adjustment(
            productId,
            req.deltaOnHand(),
            req.deltaReserved(),
            snap.getOnHand(),
            snap.getReserved(),
            req.reason(),
            req.actor()));

    return reloadAfterWrite(productId);
  }

  // ================================================================
  // Internal — gọi từ F05
  // ================================================================

  @Override
  @Transactional
  public ProductInventory reserve(UUID productId, int qty, UUID orderId, String actor) {
    requirePositive(qty);
    List<InventorySnapshot> result = inventoryRepo.reserve(productId, qty);
    if (result.isEmpty()) {
      // 0 row có đúng 2 nguyên nhân, phải phân biệt để trả status khác nhau:
      //   1. Row không tồn tại           -> 404
      //   2. WHERE available >= qty fail -> 409
      requireExists(productId);
      throw new ConflictException(
          ErrorCode.INV_INSUFFICIENT_STOCK,
          "Không đủ stock cho product %s, yêu cầu %d.".formatted(productId, qty));
    }

    InventorySnapshot snap = result.get(0);
    movementRepo.save(
        InventoryMovement.reserve(
            productId,
            qty,
            snap.getOnHand(),
            snap.getReserved(),
            "Reserved for order",
            orderId,
            actor));

    return reloadAfterWrite(productId);
  }

  @Override
  @Transactional
  public ProductInventory release(UUID productId, int qty, UUID orderId, String actor) {
    requirePositive(qty);
    List<InventorySnapshot> result = inventoryRepo.release(productId, qty);
    if (result.isEmpty()) {
      requireExists(productId);
      throw new ConflictException(
          ErrorCode.INV_RELEASE_INVALID,
          "Release %d vượt reserved hiện tại của product %s.".formatted(qty, productId));
    }

    InventorySnapshot snap = result.get(0);
    movementRepo.save(
        InventoryMovement.release(
            productId,
            qty,
            snap.getOnHand(),
            snap.getReserved(),
            "Released from order",
            orderId,
            actor));

    return reloadAfterWrite(productId);
  }

  @Override
  @Transactional
  public ProductInventory commit(UUID productId, int qty, UUID orderId, String actor) {
    requirePositive(qty);
    List<InventorySnapshot> result = inventoryRepo.commit(productId, qty);
    if (result.isEmpty()) {
      requireExists(productId);
      throw new ConflictException(
          ErrorCode.INV_INSUFFICIENT_STOCK,
          "Commit %d không hợp lệ cho product %s (on_hand hoặc reserved < qty)."
              .formatted(qty, productId));
    }

    InventorySnapshot snap = result.get(0);
    movementRepo.save(
        InventoryMovement.commit(
            productId,
            qty,
            snap.getOnHand(),
            snap.getReserved(),
            "Committed (delivered)",
            orderId,
            actor));

    return reloadAfterWrite(productId);
  }

  @Override
  @Transactional
  public void bulkReserve(List<ReserveItem> items, UUID orderId, String actor) {
    items.stream()
        .sorted(Comparator.comparing(ReserveItem::productId))
        .forEach(item -> reserve(item.productId(), item.quantity(), orderId, actor));
  }

  // ================================================================
  // Audit
  // ================================================================

  @Override
  public Page<InventoryMovement> getMovementHistory(UUID productId, Pageable pageable) {
    requireExists(productId);
    return movementRepo.findByProductIdOrderByOccurredAtDesc(productId, pageable);
  }

  @Override
  public ReconcileResponse reconcile(UUID productId) {
    // Query đã lọc theo product_id nên empty = chưa init inventory; không cần existsById riêng.
    ReconcileRow row = movementRepo.reconcile(productId).orElseThrow(() -> notFound(productId));

    boolean inSync =
        row.getOnHandDrift() == 0 && row.getReservedDrift() == 0 && row.getSoldDrift() == 0;

    return new ReconcileResponse(
        row.getProductId(),
        row.getOnHand(),
        row.getOnHandCalc(),
        row.getOnHandDrift(),
        row.getReserved(),
        row.getReservedCalc(),
        row.getReservedDrift(),
        row.getSold(),
        row.getSoldCalc(),
        row.getSoldDrift(),
        inSync);
  }

  // ================================================================
  // Helpers
  // ================================================================

  /**
   * Dùng cho các UPDATE không mang điều kiện nghiệp vụ (stockIn, adjust): WHERE chỉ có {@code
   * product_id}, nên 0 row chỉ có thể là do row chưa tồn tại.
   *
   * <p>Vi phạm CHECK constraint KHÔNG rơi vào nhánh này — Postgres ném {@code
   * DataIntegrityViolationException} và GlobalExceptionHandler map nó theo tên constraint.
   */
  private InventorySnapshot applyOrThrow(List<InventorySnapshot> result, UUID productId) {
    if (result.isEmpty()) {
      throw notFound(productId);
    }
    return result.get(0);
  }

  /**
   * Đọc lại inventory SAU khi đã ghi bằng atomic UPDATE — bắt buộc, không phải tuỳ chọn.
   *
   * <p>Các UPDATE ở repository là native query: chúng ghi thẳng xuống DB mà Hibernate không hề
   * biết, nên bản copy của entity đang nằm trong persistence context KHÔNG được cập nhật. Nếu
   * transaction này từng {@code findById} cùng product trước đó (rất dễ xảy ra: chính {@code
   * initInventory} làm vậy, và F05 cũng sẽ đọc tồn kho trước khi đặt giữ hàng), thì {@code
   * findById} lần sau trả về đúng bản cũ đó — con số cũ, không phải con số vừa ghi.
   *
   * <p>{@code @Modifying(clearAutomatically = true)} KHÔNG cứu được chỗ này: method trả {@code
   * List} đi đường {@code CollectionExecution}, không phải {@code ModifyingExecution} — nơi duy
   * nhất đọc hai cờ đó (xem 02 §5.2). Vì vậy phải refresh tường minh.
   *
   * <p>Cố tình KHÔNG dùng {@code entityManager.clear()}: nó detach mọi thứ, kể cả entity mà caller
   * đang giữ (F05 đang dựng dở Order trong cùng transaction). Refresh đúng một row an toàn hơn
   * nhiều, đổi lại tốn thêm một SELECT.
   */
  private ProductInventory reloadAfterWrite(UUID productId) {
    ProductInventory inventory = getInventory(productId);
    entityManager.refresh(inventory);
    return inventory;
  }

  private void requirePositive(int qty) {
    if (qty <= 0) {
      throw new BadRequestException(
          ErrorCode.INV_NEGATIVE_QUANTITY, "Quantity phải > 0, nhận được %d.".formatted(qty));
    }
  }

  private void requireExists(UUID productId) {
    if (!inventoryRepo.existsById(productId)) {
      throw notFound(productId);
    }
  }

  private static NotFoundException notFound(UUID productId) {
    return new NotFoundException(
        ErrorCode.INV_NOT_FOUND, "Không tìm thấy inventory cho product %s.".formatted(productId));
  }

  private static LowStockResponse toLowStockResponse(LowStockRow row) {
    return new LowStockResponse(
        row.getProductId(),
        row.getProductName(),
        row.getSku(),
        row.getOnHand(),
        row.getReserved(),
        row.getAvailable(),
        row.getReorderPoint());
  }
}
