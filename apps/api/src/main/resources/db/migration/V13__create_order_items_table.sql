-- =====================================================================
-- V13__create_order_items_table.sql
-- Chi tiết items của order với snapshot product info + generated line_total.
--
-- Đặc trưng:
--   - Generated STORED column line_total = quantity * unit_price.
--   - Snapshot product_name + sku tại thời điểm order (defense rename/archive).
--   - UNIQUE (order_id, product_id) — force FE gộp qty thay vì insert 2 row.
--   - ON DELETE CASCADE: xóa order xóa luôn items.
--
-- Tiền điều kiện: V3 (products), V12 (orders).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.order_items (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    order_id        UUID            NOT NULL,
    product_id      UUID            NOT NULL,

    -- Snapshot product info
    product_name    VARCHAR(255)    NOT NULL,
    product_sku     VARCHAR(64)     NULL,

    quantity        INTEGER         NOT NULL,
    unit_price      NUMERIC(12,2)   NOT NULL,

    -- Generated STORED — DB tự tính lúc INSERT/UPDATE, app không ghi được.
    -- NUMERIC(14,2) rộng hơn unit_price, nhưng vẫn có trần: vượt → 22003
    -- numeric_value_out_of_range, INSERT fail (không âm thầm cắt số).
    line_total      NUMERIC(14,2)
        GENERATED ALWAYS AS (quantity * unit_price) STORED,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT order_items_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE CASCADE,
    CONSTRAINT order_items_product_id_fkey
        FOREIGN KEY (product_id) REFERENCES app.products(id) ON DELETE RESTRICT,
    CONSTRAINT uq_order_items_order_product
        UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_qty_positive
        CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price_non_neg
        CHECK (unit_price >= 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------
-- uq_order_items_order_product đã tạo btree (order_id, product_id) — order_id
-- đứng đầu nên phục vụ luôn "list items của 1 order" + FK check / CASCADE khi
-- xóa order. Không cần index riêng cho order_id.

-- Query "list orders chứa product X" (admin debug, recall) + FK check khi xóa product.
CREATE INDEX idx_order_items_product
    ON app.order_items (product_id);

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.order_items              IS 'Chi tiết items của order. Snapshot product info + generated line_total.';
COMMENT ON COLUMN app.order_items.line_total   IS 'GENERATED STORED = quantity * unit_price. DB tự tính, app không sai được.';
COMMENT ON COLUMN app.order_items.product_name IS 'Snapshot tại thời điểm order. Product có thể đổi tên sau, item giữ tên gốc.';
COMMENT ON COLUMN app.order_items.unit_price   IS 'Snapshot products.price tại thời điểm order. Product đổi giá sau, item giữ giá đã charge.';

COMMENT ON CONSTRAINT uq_order_items_order_product ON app.order_items IS
    'Force FE gộp qty cho cùng product. Insert 2 row cùng (order, product) → fail.';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V13__create_order_items_table',
    'Created app.order_items with GENERATED STORED line_total, snapshot product_name+sku, ' ||
    'UNIQUE (order_id, product_id), FK CASCADE order + RESTRICT product, index on product_id.'
);
