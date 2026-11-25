package com.tungph7.learnpostgres.feature.order.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Product tại thời điểm đặt đơn — 1 row của {@code OrderItemRepository.findProductSnapshots}.
 *
 * <p>{@code name} / {@code sku} / {@code price} được chép vào {@code order_items} (snapshot);
 * {@code status} để chặn product không còn bán. {@code sku} là {@code null} với product chưa có sku
 * (cột nullable từ V5).
 */
public interface ProductSnapshotRow {

  UUID getId();

  String getName();

  String getSku();

  BigDecimal getPrice();

  /** {@code DRAFT} | {@code ACTIVE} | {@code ARCHIVED} — {@code ck_products_status} (V5). */
  String getStatus();
}
