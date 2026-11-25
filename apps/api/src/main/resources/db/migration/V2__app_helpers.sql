-- =====================================================================
-- V2__app_helpers.sql
-- DB-side helpers dùng chung cho toàn bộ entity về sau:
--   1) app.gen_uuid_v7()  — sinh UUIDv7 (RFC 9562 § 5.7) native Postgres.
--   2) app.set_updated_at() — trigger function fill NEW.updated_at = now()
--                             BEFORE UPDATE (defense-in-depth cho JPA Auditing).
--
-- Tiền điều kiện (đã thỏa từ V1):
--   - Schema 'app' tồn tại.
--   - Extension pgcrypto đã CREATE (cần gen_random_bytes).
--   - search_path = "app, public".
--
-- Idempotent: dùng CREATE OR REPLACE FUNCTION → re-run an toàn (Flyway
-- bình thường không re-run V2, nhưng nếu lỡ chạy thủ công cũng không vỡ).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. UUIDv7 generator
-- ---------------------------------------------------------------------
-- RFC 9562 § 5.7:
--   bytes 0..5  = unix_ts_ms (48 bit, big-endian)
--   byte  6     = (version=7 << 4) | (random & 0x0F)   ; version 4 bit cao
--   byte  8     = (variant=2 << 6) | (random & 0x3F)   ; variant '10' 2 bit cao
--   bytes còn lại = random (đã có sẵn từ gen_random_bytes).
--
-- VOLATILE: clock_timestamp() & gen_random_bytes() đều non-deterministic.
-- Không đánh dấu IMMUTABLE — sẽ sai semantics.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.gen_uuid_v7()
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    v_ts_ms bigint;
    v_bytes bytea;
BEGIN
    v_ts_ms := (extract(epoch from clock_timestamp()) * 1000)::bigint;
    v_bytes := gen_random_bytes(16);

    -- 6 byte đầu = unix_ts_ms big-endian
    v_bytes := set_byte(v_bytes, 0, ((v_ts_ms >> 40) & 255)::int);
    v_bytes := set_byte(v_bytes, 1, ((v_ts_ms >> 32) & 255)::int);
    v_bytes := set_byte(v_bytes, 2, ((v_ts_ms >> 24) & 255)::int);
    v_bytes := set_byte(v_bytes, 3, ((v_ts_ms >> 16) & 255)::int);
    v_bytes := set_byte(v_bytes, 4, ((v_ts_ms >>  8) & 255)::int);
    v_bytes := set_byte(v_bytes, 5, ( v_ts_ms        & 255)::int);

    -- Byte 6: version = 7 (4 bit cao = 0111)
    v_bytes := set_byte(v_bytes, 6,
        ((get_byte(v_bytes, 6) & 15) | (7 << 4))::int);

    -- Byte 8: variant = '10' (2 bit cao)
    v_bytes := set_byte(v_bytes, 8,
        ((get_byte(v_bytes, 8) & 63) | 128)::int);

    RETURN encode(v_bytes, 'hex')::uuid;
END;
$$;

COMMENT ON FUNCTION app.gen_uuid_v7() IS
    'Generate UUIDv7 (RFC 9562 §5.7) — 48-bit ms timestamp prefix + random. '
    'Dùng làm DEFAULT cho cột PK của entity nghiệp vụ.';

-- ---------------------------------------------------------------------
-- 2. Trigger function: set NEW.updated_at = now() BEFORE UPDATE
-- ---------------------------------------------------------------------
-- Đây là "lưới an toàn" cho JPA Auditing:
--   - JPA @LastModifiedDate fill updated_at khi save() qua repository.
--   - Bulk update qua JPQL @Modifying / native query / psql thẳng → JPA
--     listener KHÔNG fire → updated_at stale.
--   - Trigger BEFORE UPDATE đảm bảo updated_at luôn = now() ở DB-side,
--     bất kể ai/cái gì update.
--
-- Apply trigger cho từng bảng business ở migration tương ứng (V3+).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION app.set_updated_at() IS
    'BEFORE UPDATE trigger function — set NEW.updated_at = now(). '
    'Attach vào mỗi bảng business: '
    'CREATE TRIGGER trg_<table>_set_updated_at BEFORE UPDATE ON app.<table> '
    'FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();';

-- ---------------------------------------------------------------------
-- 3. Log vào _app_migration_log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V2__app_helpers',
    'Created app.gen_uuid_v7() (UUIDv7 generator) and app.set_updated_at() '
    '(BEFORE UPDATE trigger function for audit defense-in-depth).'
);