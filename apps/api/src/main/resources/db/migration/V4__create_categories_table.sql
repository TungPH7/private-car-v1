-- =====================================================================
-- V4__create_categories_table.sql
-- Tạo bảng app.categories — entity nghiệp vụ thứ 2 (sau products).
--
-- Đặc trưng:
--   - Self-referencing FK: parent_id → id (cây thư mục n cấp).
--   - Cycle prevention: CHECK 1-level + trigger walk-up ancestors.
--   - Cột audit chuẩn (do BaseEntity quản — DEFAULT làm fallback non-JPA).
--   - Trigger BEFORE UPDATE gắn app.set_updated_at() (V2).
--
-- Tiền điều kiện:
--   - V1, V2, V3 đã apply.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tạo bảng
-- ---------------------------------------------------------------------
CREATE TABLE app.categories (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    -- Nghiệp vụ
    parent_id       UUID            NULL,
    name            VARCHAR(160)    NOT NULL,
    slug            public.CITEXT   NOT NULL,
    description     TEXT,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order      INTEGER         NOT NULL DEFAULT 0,

    -- Audit (BaseEntity quản)
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    -- Constraints
    CONSTRAINT categories_parent_id_fkey
        FOREIGN KEY (parent_id) REFERENCES app.categories(id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT categories_slug_unique         UNIQUE (slug),
    CONSTRAINT ck_categories_name_non_blank   CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_categories_self_parent      CHECK (parent_id IS DISTINCT FROM id),
    CONSTRAINT ck_categories_sort_order_non_neg CHECK (sort_order >= 0)
);

-- ---------------------------------------------------------------------
-- 2. Comments tài liệu
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.categories              IS 'Cây phân loại sản phẩm. parent_id self-ref; cycle chặn bằng CHECK + trigger.';
COMMENT ON COLUMN app.categories.parent_id    IS 'NULL = root category. Cycle prevention bởi trigger trg_categories_no_cycle.';
COMMENT ON COLUMN app.categories.slug         IS 'URL-friendly identifier, case-insensitive (public.citext). UNIQUE.';
COMMENT ON COLUMN app.categories.is_active    IS 'Soft archive. FALSE = ẩn khỏi catalog nhưng giữ data.';
COMMENT ON COLUMN app.categories.sort_order   IS 'Thứ tự sort trong cùng cấp, ASC.';

-- ---------------------------------------------------------------------
-- 3. Index
-- ---------------------------------------------------------------------
-- Postgres KHÔNG tự index FK → bắt buộc index parent_id để listChildren nhanh.
CREATE INDEX idx_categories_parent_id     ON app.categories (parent_id);

-- Composite cho query "list active, sort theo sort_order".
CREATE INDEX idx_categories_active_sort   ON app.categories (is_active, sort_order);

-- ---------------------------------------------------------------------
-- 4. Trigger updated_at (defense-in-depth cho JPA Auditing)
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_categories_set_updated_at
    BEFORE UPDATE ON app.categories
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

COMMENT ON TRIGGER trg_categories_set_updated_at ON app.categories IS
    'Defense-in-depth: bất kỳ UPDATE nào (JPA / native / psql) đều set updated_at = now().';

-- ---------------------------------------------------------------------
-- 5. Cycle prevention trigger
-- ---------------------------------------------------------------------
-- CHECK constraint không "thấy" được cycle dài (vì 1 row không biết ancestor).
-- Trigger walk-up ancestors khi INSERT hoặc UPDATE parent_id.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.check_category_no_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_ancestor UUID := NEW.parent_id;
    v_depth    INT  := 0;
    v_max_depth CONSTANT INT := 32;
BEGIN
    -- Walk lên ancestor; nếu gặp lại NEW.id → cycle.
    WHILE v_ancestor IS NOT NULL LOOP
        IF v_depth >= v_max_depth THEN
            RAISE EXCEPTION 'CAT_CYCLE_DETECTED: depth > % when checking ancestor of %', v_max_depth, NEW.id
                USING ERRCODE = 'check_violation';
        END IF;

        IF v_ancestor = NEW.id THEN
            RAISE EXCEPTION 'CAT_CYCLE_DETECTED: moving % under % creates a cycle', NEW.id, NEW.parent_id
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT parent_id INTO v_ancestor
        FROM app.categories
        WHERE id = v_ancestor;

        v_depth := v_depth + 1;
    END LOOP;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION app.check_category_no_cycle() IS
    'Walk up ancestors qua parent_id; raise CAT_CYCLE_DETECTED nếu trở lại NEW.id. Depth limit 32 để tránh runaway nếu data dirty.';

-- Trigger chỉ chạy khi INSERT (parent_id mới) hoặc khi UPDATE parent_id (đổi cha).
-- KHÔNG chạy khi update các cột khác như name, slug → tiết kiệm cost.
CREATE TRIGGER trg_categories_no_cycle
    BEFORE INSERT OR UPDATE OF parent_id ON app.categories
    FOR EACH ROW
    WHEN (NEW.parent_id IS NOT NULL)
    EXECUTE FUNCTION app.check_category_no_cycle();

COMMENT ON TRIGGER trg_categories_no_cycle ON app.categories IS
    'BEFORE INSERT OR UPDATE OF parent_id — chặn cycle. Skip khi parent_id NULL (root).';

-- ---------------------------------------------------------------------
-- 6. Log migration
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V4__create_categories_table',
    'Created app.categories with self-ref FK, cycle prevention trigger, 2 indexes (parent_id, active_sort).'
);
