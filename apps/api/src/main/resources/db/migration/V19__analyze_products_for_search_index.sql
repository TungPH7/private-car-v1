-- =====================================================================
-- V19__analyze_products_for_search_index.sql
-- Thu thống kê cho biểu thức của idx_products_name_trgm (V18) — F08.
--
-- ANALYZE một bảng có index biểu thức thì thu luôn thống kê của chính biểu thức đó (pg_stats,
--   tablename = tên index). CREATE INDEX thì không: ngay sau V18, planner chưa có thống kê nào
--   cho app.search_normalize(name) và ước lượng mọi điều kiện %> bằng hằng số mặc định của
--   matchingsel (hàm ước lượng pg_trgm dùng) — 1% bảng, từ khoá nào cũng như nhau.
--
-- Vì sao đáng một migration: bảng nào rồi cũng được ANALYZE — autovacuum khi số row đổi từ lần
--   trước vượt autovacuum_analyze_threshold + autovacuum_analyze_scale_factor × số row (mặc định
--   50 + 10%), hay một lần ANALYZE tay — và thống kê đổi plan của câu tìm. Không có V19, chuyện đó
--   xảy ra vào một lúc không ai chọn (catalog ít sửa: có thể rất lâu). V19 cho trạng thái có thống
--   kê ngay lúc deploy — trạng thái đã đo, cùng plan_cache_mode = force_custom_plan của câu tìm.
--   Không phải để nhanh hơn: ước lượng lấy từ mẫu, RELEVANCE từ ngang tới nhanh 1,4 lần tuỳ từ
--   khoá và tuỳ mẫu, NEWEST chậm hơn 1–8%. Số đo trên 100.000 sản phẩm:
--   F08__search-performance/06-verify-and-pr.md §10.
--
-- Đặc trưng:
--   - Trong transaction của migration (mặc định của Flyway): thống kê mới có hiệu lực khi commit.
--   - Khóa SHARE UPDATE EXCLUSIVE: không chặn đọc / ghi, chỉ chặn VACUUM / ANALYZE / phần lớn
--     DDL trên bảng.
--   - Đọc một mẫu row có giới hạn (300 × default_statistics_target) — thời gian không tăng theo
--     kích thước bảng như VACUUM.
--   - Chỉ chạy một lần. Index dựng lại bằng DROP + CREATE (V18 chạy lại sau repair, hay tay) là
--     index mới, chưa có thống kê — chạy lại ANALYZE app.products.
--
-- Tiền điều kiện: V18 (idx_products_name_trgm).
-- =====================================================================

ANALYZE app.products;

-- ---------------------------------------------------------------------
-- Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V19__analyze_products_for_search_index',
    'ANALYZE app.products: collects statistics for the expression of idx_products_name_trgm ' ||
    '(V18) — CREATE INDEX does not; without them every %> condition is estimated at 1%.'
);
