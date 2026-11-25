-- =====================================================================
-- V6__create_customers_table.sql
-- Tạo bảng app.customers — entity cốt lõi cho F05+ (orders cần customer_id).
--
-- Đặc trưng:
--   - email kiểu public.CITEXT UNIQUE — case-insensitive comparison.
--   - phone NULLABLE + partial unique index trên ACTIVE customers.
--   - status enum dạng VARCHAR + CHECK (tránh Postgres ENUM TYPE).
--   - 4 CHECK constraint: status, email format, phone E.164, name non-blank.
--
-- Tiền điều kiện:
--   - V1 (extensions citext), V2 (gen_uuid_v7, set_updated_at).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.customers (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    -- Identity
    email           public.CITEXT   NOT NULL,
    phone           VARCHAR(32)     NULL,
    first_name      VARCHAR(80)     NOT NULL,
    last_name       VARCHAR(80)     NOT NULL,

    -- State
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    last_login_at   TIMESTAMPTZ     NULL,

    -- Audit
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    -- Constraints
    CONSTRAINT customers_email_unique     UNIQUE (email),
    CONSTRAINT ck_customers_status        CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    CONSTRAINT ck_customers_email_format  CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_customers_phone_e164    CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_customers_name_non_blank
        CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0)
);

-- ---------------------------------------------------------------------
-- 2. Index
-- ---------------------------------------------------------------------

-- Partial unique cho phone — chỉ enforce cho ACTIVE customer có phone.
-- Customer DELETED giữ phone cũ trong DB (audit), không chiếm chỗ.
CREATE UNIQUE INDEX uq_customers_phone_active
    ON app.customers (phone)
    WHERE phone IS NOT NULL AND status = 'ACTIVE';

-- Composite cho query phổ biến: filter status + sort by created_at DESC.
CREATE INDEX idx_customers_status_created_at
    ON app.customers (status, created_at DESC);

-- ---------------------------------------------------------------------
-- 3. Trigger updated_at
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_customers_set_updated_at
    BEFORE UPDATE ON app.customers
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 4. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.customers              IS 'Khách hàng (B2C). Email case-insensitive (citext).';
COMMENT ON COLUMN app.customers.email        IS 'CITEXT — UNIQUE case-insensitive. Email so sánh không phân biệt hoa thường.';
COMMENT ON COLUMN app.customers.phone        IS 'E.164 format (+CCxxxxxxxxx). NULLABLE. Partial unique trên ACTIVE.';
COMMENT ON COLUMN app.customers.status       IS 'ACTIVE | SUSPENDED (admin disable) | DELETED (soft delete cho compliance).';
COMMENT ON COLUMN app.customers.email_verified IS 'TRUE sau khi click email verification link. Chưa implement ở F02.';

COMMENT ON INDEX app.uq_customers_phone_active IS
    'Partial unique: chỉ enforce phone unique cho ACTIVE customer. DELETED giữ data để audit.';

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V6__create_customers_table',
    'Created app.customers with citext email UNIQUE, partial unique phone (active only), status enum via VARCHAR+CHECK, 4 CHECK constraints (status, email format, phone E.164, name non-blank).'
);
