package com.tungph7.learnpostgres.feature.customer.domain;

/**
 * Trạng thái customer — mapping cột status VARCHAR(16) trong DB.
 *
 * <p>DB enforce CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')). Mọi giá trị thêm mới phải:
 *
 * <ol>
 *   <li>Thêm value vào enum này.
 *   <li>ALTER CONSTRAINT ck_customers_status ở migration mới.
 * </ol>
 */
public enum CustomerStatus {
  ACTIVE,
  SUSPENDED,
  DELETED
}
