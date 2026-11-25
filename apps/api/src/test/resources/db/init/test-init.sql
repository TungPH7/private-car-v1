-- =====================================================================
-- Test-only DB init script — mô phỏng `docker/postgres/init/00-load-env.sh`
-- của production, nhưng đơn giản hoá cho Testcontainers.
--
-- Chạy bởi `PostgreSQLContainer.withInitScript("db/init/test-init.sql")`:
--   PostgreSQL container ready  →  script này chạy  →  Flyway migrate
--   (Flyway V1__init.sql assert các extensions + schema bên dưới).
--
-- Khác với production init script:
--   - KHÔNG tạo app role riêng (test dùng default 'test' superuser).
--   - KHÔNG cấp default privileges (test user đã có ALL).
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
-- Cần container chạy với shared_preload_libraries=pg_stat_statements (AbstractContainerBaseTest)
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

CREATE SCHEMA IF NOT EXISTS app;

-- Search path cho session của user 'test' — Hibernate/Flyway queries thấy schema 'app' mặc định.
ALTER ROLE test SET search_path TO app, public;
