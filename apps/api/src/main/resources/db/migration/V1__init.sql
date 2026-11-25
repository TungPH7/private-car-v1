-- =====================================================================
-- V1__init.sql
-- Baseline migration — verify schema 'app' và extensions đã được tạo
-- bởi init script docker (folder 02). KHÔNG tạo bảng business ở đây.
--
-- Tiền điều kiện (từ docker/postgres/init/00-load-env.sh):
--   - Schema 'app' tồn tại, owner = learn_postgres_user.
--   - Extension uuid-ossp, pgcrypto, citext, pg_trgm đã CREATE.
--   - search_path của learn_postgres_user = "app, public".
--
-- Migration này:
--   1. Assert schema & extensions hiện diện (fail-fast nếu thiếu).
--   2. Tạo bảng metadata `_app_migration_log` để demo Flyway hoạt động
--      (nội bộ — không phải bảng business).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Assert tiền điều kiện
-- ---------------------------------------------------------------------

DO $$
BEGIN
    -- Schema 'app' phải tồn tại
    IF NOT EXISTS (
        SELECT 1 FROM pg_namespace WHERE nspname = 'app'
    ) THEN
        RAISE EXCEPTION
            'Schema "app" missing. Run init script in folder 02 first '
            '(docker compose down -v && docker compose up -d).';
    END IF;

    -- Extensions phải có
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'uuid-ossp') THEN
        RAISE EXCEPTION 'Required extension "uuid-ossp" missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto') THEN
        RAISE EXCEPTION 'Required extension "pgcrypto" missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'citext') THEN
        RAISE EXCEPTION 'Required extension "citext" missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        RAISE EXCEPTION 'Required extension "pg_trgm" missing.';
    END IF;

    RAISE NOTICE 'V1__init: prerequisites OK (schema "app" + 4 extensions).';
END $$;

-- ---------------------------------------------------------------------
-- 2. Bảng metadata nội bộ — chứng minh Flyway đã apply migration thành công
-- ---------------------------------------------------------------------

-- Bảng nội bộ track thay đổi schema "có context người" (text), tách biệt
-- với flyway_schema_history (technical, do Flyway quản).
CREATE TABLE IF NOT EXISTS app._app_migration_log (
    id              BIGSERIAL PRIMARY KEY,
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    migration_name  TEXT        NOT NULL,
    notes           TEXT
);

COMMENT ON TABLE  app._app_migration_log IS
    'Internal log of significant schema changes — human-readable companion to flyway_schema_history.';
COMMENT ON COLUMN app._app_migration_log.migration_name IS
    'Tương ứng filename Flyway migration (ví dụ: V1__init).';

-- Insert dòng đầu tiên cho V1
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V1__init',
    'Baseline migration: verified schema "app" + extensions (uuid-ossp, pgcrypto, citext, pg_trgm).'
);