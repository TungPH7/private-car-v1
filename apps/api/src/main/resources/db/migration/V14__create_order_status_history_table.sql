-- =====================================================================
-- V14__create_order_status_history_table.sql
-- Append-only log lịch sử thay đổi status order.
-- Pattern như F03 inventory_movements: KHÔNG UPDATE / DELETE row, enforce ở
-- app layer (không REVOKE ở DB để đơn giản).
--
-- Tiền điều kiện: V12 (orders).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.order_status_history (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    order_id        UUID            NOT NULL,
    from_status     VARCHAR(16)     NULL,                   -- NULL = trạng thái khởi tạo (PENDING)
    to_status       VARCHAR(16)     NOT NULL,
    reason          VARCHAR(255)    NULL,
    actor           VARCHAR(64)     NOT NULL,               -- customerId / system / ops

    changed_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- Constraints
    CONSTRAINT order_status_history_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE CASCADE,
    CONSTRAINT ck_osh_status_diff
        CHECK (from_status IS DISTINCT FROM to_status),
    -- CHECK coi NULL là pass → from_status NULL (row khởi tạo) tự qua, không cần OR IS NULL.
    CONSTRAINT ck_osh_from_status
        CHECK (from_status IN ('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_osh_to_status
        CHECK (to_status IN ('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_osh_actor_non_blank
        CHECK (length(btrim(actor)) > 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------
-- Query "lịch sử của 1 order theo time". order_id đứng đầu → cover luôn FK check
-- / CASCADE khi xóa order.
CREATE INDEX idx_osh_order_changed
    ON app.order_status_history (order_id, changed_at);

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE app.order_status_history IS
    'Append-only log status transitions của order. CASCADE khi xóa order.';
COMMENT ON COLUMN app.order_status_history.from_status IS
    'NULL = trạng thái khởi tạo (lần đầu PENDING). Sau đó luôn non-null.';
COMMENT ON CONSTRAINT ck_osh_status_diff ON app.order_status_history IS
    'from_status DISTINCT FROM to_status — không log "đổi" nếu không đổi gì.';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V14__create_order_status_history_table',
    'Created append-only app.order_status_history with FK CASCADE order, 4 CHECK constraints ' ||
    '(status diff, from/to status domain, actor non-blank). IS DISTINCT FROM handles NULL semantics properly.'
);
