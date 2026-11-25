package com.tungph7.learnpostgres.feature.inventory.repository.projection;

import java.util.UUID;

/**
 * Kết quả đối soát: state hiện tại của product_inventory so với tổng delta trong movement log.
 *
 * <p>Mọi field {@code *Drift} phải bằng 0. Khác 0 → có bug hoặc data bị sửa ngoài service.
 */
public interface ReconcileRow {

  UUID getProductId();

  int getOnHand();

  long getOnHandCalc();

  long getOnHandDrift();

  int getReserved();

  long getReservedCalc();

  long getReservedDrift();

  long getSold();

  long getSoldCalc();

  long getSoldDrift();
}
