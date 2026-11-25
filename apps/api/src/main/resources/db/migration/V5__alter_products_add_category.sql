-- =====================================================================
-- V5__alter_products_add_category.sql
-- Mở rộng app.products cho feature F01 Catalog:
--   - Thêm category_id (FK nullable → app.categories).
--   - Thêm sku (UNIQUE, nullable cho data cũ).
--   - Thêm status (DRAFT | ACTIVE | ARCHIVED) với CHECK.
--   - Partial index trên ACTIVE (vì 99% query catalog filter trạng thái này).
--
-- Tiền điều kiện: V4 đã apply (cần app.categories tồn tại).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Thêm cột
-- ---------------------------------------------------------------------
ALTER TABLE app.products
    ADD COLUMN category_id UUID        NULL,
    ADD COLUMN sku         VARCHAR(64) NULL,
    ADD COLUMN status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT';

-- ---------------------------------------------------------------------
-- 2. Thêm constraint
-- ---------------------------------------------------------------------
ALTER TABLE app.products
    ADD CONSTRAINT products_category_id_fkey
        FOREIGN KEY (category_id) REFERENCES app.categories(id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT products_sku_unique UNIQUE (sku),
    ADD CONSTRAINT ck_products_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));

-- ---------------------------------------------------------------------
-- 3. Index
-- ---------------------------------------------------------------------
-- Index FK column (Postgres không tự).
CREATE INDEX idx_products_category_id ON app.products (category_id);

-- Partial cho ACTIVE — vì 99% query catalog filter status='ACTIVE'.
-- Partial index nhỏ hơn full → query nhanh hơn + tốn ít storage.
CREATE INDEX idx_products_active_created_at
    ON app.products (created_at DESC)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------
-- 4. Comments
-- ---------------------------------------------------------------------
COMMENT ON COLUMN app.products.category_id IS
    'FK trỏ tới app.categories. NULL allowed cho data cũ (sản phẩm chưa phân loại). ON DELETE RESTRICT.';
COMMENT ON COLUMN app.products.sku         IS 'Stock keeping unit, UNIQUE NULLABLE. Nullable để backfill được data cũ.';
COMMENT ON COLUMN app.products.status      IS 'DRAFT (chưa publish) | ACTIVE (bán) | ARCHIVED (ngừng bán).';

COMMENT ON INDEX app.idx_products_active_created_at IS
    'Partial index — chỉ index sản phẩm ACTIVE. Query phổ biến nhất trên catalog.';

-- ---------------------------------------------------------------------
-- 5. Log migration
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V5__alter_products_add_category',
    'Added category_id (FK), sku (UNIQUE nullable), status with CHECK constraint to app.products. Created idx_products_category_id and partial idx_products_active_created_at.'
);
