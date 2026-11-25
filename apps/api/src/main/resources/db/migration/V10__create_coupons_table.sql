-- =====================================================================
-- V10__create_coupons_table.sql
-- Tạo bảng app.coupons — mã giảm giá (coupon / voucher).
--
-- Đặc trưng (KEY của F04):
--   - EXCLUDE constraint USING gist: 2 coupon CÙNG code không được overlap
--     valid_period khi cùng active. Feature riêng của Postgres — MySQL/MSSQL
--     không có, phải mô phỏng bằng trigger hoặc lock ở app layer.
--   - tstzrange '[from, to)' cho valid_period — nửa-mở, chống off-by-one khi
--     2 campaign nối tiếp nhau (7/1 thuộc campaign sau, không thuộc campaign trước).
--   - 10 CHECK constraint encode business rule xuống DB (không chỉ Bean Validation).
--   - btree_gist extension: cấp operator class cho '=' trên VARCHAR trong GiST.
--
-- Tiền điều kiện:
--   - V1 (schema 'app' + app._app_migration_log).
--   - V2 (app.gen_uuid_v7(), app.set_updated_at()).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Extension btree_gist
-- ---------------------------------------------------------------------
-- GiST mặc định KHÔNG có operator class cho scalar type. Thiếu extension này,
-- câu EXCLUDE ở section 3 fail với:
--   ERROR: data type character varying has no default operator class
--          for access method "gist"
--
-- Vì sao CREATE ở đây thay vì ở docker/postgres/init/00-load-env.sh như
-- uuid-ossp / pgcrypto / citext / pg_trgm:
--   1. btree_gist chỉ F04 cần → migration self-contained, đọc V10 là thấy đủ
--      tiền điều kiện của nó.
--   2. Init script CHỈ chạy khi volume rỗng. Dev đã có DB từ V1..V9 sẽ không
--      bao giờ nhận được extension nếu đặt ở đó → phải xoá volume, mất data.
--
-- Chạy được bởi app role non-superuser: btree_gist.control có `trusted = true`
-- (từ PG 13), nên role chỉ cần CREATE trên DATABASE — Postgres cài extension
-- dưới danh nghĩa bootstrap superuser, không đòi CREATE trên schema public.
-- Đặt ở schema 'public' cho nhất quán với 4 extension sẵn có.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- ---------------------------------------------------------------------
-- 2. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.coupons (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    code            VARCHAR(40)     NOT NULL,
    description     TEXT            NULL,

    -- Loại + giá trị
    type            VARCHAR(16)     NOT NULL,                   -- PERCENT | AMOUNT | FREE_SHIPPING
    value           NUMERIC(12,2)   NOT NULL,                   -- PERCENT: (0,100]; AMOUNT: VND; FREE_SHIPPING: 0
    max_discount    NUMERIC(12,2)   NULL,                       -- trần giảm, chỉ có nghĩa với PERCENT

    -- Điều kiện áp dụng
    min_order_amount      NUMERIC(12,2) NULL,                   -- NULL = không yêu cầu
    max_uses              INTEGER       NULL,                   -- NULL = unlimited
    max_uses_per_customer INTEGER       NULL,                   -- NULL = unlimited per customer

    -- Thời gian hiệu lực
    valid_period    TSTZRANGE       NOT NULL,                   -- '[from, to)'

    -- Counter + state
    used_count      INTEGER         NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,

    -- Audit (BaseEntity)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    -- ===================== CHECK constraints (10) =====================
    CONSTRAINT ck_coupons_type
        CHECK (type IN ('PERCENT','AMOUNT','FREE_SHIPPING')),

    -- Cross-column: miền hợp lệ của `value` phụ thuộc `type`.
    CONSTRAINT ck_coupons_value
        CHECK (
            (type = 'PERCENT'       AND value > 0 AND value <= 100) OR
            (type = 'AMOUNT'        AND value > 0) OR
            (type = 'FREE_SHIPPING' AND value = 0)
        ),

    CONSTRAINT ck_coupons_max_discount
        CHECK (max_discount IS NULL OR max_discount > 0),

    CONSTRAINT ck_coupons_min_order
        CHECK (min_order_amount IS NULL OR min_order_amount > 0),

    CONSTRAINT ck_coupons_max_uses
        CHECK (max_uses IS NULL OR max_uses > 0),

    CONSTRAINT ck_coupons_max_per_customer
        CHECK (max_uses_per_customer IS NULL OR max_uses_per_customer > 0),

    CONSTRAINT ck_coupons_used_count
        CHECK (used_count >= 0),

    -- Cross-column: chặn counter vượt quota kể cả khi ai đó hạ max_uses.
    CONSTRAINT ck_coupons_used_le_max
        CHECK (max_uses IS NULL OR used_count <= max_uses),

    -- Range phải bounded 2 đầu và non-empty.
    -- lower()/upper() trả NULL khi range empty hoặc bound vô hạn → CHECK fail.
    CONSTRAINT ck_coupons_period_bounded
        CHECK (
            lower(valid_period) IS NOT NULL AND
            upper(valid_period) IS NOT NULL AND
            lower(valid_period) < upper(valid_period)
        ),

    CONSTRAINT ck_coupons_code_format
        CHECK (code ~ '^[A-Z0-9_-]{3,40}$')
);

-- ---------------------------------------------------------------------
-- 3. EXCLUDE constraint — KEY của F04
-- ---------------------------------------------------------------------
-- Rule: 2 coupon CÙNG code không được active overlap thời gian.
--   SUM25 [6/1, 7/1) + SUM25 [7/1, 8/1)  → OK  (touch, không overlap)
--   SUM25 [6/1, 7/1) + SUM25 [6/15, 8/15) → FAIL (23P01)
--   SUM25 [6/1, 7/1) + WIN25 [6/15, 8/15) → OK  (khác code)
--
-- Partial WHERE (is_active = TRUE): deactivate coupon cũ sẽ nhả lại khoảng
-- thời gian cho coupon mới cùng code — cần cho flow "sửa campaign đang chạy".
ALTER TABLE app.coupons
    ADD CONSTRAINT ex_coupons_code_no_overlap
    EXCLUDE USING gist (
        code         WITH =,
        valid_period WITH &&
    )
    WHERE (is_active = TRUE);

-- ---------------------------------------------------------------------
-- 4. Index
-- ---------------------------------------------------------------------

-- Lookup code khi validate — query filter `code = ? AND is_active = TRUE`,
-- phần `valid_period @> now()` lọc tiếp ở query (xem chú thích bên dưới).
--
-- NON-UNIQUE là bắt buộc: cùng code được phép nhiều active row miễn period
-- không overlap (chính là rule mà EXCLUDE ở trên enforce). Đặt UNIQUE ở đây
-- sẽ siết rule thành "mỗi code chỉ 1 active row" — chặt hơn hẳn EXCLUDE,
-- biến EXCLUDE thành dead constraint và chặn luôn 2 campaign nối tiếp nhau.
--
-- Predicate KHÔNG được chứa now(): index predicate phải IMMUTABLE, nếu không
-- Postgres reject với "functions in index predicate must be marked IMMUTABLE".
--
-- Đo thực tế (PG 17.10, 10k row, ANALYZE): index của EXCLUDE ở section 3 —
-- gist (code, valid_period) WHERE is_active — cũng trả lời được `code = ?` và
-- planner hiện CHỌN NÓ (cost 8.29 vs 8.30). Index này vẫn giữ vì:
--   - btree 632 kB vs gist 1032 kB → rẻ hơn khi bảng lớn dần.
--   - Tách read path khỏi constraint: đổi EXCLUDE sau này không làm chậm lookup.
-- Nếu cần tiết kiệm write amplification, đây là index đầu tiên nên cân nhắc bỏ.
CREATE INDEX idx_coupons_code_active
    ON app.coupons (code)
    WHERE is_active = TRUE;

-- GiST trên valid_period cho query "list coupon hiệu lực tại thời điểm T"
-- (`valid_period @> :t`) khi không biết trước code.
CREATE INDEX idx_coupons_valid_period_gist
    ON app.coupons USING gist (valid_period);

-- ---------------------------------------------------------------------
-- 5. Trigger updated_at
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_coupons_set_updated_at
    BEFORE UPDATE ON app.coupons
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 6. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.coupons              IS 'Mã giảm giá. EXCLUDE constraint chặn 2 coupon cùng code overlap thời gian active.';
COMMENT ON COLUMN app.coupons.code         IS 'Mã nhập ở cart. Không unique: cùng code được phép nhiều campaign nối tiếp (không overlap).';
COMMENT ON COLUMN app.coupons.type         IS 'PERCENT (value 0-100) | AMOUNT (số tiền) | FREE_SHIPPING (value = 0).';
COMMENT ON COLUMN app.coupons.value        IS 'Ý nghĩa phụ thuộc type — xem ck_coupons_value.';
COMMENT ON COLUMN app.coupons.max_discount IS 'Trần giảm cho PERCENT. NULL = không trần.';
COMMENT ON COLUMN app.coupons.valid_period IS 'tstzrange [from, to). Nửa-mở để 2 campaign liên tiếp không overlap.';
COMMENT ON COLUMN app.coupons.max_uses     IS 'Tổng lượt redeem cho phép. NULL = unlimited.';
COMMENT ON COLUMN app.coupons.max_uses_per_customer IS 'Lượt redeem tối đa per customer. NULL = unlimited.';
COMMENT ON COLUMN app.coupons.used_count   IS 'Counter tăng atomic khi redeem: UPDATE ... WHERE used_count < max_uses RETURNING.';
COMMENT ON COLUMN app.coupons.is_active    IS 'FALSE = soft disable. Row inactive nằm ngoài EXCLUDE (partial WHERE).';

COMMENT ON CONSTRAINT ex_coupons_code_no_overlap ON app.coupons IS
    'KEY: 2 coupon cùng code không được active overlap valid_period. Partial WHERE is_active=TRUE. Vi phạm → SQLSTATE 23P01.';
COMMENT ON CONSTRAINT ck_coupons_value ON app.coupons IS
    'Business rule: PERCENT (0,100]; AMOUNT > 0; FREE_SHIPPING = 0.';
COMMENT ON CONSTRAINT ck_coupons_period_bounded ON app.coupons IS
    'valid_period phải bounded 2 đầu và non-empty (lower/upper trả NULL khi empty hoặc vô hạn).';

COMMENT ON INDEX app.idx_coupons_code_active IS
    'Partial NON-UNIQUE trên code. KHÔNG đổi thành UNIQUE: sẽ siết rule thành "1 active row per code", '
    'mâu thuẫn với ex_coupons_code_no_overlap và chặn 2 campaign nối tiếp cùng code.';

-- ---------------------------------------------------------------------
-- 7. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V10__create_coupons_table',
    'Created app.coupons with EXCLUDE USING gist (code WITH =, valid_period WITH &&) WHERE is_active=TRUE. ' ||
    'Requires trusted extension btree_gist. 10 CHECK constraints encode business rules. ' ||
    'tstzrange [from,to) for valid_period. Index on code is intentionally NON-UNIQUE.'
);
