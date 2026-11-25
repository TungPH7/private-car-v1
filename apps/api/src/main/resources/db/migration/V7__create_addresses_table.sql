-- =====================================================================
-- V7__create_addresses_table.sql
-- Tạo bảng app.addresses — địa chỉ giao hàng / hóa đơn của customer.
--
-- Đặc trưng nổi bật:
--   - PARTIAL UNIQUE INDEX: chỉ 1 default address cho mỗi (customer, type, active).
--     Nhưng cho phép nhiều address thường (is_default = FALSE).
--   - Soft delete: is_deleted = TRUE → ẩn khỏi list nhưng giữ data (cho audit orders cũ).
--   - ON DELETE CASCADE: khi xóa customer → xóa luôn address (vô nghĩa nếu không có customer).
--
-- Tiền điều kiện: V6 đã apply (cần app.customers tồn tại).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.addresses (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    customer_id     UUID            NOT NULL,
    type            VARCHAR(16)     NOT NULL,                  -- SHIPPING | BILLING

    -- Address fields
    full_name       VARCHAR(160)    NOT NULL,
    phone           VARCHAR(32)     NOT NULL,
    line1           VARCHAR(255)    NOT NULL,
    line2           VARCHAR(255)    NULL,
    ward            VARCHAR(80)     NULL,
    district        VARCHAR(80)     NULL,
    city            VARCHAR(80)     NOT NULL,
    state_region    VARCHAR(80)     NULL,
    postal_code     VARCHAR(20)     NULL,
    country_code    CHAR(2)         NOT NULL DEFAULT 'VN',

    -- Flags
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Audit
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    -- Constraints
    CONSTRAINT addresses_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES app.customers(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_addresses_type
        CHECK (type IN ('SHIPPING','BILLING')),
    CONSTRAINT ck_addresses_country_iso
        CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_addresses_phone_e164
        CHECK (phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_addresses_full_name_non_blank
        CHECK (length(btrim(full_name)) > 0),
    CONSTRAINT ck_addresses_line1_non_blank
        CHECK (length(btrim(line1)) > 0),
    CONSTRAINT ck_addresses_city_non_blank
        CHECK (length(btrim(city)) > 0)
);

-- ---------------------------------------------------------------------
-- 2. Indexes — đặc biệt partial unique cho default address
-- ---------------------------------------------------------------------

-- ===== KEY CONSTRAINT của F02 =====
-- Mỗi (customer, type) chỉ có 1 default address (chưa deleted).
-- Cho phép có nhiều địa chỉ thường, nhiều default đã deleted (audit).
CREATE UNIQUE INDEX uq_addresses_default_per_type
    ON app.addresses (customer_id, type)
    WHERE is_default = TRUE AND is_deleted = FALSE;

-- Index hỗ trợ "list address của customer chưa deleted".
CREATE INDEX idx_addresses_customer_active
    ON app.addresses (customer_id)
    WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------
-- 3. Trigger updated_at
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_addresses_set_updated_at
    BEFORE UPDATE ON app.addresses
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 4. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.addresses              IS 'Địa chỉ giao hàng / hóa đơn của khách. Cascade khi xóa customer.';
COMMENT ON COLUMN app.addresses.type         IS 'SHIPPING (giao hàng) | BILLING (xuất hóa đơn). Khách có thể có 1 default mỗi type.';
COMMENT ON COLUMN app.addresses.is_default   IS 'Đánh dấu default cho type. Partial unique enforce 1 default mỗi (customer, type, active).';
COMMENT ON COLUMN app.addresses.is_deleted   IS 'Soft delete — order cũ vẫn ref tới address này. Hard delete sẽ vỡ FK orders.shipping_address_id (F05).';
COMMENT ON COLUMN app.addresses.country_code IS 'ISO 3166-1 alpha-2, UPPERCASE. Default VN.';

COMMENT ON INDEX app.uq_addresses_default_per_type IS
    'Partial unique: 1 default mỗi (customer, type) chưa deleted. KEY CONSTRAINT của F02.';

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V7__create_addresses_table',
    'Created app.addresses with FK customer_id (CASCADE), partial unique on default per type, soft delete via is_deleted, 6 CHECK constraints.'
);
