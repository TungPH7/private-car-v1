-- =====================================================================
-- V17__create_search_normalize_function.sql
-- Chuẩn hoá chuỗi cho tìm sản phẩm (F08): không phân biệt dấu, không phân biệt hoa thường.
--
-- Bao gồm:
--   - Extension unaccent: bỏ dấu ("Ốp lưng ĐEN" → "Op lung DEN").
--   - app.search_normalize(text) = lower(unaccent(text)): "Ốp lưng ĐEN" → "op lung den".
--     Index trigram của V18 dựng trên biểu thức này; câu tìm (bài 02) so sánh đúng biểu thức đó.
--
-- Vì sao phải chuẩn hoá: DB được initdb với --locale=C (.env) → LC_CTYPE = C không coi chữ có
--   dấu tiếng Việt là chữ cái. pg_trgm tách "Điện" thành "i" và "n", lower('Đ') vẫn là 'Đ',
--   ILIKE chỉ bỏ qua hoa thường của chữ ASCII. Bỏ dấu TRƯỚC rồi mới hạ chữ thường → kết quả
--   toàn ASCII, trigram và LIKE cho cùng một đáp số ở mọi locale.
--
-- Không có index ở đây: index trên bảng đã có dữ liệu tạo CONCURRENTLY ở V18 — lệnh đó không
--   chạy được trong transaction, còn migration này cần transaction (extension + hàm: một là
--   cả hai, hai là không gì).
--
-- Tiền điều kiện: pg_trgm (init script, V1 kiểm) — V18 dùng.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Extension unaccent
-- ---------------------------------------------------------------------
-- Chạy được bởi app role non-superuser: unaccent.control có `trusted = true` (như btree_gist
-- ở V10). Đặt ở migration chứ không ở init script vì init script chỉ chạy khi volume rỗng — DB
-- đã có V1..V16 sẽ không bao giờ nhận được extension (lý do đầy đủ ở V10).
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

-- ---------------------------------------------------------------------
-- 2. Hàm chuẩn hoá
-- ---------------------------------------------------------------------
-- IMMUTABLE: bắt buộc để dùng trong index. unaccent() bản thân là STABLE (đọc từ điển
-- unaccent lúc chạy); gọi bản hai tham số với từ điển chỉ đích danh làm kết quả chỉ phụ thuộc
-- input — đổi file unaccent.rules (nâng Postgres) thì phải REINDEX index của V18.
--
-- BEGIN ATOMIC (SQL chuẩn, PG 14+): thân hàm được phân tích và gắn object ngay lúc CREATE,
-- không đọc search_path lúc chạy. Từ PG 17, CREATE INDEX / REINDEX / ANALYZE / VACUUM chạy hàm
-- của index với search_path = pg_catalog, pg_temp — thân hàm dạng chuỗi gọi `unaccent(...)` không
-- kèm schema chạy được trong query thường nhưng làm CREATE INDEX lỗi
-- "function unaccent(text) does not exist".
--
-- lower() SAU unaccent(): lower('Đ') = 'Đ' với LC_CTYPE = C, unaccent('Đ') = 'D'.
-- STRICT: NULL vào → NULL ra, không gọi thân hàm.
CREATE FUNCTION app.search_normalize(input text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE STRICT
BEGIN ATOMIC
    SELECT lower(public.unaccent('public.unaccent'::regdictionary, input));
END;

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON FUNCTION app.search_normalize(text) IS
    'Chuẩn hoá để tìm kiếm: lower(unaccent(text)) — "Ốp lưng ĐEN" → "op lung den". IMMUTABLE, '
    'dùng trong idx_products_name_trgm (V18). Câu tìm phải so sánh đúng biểu thức '
    'app.search_normalize(name) thì mới dùng được index.';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V17__create_search_normalize_function',
    'Created extension unaccent (trusted) and app.search_normalize(text) = lower(unaccent(text)), ' ||
    'IMMUTABLE, BEGIN ATOMIC body (no search_path dependency). Index on it comes in V18.'
);
