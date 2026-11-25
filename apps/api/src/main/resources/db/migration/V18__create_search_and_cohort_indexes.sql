-- =====================================================================
-- V18__create_search_and_cohort_indexes.sql
-- Hai index trên bảng đã có dữ liệu (F08), tạo CONCURRENTLY.
--
-- Bao gồm:
--   - idx_products_name_trgm: GIN trigram trên app.search_normalize(name) (V17) — tìm sản phẩm
--     theo tên bằng LIKE '%...%' / word_similarity, không phân biệt dấu / hoa thường.
--   - idx_customers_created_at: B-tree trên customers.created_at — cohort retention (F07) tìm khách
--     đăng ký trong một tháng bằng index thay vì quét cả bảng customers.
--
-- Đặc trưng:
--   - CREATE INDEX CONCURRENTLY: build trong khi bảng vẫn nhận INSERT / UPDATE. CREATE INDEX thường
--     giữ ShareLock suốt lúc build — mọi lệnh ghi vào bảng đứng chờ.
--   - Chạy KHÔNG trong transaction (V18__create_search_and_cohort_indexes.sql.conf:
--     executeInTransaction=false) — CONCURRENTLY không chạy được trong transaction block. Mỗi lệnh
--     tự commit: lỗi giữa chừng để lại một nửa migration, có thể kèm index INVALID. Vì vậy mỗi
--     index DROP ... IF EXISTS ngay trước khi tạo: sau `flyway repair`, chạy lại V18 từ đầu cho ra
--     đúng trạng thái. (CREATE INDEX CONCURRENTLY IF NOT EXISTS thì không: nó bỏ qua cả index
--     INVALID còn sót lại.)
--   - Cần spring.flyway.postgresql.transactional-lock=false (application.yml): khóa advisory dạng
--     transaction của Flyway giữ một snapshot mở; CONCURRENTLY phải chờ mọi snapshot cũ hơn nó
--     → chờ chính Flyway, tới khi socketTimeout cắt kết nối.
--
-- Tiền điều kiện: V17 (app.search_normalize), pg_trgm (init script).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tìm sản phẩm theo tên
-- ---------------------------------------------------------------------
-- GIN chứ không GiST: GIN nhỏ hơn và lọc nhanh hơn cho LIKE / % / <%; GiST chỉ hơn khi cần
-- sắp theo khoảng cách (ORDER BY ... <-> ... LIMIT). Trên tên gốc (`name gin_trgm_ops`) thì
-- vô dụng với tiếng Việt khi LC_CTYPE = C — xem V17.
DROP INDEX CONCURRENTLY IF EXISTS app.idx_products_name_trgm;
CREATE INDEX CONCURRENTLY idx_products_name_trgm
    ON app.products USING gin (app.search_normalize(name) gin_trgm_ops);

-- ---------------------------------------------------------------------
-- 2. Cohort
-- ---------------------------------------------------------------------
-- idx_customers_status_created_at (V6) bắt đầu bằng status — không dùng được cho điều kiện chỉ
-- trên created_at (PG 17 chưa có skip scan cho B-tree).
DROP INDEX CONCURRENTLY IF EXISTS app.idx_customers_created_at;
CREATE INDEX CONCURRENTLY idx_customers_created_at
    ON app.customers (created_at);

-- ---------------------------------------------------------------------
-- 3. Comments
-- ---------------------------------------------------------------------
COMMENT ON INDEX app.idx_products_name_trgm IS
    'GIN trigram trên app.search_normalize(name): LIKE ''%...%'', %, <% không phân biệt dấu / '
    'hoa thường. Câu tìm phải dùng đúng biểu thức app.search_normalize(name).';
COMMENT ON INDEX app.idx_customers_created_at IS
    'Khách đăng ký trong một khoảng thời gian — cohort retention (F07).';

-- ---------------------------------------------------------------------
-- 4. Log
-- ---------------------------------------------------------------------
-- Không có transaction bao quanh: chạy lại sau `flyway repair` không được ghi log lần hai.
INSERT INTO app._app_migration_log (migration_name, notes)
SELECT 'V18__create_search_and_cohort_indexes',
       'Created CONCURRENTLY (non-transactional migration): idx_products_name_trgm (GIN trigram on ' ||
       'app.search_normalize(name)) and idx_customers_created_at (B-tree). Each index is dropped ' ||
       'first so a re-run after flyway repair converges.'
WHERE NOT EXISTS (
    SELECT 1 FROM app._app_migration_log
    WHERE migration_name = 'V18__create_search_and_cohort_indexes'
);
