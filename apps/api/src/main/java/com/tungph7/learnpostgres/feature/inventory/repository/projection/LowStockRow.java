package com.tungph7.learnpostgres.feature.inventory.repository.projection;

import java.util.UUID;

/** Một dòng báo cáo low-stock — join product_inventory với products. */
public interface LowStockRow {

  UUID getProductId();

  String getProductName();

  String getSku();

  int getOnHand();

  int getReserved();

  int getAvailable();

  Integer getReorderPoint();
}
