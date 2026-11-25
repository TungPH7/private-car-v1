package com.tungph7.learnpostgres.feature.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.inventory.domain.InventoryMovement;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.ReconcileRow;

/**
 * Append-only log repository.
 *
 * <p>CHỈ có insert (kế thừa {@code save}) và read. Không khai báo bất kỳ method update/delete nào —
 * append-only được giữ ở cả tầng entity (không setter) lẫn tầng repository (không API).
 */
@Repository
public interface MovementRepository extends JpaRepository<InventoryMovement, UUID> {

  Page<InventoryMovement> findByProductIdOrderByOccurredAtDesc(UUID productId, Pageable pageable);

  List<InventoryMovement> findByOrderIdOrderByOccurredAtAsc(UUID orderId);

  /**
   * Reconcile: tính lại on_hand/reserved/sold từ tổng delta của movements, so với row hiện tại.
   *
   * <p>Nếu drift != 0 → có bug hoặc data bị sửa ngoài service.
   *
   * <p>{@code LEFT JOIN recon ON TRUE} để product chưa có movement nào vẫn trả 1 row (calc = 0)
   * thay vì mất hút — khi đó drift chính là toàn bộ state hiện tại.
   *
   * @return empty nếu product chưa init inventory
   */
  @Query(
      value =
          """
          WITH recon AS (
              SELECT
                  SUM(delta_on_hand)  AS on_hand_calc,
                  SUM(delta_reserved) AS reserved_calc,
                  SUM(delta_sold)     AS sold_calc
              FROM app.inventory_movements
              WHERE product_id = :productId
          )
          SELECT
              pi.product_id                                    AS productId,
              pi.on_hand                                       AS onHand,
              COALESCE(recon.on_hand_calc, 0)                  AS onHandCalc,
              (pi.on_hand  - COALESCE(recon.on_hand_calc, 0))  AS onHandDrift,
              pi.reserved                                      AS reserved,
              COALESCE(recon.reserved_calc, 0)                 AS reservedCalc,
              (pi.reserved - COALESCE(recon.reserved_calc, 0)) AS reservedDrift,
              pi.sold                                          AS sold,
              COALESCE(recon.sold_calc, 0)                     AS soldCalc,
              (pi.sold     - COALESCE(recon.sold_calc, 0))     AS soldDrift
          FROM app.product_inventory pi
          LEFT JOIN recon ON TRUE
          WHERE pi.product_id = :productId
          """,
      nativeQuery = true)
  Optional<ReconcileRow> reconcile(@Param("productId") UUID productId);
}
