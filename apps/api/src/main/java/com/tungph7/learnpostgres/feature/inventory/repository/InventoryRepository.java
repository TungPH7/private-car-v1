package com.tungph7.learnpostgres.feature.inventory.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.inventory.domain.ProductInventory;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.InventorySnapshot;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.LowStockRow;

/**
 * Atomic UPDATE pattern — phần kỹ thuật nhất của F03.
 *
 * <p>Mỗi thao tác đổi tồn kho là MỘT câu {@code UPDATE ... WHERE <điều kiện> RETURNING ...}:
 *
 * <ul>
 *   <li>Postgres tự lock row khi UPDATE chạm tới nó → KHÔNG cần {@code SELECT ... FOR UPDATE} trước
 *       đó, và cũng không có khoảng hở check-rồi-mới-ghi.
 *   <li>Điều kiện nghiệp vụ (đủ hàng hay không) nằm trong {@code WHERE}. Không thoả → 0 row đổi →
 *       list trả về rỗng → service ném lỗi. Không bao giờ ghi sai.
 *   <li>{@code RETURNING} trả state SAU khi ghi, để log movement không phải SELECT lại.
 * </ul>
 *
 * <p>LƯU Ý về {@code @Modifying}: annotation này KHÔNG quyết định câu lệnh chạy thế nào. Spring
 * Data chọn execution strategy theo KIỂU TRẢ VỀ trước: {@code AbstractJpaQuery} kiểm tra {@code
 * isCollectionQuery()} TRƯỚC nhánh modifying, nên mọi method trả {@code List} bên dưới đều đi đường
 * {@code CollectionExecution} ({@code getResultList()}) và map được result set của {@code
 * RETURNING} sang projection — dù có hay không có {@code @Modifying}.
 *
 * <p>Hệ quả cần nhớ: hai option {@code clearAutomatically} / {@code flushAutomatically} chỉ được
 * {@code ModifyingExecution} đọc, mà nhánh đó không chạy ở đây → đặt chúng trên các method trả
 * {@code List} sẽ KHÔNG có tác dụng. Muốn tránh đọc phải entity cũ trong persistence context sau
 * khi UPDATE native, phải tự {@code flush()} / {@code clear()} ở service.
 */
@Repository
public interface InventoryRepository extends JpaRepository<ProductInventory, UUID> {

  // -----------------------------------------------------------------
  // Init — idempotent
  // -----------------------------------------------------------------

  /**
   * Khởi tạo inventory row cho product nếu chưa có. Idempotent.
   *
   * <p>Pattern ON CONFLICT DO NOTHING → safe khi gọi nhiều lần, và không ném lỗi khi hai request
   * cùng init một product.
   *
   * @return 1 nếu vừa tạo, 0 nếu đã có sẵn
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO app.product_inventory (product_id, on_hand, reserved, sold)
          VALUES (:productId, 0, 0, 0)
          ON CONFLICT (product_id) DO NOTHING
          """,
      nativeQuery = true)
  int initIfAbsent(@Param("productId") UUID productId);

  // -----------------------------------------------------------------
  // Atomic UPDATE pattern — đỉnh cao của F03
  // -----------------------------------------------------------------

  /**
   * Stock-in: tăng on_hand. Atomic — không cần lock hay SELECT trước.
   *
   * <p>Không có điều kiện nghiệp vụ nào ngoài "row phải tồn tại", nên list rỗng nghĩa là product
   * chưa init inventory.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.product_inventory
          SET on_hand         = on_hand + :qty,
              last_restock_at = now()
          WHERE product_id = :productId
          RETURNING on_hand AS onHand, reserved AS reserved
          """,
      nativeQuery = true)
  List<InventorySnapshot> stockIn(@Param("productId") UUID productId, @Param("qty") int qty);

  /**
   * Reserve: tăng reserved nếu available >= qty.
   *
   * <p>KEY PATTERN của F03. Điều kiện {@code on_hand - reserved >= :qty} nằm NGAY trong WHERE, nên
   * hai request đồng thời không thể cùng thấy "đủ hàng" rồi cùng ghi: request thứ hai đánh giá
   * WHERE trên state đã bị request đầu ghi xong (Postgres re-check WHERE sau khi chờ row lock).
   *
   * <p>List rỗng → không đủ hàng → service ném INV_INSUFFICIENT_STOCK.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.product_inventory
          SET reserved = reserved + :qty
          WHERE product_id = :productId
            AND on_hand - reserved >= :qty
          RETURNING on_hand AS onHand, reserved AS reserved
          """,
      nativeQuery = true)
  List<InventorySnapshot> reserve(@Param("productId") UUID productId, @Param("qty") int qty);

  /** Release: giảm reserved. Atomic check reserved >= qty để không xuống âm. */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.product_inventory
          SET reserved = reserved - :qty
          WHERE product_id = :productId
            AND reserved >= :qty
          RETURNING on_hand AS onHand, reserved AS reserved
          """,
      nativeQuery = true)
  List<InventorySnapshot> release(@Param("productId") UUID productId, @Param("qty") int qty);

  /** Commit: chuyển reserved → sold và giảm on_hand (đơn đã giao). */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.product_inventory
          SET on_hand  = on_hand  - :qty,
              reserved = reserved - :qty,
              sold     = sold     + :qty
          WHERE product_id = :productId
            AND on_hand  >= :qty
            AND reserved >= :qty
          RETURNING on_hand AS onHand, reserved AS reserved
          """,
      nativeQuery = true)
  List<InventorySnapshot> commit(@Param("productId") UUID productId, @Param("qty") int qty);

  /**
   * Adjust: cộng delta tuỳ ý (admin/audit fix), delta có thể âm.
   *
   * <p>Trust caller — 5 CHECK constraint của V8 sẽ chặn nếu kết quả âm hoặc reserved vượt on_hand,
   * và ném DataIntegrityViolationException.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE app.product_inventory
          SET on_hand  = on_hand  + :deltaOnHand,
              reserved = reserved + :deltaReserved
          WHERE product_id = :productId
          RETURNING on_hand AS onHand, reserved AS reserved
          """,
      nativeQuery = true)
  List<InventorySnapshot> adjust(
      @Param("productId") UUID productId,
      @Param("deltaOnHand") int deltaOnHand,
      @Param("deltaReserved") int deltaReserved);

  // -----------------------------------------------------------------
  // Read
  // -----------------------------------------------------------------

  /**
   * Low-stock report — product ACTIVE có available dưới ngưỡng.
   *
   * <p>Ngưỡng lấy theo reorder_point của từng product, fallback {@code :defaultThreshold} khi
   * product không set. Điều kiện lọc dùng đúng biểu thức {@code (on_hand - reserved)} mà expression
   * index {@code idx_inventory_available_low_stock} (V8) đánh trên.
   */
  @Query(
      value =
          """
          SELECT
              pi.product_id              AS productId,
              p.name                     AS productName,
              p.sku                      AS sku,
              pi.on_hand                 AS onHand,
              pi.reserved                AS reserved,
              (pi.on_hand - pi.reserved) AS available,
              pi.reorder_point           AS reorderPoint
          FROM app.product_inventory pi
          JOIN app.products p ON p.id = pi.product_id
          WHERE (pi.on_hand - pi.reserved) < COALESCE(pi.reorder_point, :defaultThreshold)
            AND p.status = 'ACTIVE'
          ORDER BY available ASC
          LIMIT :limit
          """,
      nativeQuery = true)
  List<LowStockRow> findLowStock(
      @Param("defaultThreshold") int defaultThreshold, @Param("limit") int limit);
}
