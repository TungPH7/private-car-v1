-- =====================================================================
-- V12__create_orders_table.sql
-- Tạo bảng app.orders + sequence cho order_number + FK forward.
--
-- Bao gồm:
--   - app.orders_number_seq cho order_number format ORD-YYYYMM-NNNNNN.
--   - app.orders với 9 CHECK constraint (money invariant, status, timestamp ↔ status,
--     country ISO).
--   - Snapshot pattern: shipping fields copy từ addresses tại thời điểm order.
--   - FK forward: coupon_redemptions.order_id → orders.id (add ở đây vì V11 không thể).
--
-- Tiền điều kiện: V6 (customers), V7 (addresses), V10 (coupons), V11 (redemptions).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Sequence cho order_number
-- ---------------------------------------------------------------------
-- CACHE 20: mỗi session (connection) giữ sẵn 20 giá trị trong memory → nextval
-- không phải ghi sequence mỗi lần. Đổi lại:
--   - Gap: giá trị cache chưa dùng mất khi session đóng / DB restart. Gap cũng
--     xảy ra dù CACHE 1, vì nextval KHÔNG rollback theo transaction.
--   - Không theo thời gian: 2 connection giữ 2 block khác nhau (1..20, 21..40)
--     → đơn tạo sau có thể mang số nhỏ hơn. Sort theo created_at, không theo số.
CREATE SEQUENCE app.orders_number_seq AS BIGINT START 1 CACHE 20;

COMMENT ON SEQUENCE app.orders_number_seq IS
    'Sequence cho order_number. App layer format ORD-YYYYMM-NNNNNN. CACHE 20: có gap và '
    'không đơn điệu theo thời gian giữa các connection — chấp nhận, số đơn chỉ cần UNIQUE.';

-- ---------------------------------------------------------------------
-- 2. Tạo bảng orders
-- ---------------------------------------------------------------------
CREATE TABLE app.orders (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    -- Order number user-friendly
    order_number    VARCHAR(32)     NOT NULL,

    -- Quan hệ
    customer_id           UUID      NOT NULL,
    shipping_address_id   UUID      NOT NULL,
    coupon_id             UUID      NULL,

    -- Snapshot shipping (defense: address có thể bị sửa / soft delete sau)
    shipping_recipient    VARCHAR(160) NOT NULL,
    shipping_phone        VARCHAR(32)  NOT NULL,
    shipping_address_line1 VARCHAR(255) NOT NULL,
    shipping_address_line2 VARCHAR(255) NULL,
    shipping_city         VARCHAR(80)  NOT NULL,
    shipping_country      CHAR(2)      NOT NULL,

    -- Tiền (NUMERIC để không sai float)
    subtotal              NUMERIC(12,2) NOT NULL,
    discount_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_fee          NUMERIC(12,2) NOT NULL DEFAULT 0,
    total                 NUMERIC(12,2) NOT NULL,

    -- State machine
    status                VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    cancelled_at          TIMESTAMPTZ   NULL,
    paid_at               TIMESTAMPTZ   NULL,
    shipped_at            TIMESTAMPTZ   NULL,
    delivered_at          TIMESTAMPTZ   NULL,
    cancel_reason         VARCHAR(255)  NULL,

    -- Note
    customer_note         TEXT          NULL,
    internal_note         TEXT          NULL,

    -- Audit
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by            VARCHAR(64)   NOT NULL DEFAULT 'system',
    version               BIGINT        NOT NULL DEFAULT 0,

    -- ===== Constraints =====
    CONSTRAINT orders_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES app.customers(id) ON DELETE RESTRICT,
    CONSTRAINT orders_shipping_address_id_fkey
        FOREIGN KEY (shipping_address_id) REFERENCES app.addresses(id) ON DELETE RESTRICT,
    CONSTRAINT orders_coupon_id_fkey
        FOREIGN KEY (coupon_id) REFERENCES app.coupons(id) ON DELETE RESTRICT,
    CONSTRAINT orders_order_number_unique     UNIQUE (order_number),

    -- ===================== CHECK constraints (9) =====================
    CONSTRAINT ck_orders_status
        CHECK (status IN ('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_orders_subtotal_non_neg     CHECK (subtotal       >= 0),
    CONSTRAINT ck_orders_discount_non_neg     CHECK (discount_amount >= 0),
    CONSTRAINT ck_orders_discount_le_subtotal CHECK (discount_amount <= subtotal),
    CONSTRAINT ck_orders_shipping_non_neg     CHECK (shipping_fee   >= 0),

    -- Money invariant. So sánh SAU khi giá trị đã làm tròn về NUMERIC(12,2) →
    -- app phải tính total từ các thành phần đã làm tròn 2 chữ số.
    CONSTRAINT ck_orders_total_match
        CHECK (total = subtotal - discount_amount + shipping_fee),

    -- Timestamp ↔ status invariant.
    -- paid_at chỉ được có khi đơn đã qua PAID (CANCELLED sau PAID vẫn giữ paid_at).
    -- EXPIRED chỉ đi ra từ PENDING nên không bao giờ có paid_at.
    CONSTRAINT ck_orders_paid_at_with_status
        CHECK (paid_at IS NULL OR status IN ('PAID','SHIPPED','DELIVERED','CANCELLED')),
    -- cancelled_at có ⇔ đơn đã kết thúc bằng hủy: CANCELLED (người hủy) hoặc
    -- EXPIRED (job hủy). Status NOT NULL nên 2 vế luôn TRUE/FALSE, không có NULL.
    CONSTRAINT ck_orders_cancelled_at_with_status
        CHECK ((status IN ('CANCELLED','EXPIRED')) = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_orders_shipping_country_iso
        CHECK (shipping_country ~ '^[A-Z]{2}$')
);

-- ---------------------------------------------------------------------
-- 3. Indexes
-- ---------------------------------------------------------------------
-- UNIQUE (order_number) đã tự tạo btree — getOrderByNumber không cần index riêng.
--
-- Coverage cho FK (Postgres không tự index cột FK):
--   - customer_id: cột đầu của idx_orders_customer_created_at.
--   - coupon_id:   idx_orders_coupon_id (partial IS NOT NULL — `coupon_id = ?`
--                  suy ra được IS NOT NULL nên planner vẫn dùng được).
--   - shipping_address_id: CỐ Ý không index. FK check trên cột này chỉ chạy khi
--     address bị hard delete — app chỉ soft delete (is_deleted), hard delete chỉ
--     còn đường CASCADE khi xóa hẳn customer (V7), vốn hiếm. Chấp nhận seq scan
--     orders lúc đó thay vì trả write cost index cho mọi INSERT order.

-- Query phổ biến nhất: list orders của customer mới nhất.
CREATE INDEX idx_orders_customer_created_at
    ON app.orders (customer_id, created_at DESC);

-- Admin filter status.
CREATE INDEX idx_orders_status_created_at
    ON app.orders (status, created_at DESC);

-- Partial cho job expire — chỉ index đơn PENDING. Đơn PENDING quá 30 phút bị
-- job expire, nên tập này luôn nhỏ so với toàn bảng.
CREATE INDEX idx_orders_pending_created_at
    ON app.orders (created_at)
    WHERE status = 'PENDING';

-- List đơn dùng coupon X (admin / report) + FK check khi xóa coupon.
CREATE INDEX idx_orders_coupon_id
    ON app.orders (coupon_id)
    WHERE coupon_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. Trigger updated_at
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_orders_set_updated_at
    BEFORE UPDATE ON app.orders
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 5. FK FORWARD: coupon_redemptions.order_id → orders.id
-- ---------------------------------------------------------------------
-- V11 không add được FK này vì orders chưa tồn tại. Add ở V12.
--
-- ADD CONSTRAINT validate toàn bộ row hiện có: còn row nào có order_id không
-- trỏ tới order thật → migration fail (23503) và cả V12 rollback.
--
-- Không thêm index (order_id): uq_coupon_redemption_order là (coupon_id, order_id)
-- — order_id đứng sau nên FK check khi DELETE order không seek được theo order_id
-- (chỉ quét toàn bảng / toàn index). Chấp nhận vì order không bị hard delete trong
-- flow app (hủy = đổi status).
ALTER TABLE app.coupon_redemptions
    ADD CONSTRAINT coupon_redemptions_order_id_fkey
    FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT;

-- Comment cũ ở V11 ghi "chưa có ràng buộc referential" — sai kể từ migration này.
COMMENT ON COLUMN app.coupon_redemptions.order_id IS
    'FK → app.orders(id) ON DELETE RESTRICT (add ở V12, sau khi app.orders tồn tại).';

-- ---------------------------------------------------------------------
-- 6. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.orders                      IS 'Đơn hàng. Snapshot shipping fields để decouple với customer/address mutation.';
COMMENT ON COLUMN app.orders.order_number         IS 'ORD-YYYYMM-NNNNNN, NNNNNN từ app.orders_number_seq. Có gap, không liên tục — chấp nhận.';
COMMENT ON COLUMN app.orders.shipping_recipient   IS 'Snapshot addresses.full_name tại thời điểm order. Address sửa / soft delete sau, đơn vẫn giữ data gốc.';
COMMENT ON COLUMN app.orders.total                IS 'CHECK total = subtotal - discount + shipping. DB enforce money invariant.';
COMMENT ON COLUMN app.orders.status               IS 'PENDING | PAID | SHIPPED | DELIVERED | CANCELLED | EXPIRED. Transition hợp lệ do app guard.';
COMMENT ON COLUMN app.orders.cancelled_at         IS 'Set khi status = CANCELLED hoặc EXPIRED (xem ck_orders_cancelled_at_with_status).';

COMMENT ON CONSTRAINT ck_orders_total_match ON app.orders IS
    'KEY INVARIANT: tổng tiền phải match công thức. App tính sai → DB chặn.';
COMMENT ON CONSTRAINT ck_orders_cancelled_at_with_status ON app.orders IS
    'cancelled_at IS NOT NULL ⇔ status IN (CANCELLED, EXPIRED). Thiếu hoặc thừa timestamp → DB chặn.';

-- ---------------------------------------------------------------------
-- 7. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V12__create_orders_table',
    'Created app.orders_number_seq + app.orders with snapshot shipping, 9 CHECK constraints ' ||
    '(money invariant, status, timestamp ↔ status, country ISO), 4 indexes (customer-time, ' ||
    'status-time, partial PENDING, partial coupon). Forward FK coupon_redemptions.order_id added.'
);
