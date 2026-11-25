-- =====================================================================
-- V8__create_product_inventory_table.sql
-- Tách inventory ra khỏi products để:
--   - Lock granularity tốt hơn (lock row inventory không block catalog read).
--   - Update frequency cao (mỗi đơn) không ảnh hưởng catalog query.
--   - Audit movement log có FK riêng tới product.
--
-- 3-state inventory:
--   - on_hand:   số vật lý trong kho.
--   - reserved:  đã trừ cho đơn pending (chưa giao).
--   - sold:      cumulative tổng đã bán (BIGINT để chứa 10+ năm dữ liệu).
--   - available  = on_hand - reserved (computed, không lưu).
--
-- CHECK constraint là KEY của F03 — bảo vệ inventory invariant.
--
-- Tiền điều kiện:
--   - V2 (app helpers) đã chạy: app.set_updated_at().
--   - V3 (products) đã chạy: app.products tồn tại.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.product_inventory (
    product_id      UUID            PRIMARY KEY,            -- 1..1 với products

    on_hand         INTEGER         NOT NULL DEFAULT 0,
    reserved        INTEGER         NOT NULL DEFAULT 0,
    sold            BIGINT          NOT NULL DEFAULT 0,

    reorder_point   INTEGER         NULL,                   -- cảnh báo low stock; NULL = không cảnh báo
    last_restock_at TIMESTAMPTZ     NULL,

    -- Audit nhỏ (không cần full BaseEntity vì 1-1 với product)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version         BIGINT          NOT NULL DEFAULT 0,

    -- Constraints
    CONSTRAINT product_inventory_product_id_fkey
        FOREIGN KEY (product_id) REFERENCES app.products(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_inventory_on_hand_non_neg     CHECK (on_hand    >= 0),
    CONSTRAINT ck_inventory_reserved_non_neg    CHECK (reserved   >= 0),
    CONSTRAINT ck_inventory_reserved_le_on_hand CHECK (reserved  <= on_hand),
    CONSTRAINT ck_inventory_sold_non_neg        CHECK (sold      >= 0),
    CONSTRAINT ck_inventory_reorder_non_neg
        CHECK (reorder_point IS NULL OR reorder_point >= 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------

-- Expression index trên (on_hand - reserved) cho query low-stock.
-- Partial: chỉ index row có reorder_point set (skip product không track).
CREATE INDEX idx_inventory_available_low_stock
    ON app.product_inventory ((on_hand - reserved))
    WHERE reorder_point IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3. Trigger updated_at
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_product_inventory_set_updated_at
    BEFORE UPDATE ON app.product_inventory
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 4. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.product_inventory             IS '3-state inventory: on_hand, reserved, sold. CASCADE khi xóa product.';
COMMENT ON COLUMN app.product_inventory.on_hand     IS 'Số lượng vật lý trong kho (chưa trừ reserved).';
COMMENT ON COLUMN app.product_inventory.reserved   IS 'Đã trừ cho đơn pending. Khi đơn DELIVERED → chuyển sang sold.';
COMMENT ON COLUMN app.product_inventory.sold       IS 'Cumulative đã bán (BIGINT). KHÔNG decrement khi cancel — dùng cho report turnover.';
COMMENT ON COLUMN app.product_inventory.reorder_point IS 'Threshold cảnh báo low stock. NULL = không track.';

COMMENT ON CONSTRAINT ck_inventory_reserved_le_on_hand ON app.product_inventory IS
    'KEY INVARIANT: không cho reserved vượt on_hand. Bất kỳ UPDATE vi phạm → DB chặn.';

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V8__create_product_inventory_table',
    'Created app.product_inventory with 5 CHECK constraints (on_hand/reserved/sold non-neg + reserved<=on_hand + reorder>=0). ' ||
    'Expression partial index idx_inventory_available_low_stock for low-stock query. Trigger updated_at.'
);
