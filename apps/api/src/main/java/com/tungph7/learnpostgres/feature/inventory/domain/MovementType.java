package com.tungph7.learnpostgres.feature.inventory.domain;

/**
 * Loại movement — mapping cột movement_type VARCHAR(24) trong DB CHECK.
 *
 * <p>Workflow điển hình:
 *
 * <pre>
 *   STOCK_IN   → on_hand +=
 *   RESERVE    → reserved += (place order)
 *   RELEASE    → reserved -= (cancel pending)
 *   COMMIT     → on_hand -=, reserved -=, sold +=  (delivered)
 *   STOCK_OUT  → on_hand -= (manual loss/return)
 *   ADJUSTMENT → adhoc (count discrepancy)
 * </pre>
 */
public enum MovementType {
  STOCK_IN,
  STOCK_OUT,
  RESERVE,
  RELEASE,
  COMMIT,
  ADJUSTMENT
}
