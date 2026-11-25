-- =====================================================================
-- V9__create_inventory_movements_table.sql
-- Append-only log của mọi thay đổi inventory.
--
-- Property:
--   - KHÔNG UPDATE / DELETE row của bảng này.
--   - SUM(delta_*) rebuild được state inventory tại bất kỳ T.
--   - reconcileInventory so sánh state hiện tại với sum movements → detect drift.
--
-- Enforce append-only ở app layer (không REVOKE UPDATE ở DB để đơn giản;
-- production có thể REVOKE UPDATE,DELETE ON app.inventory_movements FROM app_role).
--
-- Tiền điều kiện: V3 (products), V8 (product_inventory).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.inventory_movements (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    product_id      UUID            NOT NULL,
    movement_type   VARCHAR(24)     NOT NULL,

    -- Delta (signed — RESERVE +, RELEASE -, COMMIT - on_hand)
    delta_on_hand   INTEGER         NOT NULL DEFAULT 0,
    delta_reserved  INTEGER         NOT NULL DEFAULT 0,
    delta_sold      INTEGER         NOT NULL DEFAULT 0,

    -- Snapshot sau khi áp dụng delta
    on_hand_after   INTEGER         NOT NULL,
    reserved_after  INTEGER         NOT NULL,

    -- Context
    reason          VARCHAR(255)    NULL,
    order_id        UUID            NULL,                       -- ref tới orders (F05)
    actor           VARCHAR(64)     NOT NULL,                   -- system / userId / job_name

    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT inventory_movements_product_id_fkey
        FOREIGN KEY (product_id) REFERENCES app.products(id)
        ON DELETE RESTRICT,                                     -- Audit phải giữ
    CONSTRAINT ck_inv_mov_type
        CHECK (movement_type IN ('STOCK_IN','STOCK_OUT','RESERVE','RELEASE','COMMIT','ADJUSTMENT')),
    CONSTRAINT ck_inv_mov_snapshot_non_neg
        CHECK (on_hand_after >= 0 AND reserved_after >= 0),
    CONSTRAINT ck_inv_mov_actor_non_blank
        CHECK (length(btrim(actor)) > 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------

-- Query phổ biến: lịch sử movement của 1 product theo thời gian DESC.
CREATE INDEX idx_inv_mov_product_time
    ON app.inventory_movements (product_id, occurred_at DESC);

-- Query: list movement của 1 order (cho debug, refund).
CREATE INDEX idx_inv_mov_order_id
    ON app.inventory_movements (order_id)
    WHERE order_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.inventory_movements             IS 'Append-only log. KHÔNG UPDATE/DELETE row. Source of truth cho audit + reconcile.';
COMMENT ON COLUMN app.inventory_movements.movement_type IS 'STOCK_IN | STOCK_OUT | RESERVE | RELEASE | COMMIT | ADJUSTMENT';
COMMENT ON COLUMN app.inventory_movements.delta_on_hand IS 'Thay đổi on_hand. +N = nhập, -N = xuất.';
COMMENT ON COLUMN app.inventory_movements.delta_reserved IS 'Thay đổi reserved. +N = đặt giữ, -N = giải phóng.';
COMMENT ON COLUMN app.inventory_movements.on_hand_after IS 'Snapshot on_hand sau khi áp dụng delta. Cho reconcile.';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V9__create_inventory_movements_table',
    'Created append-only app.inventory_movements with FK to products (RESTRICT). ' ||
    'Indexed (product_id, occurred_at DESC) + partial on order_id. 3 CHECK constraints.'
);
