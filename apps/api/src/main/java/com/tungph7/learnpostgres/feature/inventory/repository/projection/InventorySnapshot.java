package com.tungph7.learnpostgres.feature.inventory.repository.projection;

/**
 * Snapshot on_hand / reserved trả về từ mệnh đề {@code RETURNING} của các atomic UPDATE.
 *
 * <p>Dùng để ghi movement với on_hand_after / reserved_after chính xác mà không cần SELECT lại.
 */
public interface InventorySnapshot {

  int getOnHand();

  int getReserved();
}
