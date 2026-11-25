-- =====================================================================
-- V11__create_coupon_redemptions_table.sql
-- Log mỗi lần coupon được apply vào 1 order.
--
-- Vai trò:
--   - Idempotency: UNIQUE (coupon_id, order_id) — redeem lại cùng order không
--     tăng used_count lần 2. INSERT ... ON CONFLICT DO NOTHING RETURNING id
--     cho biết lần này là mới hay đã có.
--   - Per-customer limit: COUNT(*) theo customer_id để enforce
--     coupons.max_uses_per_customer.
--   - Audit: lịch sử dùng coupon của từng customer.
--
-- order_id CHƯA có FK: bảng app.orders tạo ở F05. FK sẽ add ở V12 để tránh
-- circular dependency giữa 2 feature branch.
--
-- Tiền điều kiện:
--   - V2 (app.gen_uuid_v7()), V6 (app.customers), V10 (app.coupons).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.coupon_redemptions (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    coupon_id       UUID            NOT NULL,
    order_id        UUID            NOT NULL,                   -- FK add ở V12 (F05 orders)
    customer_id     UUID            NOT NULL,                   -- denormalize: đếm per-customer không cần join orders

    discount_amount NUMERIC(12,2)   NOT NULL,                   -- số tiền đã giảm thực tế tại thời điểm redeem
    redeemed_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- Constraints
    -- RESTRICT cả 2 FK: redemption là audit trail, không được biến mất khi
    -- xoá coupon/customer. Muốn xoá coupon thì set is_active = FALSE.
    CONSTRAINT coupon_redemptions_coupon_id_fkey
        FOREIGN KEY (coupon_id) REFERENCES app.coupons(id)
        ON DELETE RESTRICT,
    CONSTRAINT coupon_redemptions_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES app.customers(id)
        ON DELETE RESTRICT,

    -- KEY: nền tảng của idempotent redeem.
    CONSTRAINT uq_coupon_redemption_order
        UNIQUE (coupon_id, order_id),

    CONSTRAINT ck_coupon_redemption_discount_non_neg
        CHECK (discount_amount >= 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------
-- Lưu ý: uq_coupon_redemption_order đã tạo sẵn btree (coupon_id, order_id),
-- phục vụ cả lookup idempotency lẫn FK check trên coupon_id.

-- Per-customer limit: COUNT(*) WHERE customer_id = ? AND coupon_id = ?.
-- customer_id đứng trước → index này cũng cover FK check khi xoá customer.
CREATE INDEX idx_coupon_redemptions_customer
    ON app.coupon_redemptions (customer_id, coupon_id);

-- Lịch sử redeem của 1 coupon theo thời gian giảm dần (trang admin).
CREATE INDEX idx_coupon_redemptions_coupon
    ON app.coupon_redemptions (coupon_id, redeemed_at DESC);

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.coupon_redemptions IS
    'Log mỗi lần redeem coupon vào order. Idempotency qua UNIQUE (coupon_id, order_id).';
COMMENT ON COLUMN app.coupon_redemptions.order_id IS
    'FK sẽ add ở V12 (sau khi app.orders tạo ở F05). Hiện chưa có ràng buộc referential.';
COMMENT ON COLUMN app.coupon_redemptions.customer_id IS
    'Denormalize từ order để COUNT per-customer không phải join orders.';
COMMENT ON COLUMN app.coupon_redemptions.discount_amount IS
    'Số tiền đã giảm thực tế. Snapshot tại thời điểm redeem — coupon đổi value sau đó không ảnh hưởng.';

COMMENT ON CONSTRAINT uq_coupon_redemption_order ON app.coupon_redemptions IS
    'KEY: cùng (coupon, order) chỉ log 1 lần. Vi phạm → SQLSTATE 23505, app dịch thành idempotent return.';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V11__create_coupon_redemptions_table',
    'Created app.coupon_redemptions with UNIQUE (coupon_id, order_id) for idempotent redeem. ' ||
    'FK coupon_id + customer_id (RESTRICT, audit trail). order_id FK deferred to V12 (F05 orders table).'
);
