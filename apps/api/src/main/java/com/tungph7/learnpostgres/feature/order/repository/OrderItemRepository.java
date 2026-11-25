package com.tungph7.learnpostgres.feature.order.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.order.domain.OrderItem;
import com.tungph7.learnpostgres.feature.order.repository.projection.ProductSnapshotRow;

/** Repository của {@code app.order_items}. Item bất biến — chỉ insert và read. */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

  /**
   * Item của 1 đơn theo thứ tự thêm vào. Chạy trên btree của {@code uq_order_items_order_product}
   * (V13) — {@code order_id} là cột đầu.
   */
  List<OrderItem> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

  /**
   * Dữ liệu để chụp snapshot cho các dòng hàng: {@code name, sku, price, status} của nhiều product
   * trong 1 query (bài 03 — {@code placeOrder}).
   *
   * <p>Đọc thẳng {@code app.products} bằng native query thay vì qua entity {@code Product}: entity
   * đó (F01) chưa map {@code sku} và {@code status} — hai cột V5 thêm sau. Product không tồn tại
   * thì đơn giản là không có row; caller tự đối chiếu với danh sách id đã hỏi.
   *
   * <p>Không khóa row product: giá đọc một lần rồi chép vào đơn, product đổi giá sau đó không ảnh
   * hưởng đơn này — đó chính là mục đích của snapshot.
   */
  @Query(
      value =
          """
          SELECT p.id, p.name, p.sku, p.price, p.status
          FROM app.products p
          WHERE p.id IN (:ids)
          """,
      nativeQuery = true)
  List<ProductSnapshotRow> findProductSnapshots(@Param("ids") Collection<UUID> ids);
}
