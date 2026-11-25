-- =====================================================================
-- V16__create_dashboard_views.sql
-- View + materialized view cho Dashboard & Reports (F07). Không tạo bảng.
--
-- Bao gồm:
--   - app.business_timezone(): múi giờ của cửa hàng, nơi DUY NHẤT trong DB quyết định
--     "ngày nghiệp vụ" của báo cáo. Phải khớp app.business.timezone của app.
--   - app.v_completed_orders: đơn được tính doanh thu (PAID / SHIPPED / DELIVERED)
--     + cột order_segment theo total.
--   - app.mv_daily_revenue: pre-aggregate theo ngày nghiệp vụ — số đơn theo từng status,
--     doanh thu của đơn được tính, tiền đã hoàn của các đơn đó.
--   - UNIQUE INDEX (day): điều kiện bắt buộc của REFRESH MATERIALIZED VIEW CONCURRENTLY.
--
-- Đặc trưng:
--   - Ngày = (created_at AT TIME ZONE app.business_timezone())::date — KHÔNG dùng
--     date_trunc('day', created_at): kết quả của nó đổi theo TimeZone của session chạy
--     REFRESH (pgjdbc lấy theo TZ của JVM) → cùng dữ liệu, máy khác ra số khác.
--   - Lưu SUM + COUNT, không lưu AVG: trung bình của các trung bình theo ngày không phải
--     trung bình của tuần / tháng.
--   - FILTER (WHERE ...) trong aggregate thay cho SUM(CASE WHEN ...).
--   - Giữ toàn bộ lịch sử: mỗi ngày 1 row, 10 năm ~3.650 row.
--
-- Tiền điều kiện: V12 (orders), V15 (payments, payment_refunds).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Múi giờ nghiệp vụ của báo cáo
-- ---------------------------------------------------------------------
-- IMMUTABLE + thân là một hằng → planner gập thành hằng số (không có lời gọi hàm lúc
-- chạy). Đổi múi giờ cửa hàng = migration mới: CREATE OR REPLACE FUNCTION rồi
-- REFRESH MATERIALIZED VIEW app.mv_daily_revenue (row cũ vẫn mang ngày theo múi giờ cũ
-- tới khi refresh).
CREATE FUNCTION app.business_timezone()
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
RETURN 'Asia/Ho_Chi_Minh';

-- ---------------------------------------------------------------------
-- 2. Regular view: đơn được tính doanh thu
-- ---------------------------------------------------------------------
-- CANCELLED / EXPIRED / PENDING không phải doanh thu. Đơn PAID rồi mới hủy là CANCELLED
-- (vẫn giữ paid_at) → cũng bị loại; tiền đã thu của nó là việc của đối soát (F06).
CREATE VIEW app.v_completed_orders AS
SELECT
    o.id,
    o.order_number,
    o.customer_id,
    o.coupon_id,
    o.subtotal,
    o.discount_amount,
    o.shipping_fee,
    o.total,
    o.status,
    o.created_at,
    o.paid_at,
    o.shipped_at,
    o.delivered_at,
    CASE
        WHEN o.total <  500000 THEN 'SMALL'
        WHEN o.total < 2000000 THEN 'MEDIUM'
        WHEN o.total < 5000000 THEN 'LARGE'
        ELSE 'VIP'
    END AS order_segment
FROM app.orders o
WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED');

-- ---------------------------------------------------------------------
-- 3. Materialized view: doanh thu theo ngày nghiệp vụ
-- ---------------------------------------------------------------------
CREATE MATERIALIZED VIEW app.mv_daily_revenue AS
WITH refunded AS (
    -- Gộp theo order TRƯỚC khi join: join thẳng payment_refunds thì order có 2 refund
    -- xuất hiện 2 lần → total của nó bị cộng 2 lần.
    SELECT p.order_id, SUM(r.amount) AS amount
    FROM app.payment_refunds r
    JOIN app.payments p ON p.id = r.payment_id
    WHERE r.status = 'COMPLETED'
    GROUP BY p.order_id
),
base AS (
    SELECT
        (o.created_at AT TIME ZONE app.business_timezone())::date AS day,
        o.status,
        -- Cùng điều kiện với app.v_completed_orders.
        o.status IN ('PAID', 'SHIPPED', 'DELIVERED')              AS counted,
        o.subtotal,
        o.discount_amount,
        o.shipping_fee,
        o.total,
        rf.amount                                                 AS refunded
    FROM app.orders o
    LEFT JOIN refunded rf ON rf.order_id = o.id
)
SELECT
    day,
    -- Số đơn đặt trong ngày, mọi status; 6 cột status cộng lại = order_count.
    COUNT(*)                                                    AS order_count,
    COUNT(*) FILTER (WHERE status = 'PENDING')                  AS pending_count,
    COUNT(*) FILTER (WHERE status = 'PAID')                     AS paid_count,
    COUNT(*) FILTER (WHERE status = 'SHIPPED')                  AS shipped_count,
    COUNT(*) FILTER (WHERE status = 'DELIVERED')                AS delivered_count,
    COUNT(*) FILTER (WHERE status = 'CANCELLED')                AS cancelled_count,
    COUNT(*) FILTER (WHERE status = 'EXPIRED')                  AS expired_count,
    -- Doanh thu: chỉ đơn được tính. Ngày chỉ có đơn hủy thì SUM ... FILTER = NULL →
    -- COALESCE về 0.00 (scale 2 như mọi giá trị khác của cột).
    COUNT(*) FILTER (WHERE counted)                             AS completed_count,
    COALESCE(SUM(subtotal)        FILTER (WHERE counted), 0.00) AS gross_subtotal,
    COALESCE(SUM(discount_amount) FILTER (WHERE counted), 0.00) AS total_discount,
    COALESCE(SUM(shipping_fee)    FILTER (WHERE counted), 0.00) AS total_shipping,
    COALESCE(SUM(total)           FILTER (WHERE counted), 0.00) AS net_revenue,
    COALESCE(SUM(refunded)        FILTER (WHERE counted), 0.00) AS refunded_amount
FROM base
GROUP BY day
WITH DATA;

-- UNIQUE, không WHERE, trên cột thường — thiếu thì REFRESH ... CONCURRENTLY báo lỗi
-- 55000. Cũng là index cho truy vấn theo khoảng ngày.
CREATE UNIQUE INDEX uq_mv_daily_revenue_day
    ON app.mv_daily_revenue (day);

-- ---------------------------------------------------------------------
-- 4. Comments
-- ---------------------------------------------------------------------
COMMENT ON FUNCTION app.business_timezone() IS
    'Múi giờ cửa hàng cho báo cáo theo ngày / tháng. Phải khớp app.business.timezone. '
    'Đổi = migration mới + REFRESH MATERIALIZED VIEW app.mv_daily_revenue.';
COMMENT ON VIEW app.v_completed_orders IS
    'Đơn được tính doanh thu: PAID / SHIPPED / DELIVERED. Loại PENDING, CANCELLED (kể cả hủy sau khi đã PAID), '
    'EXPIRED. order_segment: SMALL < 500k ≤ MEDIUM < 2M ≤ LARGE < 5M ≤ VIP (theo total).';
COMMENT ON MATERIALIZED VIEW app.mv_daily_revenue IS
    'Doanh thu theo ngày nghiệp vụ (app.business_timezone()) của created_at, toàn bộ lịch sử. '
    'Làm mới bằng REFRESH MATERIALIZED VIEW CONCURRENTLY (không chặn SELECT) — dữ liệu trễ tới lần refresh kế tiếp.';
COMMENT ON COLUMN app.mv_daily_revenue.day IS
    'Ngày theo app.business_timezone(), không phải theo TimeZone của session.';
COMMENT ON COLUMN app.mv_daily_revenue.order_count IS
    'Mọi đơn đặt trong ngày. = pending + paid + shipped + delivered + cancelled + expired.';
COMMENT ON COLUMN app.mv_daily_revenue.completed_count IS
    'Số đơn được tính doanh thu (như app.v_completed_orders). AOV = net_revenue / completed_count.';
COMMENT ON COLUMN app.mv_daily_revenue.net_revenue IS
    'SUM(total) của đơn được tính = gross_subtotal - total_discount + total_shipping. CHƯA trừ hoàn tiền.';
COMMENT ON COLUMN app.mv_daily_revenue.refunded_amount IS
    'Refund COMPLETED của các đơn được tính, theo ngày đặt đơn. Sau hoàn tiền = net_revenue - refunded_amount.';

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V16__create_dashboard_views',
    'Created app.business_timezone() (Asia/Ho_Chi_Minh), app.v_completed_orders (PAID/SHIPPED/DELIVERED ' ||
    '+ order_segment) and app.mv_daily_revenue (business day, per-status counts, revenue of completed ' ||
    'orders, completed refunds; SUM/COUNT only, full history) with UNIQUE (day) for REFRESH CONCURRENTLY. ' ||
    'No new tables.'
);
