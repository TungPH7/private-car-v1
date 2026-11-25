-- =====================================================================
-- V3__create_products_table.sql
-- Tạo bảng business đầu tiên: app.products.
--   - PK kiểu UUIDv7 (DEFAULT app.gen_uuid_v7() — fallback cho INSERT
--     không qua JPA; JPA tự sinh ID ở @PrePersist).
--   - 5 audit cột (created_at, updated_at, created_by, updated_by, version)
--     do BaseEntity quản — DEFAULT giá trị an toàn cho non-JPA INSERT.
--   - Trigger BEFORE UPDATE gắn app.set_updated_at() (V2) → đảm bảo
--     updated_at luôn mới, kể cả khi UPDATE bypass JPA.
--
-- Tiền điều kiện:
--   - V1 (init) đã chạy: schema 'app' tồn tại, 4 extension active.
--   - V2 (app helpers) đã chạy: app.gen_uuid_v7(), app.set_updated_at().
--
-- Lưu ý về schema-qualification:
--   JDBC URL có 'currentSchema=app' → search_path khi Flyway chạy = chỉ 'app'
--   (KHÔNG kèm 'public'). Mọi type/function ngoài schema 'app' phải qualify
--   schema tường minh, vd `public.citext`, vì `citext` được cài vào schema
--   'public' ở V1. Built-in type (VARCHAR, TIMESTAMPTZ, ...) ở pg_catalog —
--   luôn ngầm trong search_path nên không cần qualify.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.products (
    -- PK
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    -- Cột nghiệp vụ
    name            VARCHAR(255)    NOT NULL,
    slug            public.CITEXT   NOT NULL,         -- citext sống ở schema public, phải qualify
    price           NUMERIC(12, 2)  NOT NULL,
    description     TEXT,

    -- Cột audit (do BaseEntity quản — DEFAULT làm fallback non-JPA)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    -- Constraint nghiệp vụ
    CONSTRAINT products_price_non_negative CHECK (price >= 0),
    CONSTRAINT products_slug_unique        UNIQUE (slug)
);

-- ---------------------------------------------------------------------
-- 2. Comments — tài liệu inline
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.products              IS 'Catalog product — entity nghiệp vụ đầu tiên dùng để verify BaseEntity + auditing + trigger pipeline.';
COMMENT ON COLUMN app.products.id           IS 'UUIDv7 (RFC 9562 §5.7) — sinh ở Java @PrePersist hoặc DB DEFAULT app.gen_uuid_v7().';
COMMENT ON COLUMN app.products.slug         IS 'URL-friendly identifier, case-insensitive (public.citext). UNIQUE.';
COMMENT ON COLUMN app.products.price        IS 'Giá USD, numeric(12,2). KHÔNG dùng float/double cho tiền.';
COMMENT ON COLUMN app.products.created_at   IS 'Lúc row được tạo. JPA @CreatedDate. DEFAULT now() làm fallback.';
COMMENT ON COLUMN app.products.updated_at   IS 'Lúc row được cập nhật cuối. JPA @LastModifiedDate + trigger app.set_updated_at() (defense-in-depth).';
COMMENT ON COLUMN app.products.version      IS 'Optimistic locking @Version. Hibernate inject WHERE version=? cho UPDATE.';

-- ---------------------------------------------------------------------
-- 3. Index
-- ---------------------------------------------------------------------
-- Index UNIQUE trên slug đã tự tạo bởi UNIQUE constraint ở trên — không cần CREATE INDEX riêng.
--
-- Index trên created_at (descending) để hỗ trợ query "list products mới nhất" — phổ biến.
-- DESC để index match query ORDER BY created_at DESC LIMIT N.
CREATE INDEX idx_products_created_at_desc
    ON app.products (created_at DESC);

-- ---------------------------------------------------------------------
-- 4. Trigger: gắn app.set_updated_at() (V2) cho BEFORE UPDATE
-- ---------------------------------------------------------------------
-- Đảm bảo updated_at luôn refresh khi UPDATE — kể cả khi:
--   - JPQL @Modifying UPDATE (bypass @LastModifiedDate listener).
--   - Native query.
--   - psql thẳng vào DB.
--   - Migration data update.
CREATE TRIGGER trg_products_set_updated_at
    BEFORE UPDATE ON app.products
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

COMMENT ON TRIGGER trg_products_set_updated_at ON app.products IS
    'Defense-in-depth cho updated_at: bất kỳ UPDATE nào (JPA hoặc thẳng SQL) đều được set updated_at = now().';

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V3__create_products_table',
    'Created app.products with UUIDv7 PK, 5 audit columns, public.citext slug UNIQUE, '
    'numeric(12,2) price CHECK >= 0, trigger trg_products_set_updated_at gắn app.set_updated_at().'
);