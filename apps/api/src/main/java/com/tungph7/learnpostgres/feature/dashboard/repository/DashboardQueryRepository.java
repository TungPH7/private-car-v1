package com.tungph7.learnpostgres.feature.dashboard.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.common.jdbc.RecordRowMapper;
import com.tungph7.learnpostgres.feature.dashboard.dto.CategoryRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.CohortRetentionResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.CustomerLifetimeValueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.InventoryTurnoverResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderFunnelResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.OrderSegmentResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.PeriodRevenueResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.RevenueGroupingSetResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.RevenueGrowthResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopCategoryResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopCustomerResponse;
import com.tungph7.learnpostgres.feature.dashboard.dto.TopProductResponse;

/**
 * 14 báo cáo của dashboard (F07) — SQL viết tay qua {@link JdbcClient}, kết quả map thẳng vào
 * record của {@code dto}.
 *
 * <p>Không phải Spring Data repository: F07 không có entity. Tham số đặt tên ({@code :from}), không
 * đếm vị trí {@code ?}. Record nhận cột theo <b>tên</b> — alias {@code net_revenue} vào component
 * {@code netRevenue} — nên alias lệch với record thì lỗi ngay ở row đầu tiên, không lặng lẽ đổ nhầm
 * cột. Map qua {@link RecordRowMapper}, không qua {@code query(Class)}: mapper mặc định của {@code
 * JdbcClient} (Spring 7.0.7) ném rồi bắt một {@code SQLException} cho mỗi row, mỗi alias {@code
 * snake_case}.
 *
 * <p>Ba quy ước chung:
 *
 * <ul>
 *   <li><b>Ngày theo lịch của cửa hàng.</b> {@code from} / {@code to} là ngày theo {@code
 *       app.business_timezone()} (V16), lấy cả hai đầu. Lọc bằng khoảng nửa mở trên cột thời gian:
 *       {@code >= from 00:00} và {@code < (to + 1) 00:00} giờ cửa hàng, hai mốc tính trong SQL bằng
 *       {@code ::timestamp AT TIME ZONE app.business_timezone()} — cột không bị bọc trong hàm nên
 *       vẫn dùng được index. Cắt tháng bằng {@code date_trunc('month', created_at AT TIME ZONE
 *       app.business_timezone())}. Không câu nào đọc {@code TimeZone} của session — thứ pgjdbc đặt
 *       theo múi giờ của JVM.
 *   <li><b>Doanh thu = {@code app.v_completed_orders}</b> (PAID / SHIPPED / DELIVERED). Doanh thu
 *       theo ngày / tuần / tháng đọc {@code app.mv_daily_revenue} — cùng định nghĩa, đã gộp sẵn.
 *   <li><b>Chỉ đọc</b>, trừ {@link #refreshDailyRevenue()} và {@link #populateDailyRevenue()}.
 *       Transaction do service quyết định.
 * </ul>
 */
@Repository
public class DashboardQueryRepository {

  private final JdbcClient jdbc;

  public DashboardQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ================================================================
  // 1–3. Doanh thu theo ngày / tuần / tháng — đọc materialized view
  // ================================================================

  /** Doanh thu từng ngày trong {@code [from, to]}. Ngày không có đơn vẫn có row. */
  public List<PeriodRevenueResponse> dailyRevenue(LocalDate from, LocalDate to) {
    return revenueByPeriod("day", from, to);
  }

  /** Doanh thu từng tuần (thứ Hai – Chủ nhật) chạm vào {@code [from, to]}. */
  public List<PeriodRevenueResponse> weeklyRevenue(LocalDate from, LocalDate to) {
    return revenueByPeriod("week", from, to);
  }

  /** Doanh thu 12 tháng của {@code year}. */
  public List<PeriodRevenueResponse> monthlyRevenue(int year) {
    return revenueByPeriod("month", firstDay(year), lastDay(year));
  }

  /**
   * Một câu cho cả ba độ chia: MV lưu SUM + COUNT, nên gộp ngày thành tuần / tháng chỉ là SUM lại
   * theo {@code date_trunc(:grain, ...)}. AOV tính lúc đọc — tổng tiền / tổng số đơn của kỳ.
   *
   * <p>Lịch và {@code date_trunc} chạy trên {@code timestamp} (không múi giờ): đưa thẳng {@code
   * date} vào thì Postgres chọn bản {@code timestamptz}, tính theo {@code TimeZone} của session — ở
   * múi giờ đổi giờ lúc nửa đêm (Havana, Cairo) lịch mất ngày cuối. {@code from > to} → lịch rỗng →
   * list rỗng.
   */
  private List<PeriodRevenueResponse> revenueByPeriod(String grain, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT
                date_trunc(:grain, d.day::timestamp)::date   AS period_start,
                COALESCE(SUM(mv.order_count), 0)::bigint     AS order_count,
                COALESCE(SUM(mv.completed_count), 0)::bigint AS completed_count,
                COALESCE(SUM(mv.gross_subtotal), 0.00)       AS gross_subtotal,
                COALESCE(SUM(mv.total_discount), 0.00)       AS total_discount,
                COALESCE(SUM(mv.total_shipping), 0.00)       AS total_shipping,
                COALESCE(SUM(mv.net_revenue), 0.00)          AS net_revenue,
                COALESCE(SUM(mv.refunded_amount), 0.00)      AS refunded_amount,
                round(SUM(mv.net_revenue) / NULLIF(SUM(mv.completed_count), 0), 2)
                                                             AS avg_order_value
            FROM (
                SELECT g.d::date AS day
                FROM generate_series(:from::timestamp, :to::timestamp, interval '1 day') AS g(d)
            ) d
            LEFT JOIN app.mv_daily_revenue mv ON mv.day = d.day
            GROUP BY 1
            ORDER BY 1
            """)
        .param("grain", grain)
        .param("from", from)
        .param("to", to)
        .query(RecordRowMapper.of(PeriodRevenueResponse.class))
        .list();
  }

  // ================================================================
  // 4. Doanh số theo tháng × category
  // ================================================================

  /**
   * Doanh số hàng theo tháng × category trực tiếp của sản phẩm, năm {@code year}. Chỉ các ô có bán.
   *
   * <p>{@code COUNT(DISTINCT o.id)}: join {@code order_items} nhân mỗi đơn thành N row — một đơn có
   * 2 sản phẩm cùng category thì không có DISTINCT sẽ bị đếm 2 lần.
   */
  public List<CategoryRevenueResponse> monthlyRevenueByCategory(int year) {
    return jdbc.sql(
            """
            SELECT
                date_trunc('month', o.created_at AT TIME ZONE app.business_timezone())::date
                                     AS month,
                c.id                 AS category_id,
                c.name               AS category_name,
                COUNT(DISTINCT o.id) AS order_count,
                SUM(oi.quantity)     AS units_sold,
                SUM(oi.line_total)   AS gross_revenue
            FROM app.v_completed_orders o
            JOIN app.order_items oi    ON oi.order_id = o.id
            JOIN app.products p        ON p.id = oi.product_id
            LEFT JOIN app.categories c ON c.id = p.category_id
            WHERE o.created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
              AND o.created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            GROUP BY 1, c.id
            ORDER BY 1, gross_revenue DESC, c.id
            """)
        .param("from", firstDay(year))
        .param("to", lastDay(year))
        .query(RecordRowMapper.of(CategoryRevenueResponse.class))
        .list();
  }

  // ================================================================
  // 5–6. Top N
  // ================================================================

  /**
   * {@code n} sản phẩm bán nhiều nhất trong {@code [from, to]} — theo số lượng, hòa thì theo doanh
   * số, rồi theo id để thứ tự luôn xác định.
   *
   * <p>{@code GROUP BY p.id} đủ: {@code name}, {@code sku} phụ thuộc hàm vào khóa chính. {@code
   * COUNT(*)} thay cho {@code COUNT(DISTINCT o.id)}: {@code uq_order_items_order_product} bảo đảm
   * mỗi đơn có tối đa một dòng cho một sản phẩm, nên hai cách ra cùng số — DISTINCT chỉ thêm việc
   * sắp xếp.
   */
  public List<TopProductResponse> topNBestSellers(int n, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT
                p.id               AS product_id,
                p.name             AS product_name,
                p.sku              AS sku,
                SUM(oi.quantity)   AS units_sold,
                SUM(oi.line_total) AS revenue,
                COUNT(*)           AS order_count
            FROM app.v_completed_orders o
            JOIN app.order_items oi ON oi.order_id = o.id
            JOIN app.products p     ON p.id = oi.product_id
            WHERE o.created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
              AND o.created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            GROUP BY p.id
            ORDER BY units_sold DESC, revenue DESC, p.id
            LIMIT :n
            """)
        .param("from", from)
        .param("to", to)
        .param("n", n)
        .query(RecordRowMapper.of(TopProductResponse.class))
        .list();
  }

  /**
   * {@code n} khách chi tiêu nhiều nhất trong {@code [from, to]}, chỉ những khách tiêu ít nhất
   * {@code minTotalSpent}.
   *
   * <p>Ngưỡng nằm ở {@code HAVING}, không ở {@code WHERE}: {@code WHERE} lọc từng đơn trước khi gộp
   * (chỉ biết {@code o.total} của một đơn), {@code HAVING} lọc nhóm sau khi gộp (biết tổng của
   * khách).
   */
  public List<TopCustomerResponse> topNCustomersBySpend(
      int n, BigDecimal minTotalSpent, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT
                c.id                               AS customer_id,
                c.email                            AS email,
                c.first_name || ' ' || c.last_name AS full_name,
                COUNT(*)                           AS order_count,
                SUM(o.total)                       AS total_spent,
                round(AVG(o.total), 2)             AS avg_order_value,
                MAX(o.created_at)                  AS last_order_at
            FROM app.v_completed_orders o
            JOIN app.customers c ON c.id = o.customer_id
            WHERE o.created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
              AND o.created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            GROUP BY c.id
            HAVING SUM(o.total) >= :minTotalSpent
            ORDER BY total_spent DESC, c.id
            LIMIT :n
            """)
        .param("from", from)
        .param("to", to)
        .param("minTotalSpent", minTotalSpent)
        .param("n", n)
        .query(RecordRowMapper.of(TopCustomerResponse.class))
        .list();
  }

  // ================================================================
  // 7. Phễu đơn hàng
  // ================================================================

  /**
   * Phễu của mọi đơn đặt trong {@code [from, to]} — một row, kể cả khi khoảng không có đơn.
   *
   * <p>Bước phễu đếm theo mốc thời gian ({@code paid_at IS NOT NULL}...), không theo status: đơn
   * PAID rồi bị hủy đã đi qua bước thanh toán. Đếm theo status thì đơn đó rơi khỏi {@code paid} —
   * tỷ lệ thanh toán thấp hơn thật.
   */
  public OrderFunnelResponse orderFunnel(LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            WITH counts AS (
                SELECT
                    COUNT(*)                                         AS placed,
                    COUNT(*) FILTER (WHERE paid_at IS NOT NULL)      AS paid,
                    COUNT(*) FILTER (WHERE shipped_at IS NOT NULL)   AS shipped,
                    COUNT(*) FILTER (WHERE delivered_at IS NOT NULL) AS delivered,
                    COUNT(*) FILTER (WHERE status = 'PENDING')       AS pending,
                    COUNT(*) FILTER (WHERE status = 'CANCELLED')     AS cancelled,
                    COUNT(*) FILTER (WHERE status = 'CANCELLED' AND paid_at IS NOT NULL)
                                                                     AS cancelled_after_paid,
                    COUNT(*) FILTER (WHERE status = 'EXPIRED')       AS expired
                FROM app.orders
                WHERE created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
                  AND created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            )
            SELECT
                placed, paid, shipped, delivered, pending, cancelled, cancelled_after_paid,
                expired,
                round(100.0 * paid      / NULLIF(placed, 0),  2) AS paid_rate,
                round(100.0 * shipped   / NULLIF(paid, 0),    2) AS shipped_rate,
                round(100.0 * delivered / NULLIF(shipped, 0), 2) AS delivered_rate,
                round(100.0 * cancelled / NULLIF(placed, 0),  2) AS cancel_rate,
                round(100.0 * expired   / NULLIF(placed, 0),  2) AS expire_rate
            FROM counts
            """)
        .param("from", from)
        .param("to", to)
        .query(RecordRowMapper.of(OrderFunnelResponse.class))
        .single();
  }

  // ================================================================
  // 8. Cohort retention
  // ================================================================

  /**
   * Retention của khách đăng ký trong {@code cohortMonth}: tháng thứ 0 (tháng đăng ký) tới tháng
   * thứ {@code months - 1}, mỗi tháng một row kể cả khi không ai mua.
   *
   * <p>Chỉ đọc đơn trong cửa sổ {@code [tháng cohort, + months)}, rồi ghép với lịch tháng theo ngày
   * đầu tháng — không tính offset bằng phép trừ năm / tháng, nên không có offset âm hay vượt cửa
   * sổ. Tháng chưa tới vẫn ra row (0 khách): giới hạn {@code months} theo tháng hiện tại là việc
   * của caller.
   */
  public List<CohortRetentionResponse> customerCohortRetention(YearMonth cohortMonth, int months) {
    return jdbc.sql(
            """
            WITH cohort AS (
                SELECT id AS customer_id
                FROM app.customers
                WHERE created_at >= :cohortMonth::timestamp AT TIME ZONE app.business_timezone()
                  AND created_at <  (:cohortMonth + interval '1 month')
                                        AT TIME ZONE app.business_timezone()
            ),
            activity AS (
                -- (khách, tháng có đơn) — DISTINCT: nhiều đơn trong một tháng tính một lần
                SELECT DISTINCT
                    o.customer_id,
                    date_trunc('month', o.created_at AT TIME ZONE app.business_timezone())::date
                        AS month
                FROM app.v_completed_orders o
                JOIN cohort ch ON ch.customer_id = o.customer_id
                WHERE o.created_at >= :cohortMonth::timestamp AT TIME ZONE app.business_timezone()
                  AND o.created_at <  (:cohortMonth + make_interval(months => :months))
                                          AT TIME ZONE app.business_timezone()
            ),
            months AS (
                SELECT i                                                 AS month_offset,
                       (:cohortMonth + make_interval(months => i))::date AS month
                FROM generate_series(0, :months - 1) AS g(i)
            )
            SELECT
                m.month_offset,
                m.month,
                s.cohort_size,
                COUNT(a.customer_id) AS active_customers,
                round(100.0 * COUNT(a.customer_id) / NULLIF(s.cohort_size, 0), 2)
                                     AS retention_pct
            FROM months m
            CROSS JOIN (SELECT COUNT(*) AS cohort_size FROM cohort) s
            LEFT JOIN activity a ON a.month = m.month
            GROUP BY m.month_offset, m.month, s.cohort_size
            ORDER BY m.month_offset
            """)
        .param("cohortMonth", cohortMonth.atDay(1))
        .param("months", months)
        .query(RecordRowMapper.of(CohortRetentionResponse.class))
        .list();
  }

  // ================================================================
  // 9. Vòng quay tồn kho
  // ================================================================

  /**
   * {@code n} sản phẩm ACTIVE có vòng quay tồn kho cao nhất trong {@code [from, to]}.
   *
   * <p>Tồn kho tại mốc T = tồn kho hiện tại − tổng {@code delta_on_hand} từ T tới nay. Cộng delta
   * không phụ thuộc thứ tự, nên đúng cả khi nhiều movement trùng {@code occurred_at} (cùng một
   * transaction). Hai correlated subquery ({@code LATERAL} — tham chiếu {@code p.id} của câu ngoài)
   * chạy một lần cho mỗi sản phẩm, mỗi lần chỉ đọc sổ của sản phẩm đó qua {@code
   * idx_inv_mov_product_time (product_id, occurred_at DESC)}.
   */
  public List<InventoryTurnoverResponse> inventoryTurnover(int n, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            WITH stock AS (
                SELECT
                    p.id, p.name, p.sku,
                    since_start.sold - since_end.sold      AS units_sold,
                    pi.on_hand - since_start.on_hand_delta AS on_hand_start,
                    pi.on_hand - since_end.on_hand_delta   AS on_hand_end
                FROM app.products p
                JOIN app.product_inventory pi ON pi.product_id = p.id
                CROSS JOIN LATERAL (
                    -- tổng từ 00:00 ngày from tới nay
                    SELECT COALESCE(SUM(m.delta_sold), 0)    AS sold,
                           COALESCE(SUM(m.delta_on_hand), 0) AS on_hand_delta
                    FROM app.inventory_movements m
                    WHERE m.product_id = p.id
                      AND m.occurred_at >= :from::timestamp
                                               AT TIME ZONE app.business_timezone()
                ) since_start
                CROSS JOIN LATERAL (
                    -- tổng từ 00:00 ngày sau to tới nay
                    SELECT COALESCE(SUM(m.delta_sold), 0)    AS sold,
                           COALESCE(SUM(m.delta_on_hand), 0) AS on_hand_delta
                    FROM app.inventory_movements m
                    WHERE m.product_id = p.id
                      AND m.occurred_at >= (:to + 1)::timestamp
                                               AT TIME ZONE app.business_timezone()
                ) since_end
                WHERE p.status = 'ACTIVE'
            )
            SELECT
                id            AS product_id,
                name          AS product_name,
                sku,
                units_sold,
                on_hand_start,
                on_hand_end,
                round(units_sold / NULLIF((on_hand_start + on_hand_end) / 2.0, 0), 2)
                              AS turnover_ratio
            FROM stock
            ORDER BY turnover_ratio DESC NULLS LAST, units_sold DESC, id
            LIMIT :n
            """)
        .param("from", from)
        .param("to", to)
        .param("n", n)
        .query(RecordRowMapper.of(InventoryTurnoverResponse.class))
        .list();
  }

  // ================================================================
  // 10. Giá trị đơn theo phân khúc
  // ================================================================

  /**
   * Thống kê giá trị đơn được tính trong {@code [from, to]} theo {@code order_segment} của view —
   * ngưỡng phân khúc chỉ nằm ở V16.
   *
   * <p>{@code percentile_cont} chỉ có bản {@code double precision}: {@code total} (numeric) bị ép
   * sang số thực trước khi nội suy. Ép kết quả về {@code numeric} rồi làm tròn 2 chữ số như mọi số
   * tiền khác. Sắp theo {@code MIN(total)}: phân khúc là các khoảng giá rời nhau, nên đúng thứ tự
   * SMALL → VIP mà không phải lặp lại tên phân khúc trong SQL.
   */
  public List<OrderSegmentResponse> avgOrderValueBySegment(LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT
                order_segment        AS segment,
                COUNT(*)             AS order_count,
                round(AVG(total), 2) AS avg_value,
                MIN(total)           AS min_value,
                MAX(total)           AS max_value,
                SUM(total)           AS total_value,
                round((percentile_cont(0.5)  WITHIN GROUP (ORDER BY total))::numeric, 2) AS median,
                round((percentile_cont(0.95) WITHIN GROUP (ORDER BY total))::numeric, 2) AS p95
            FROM app.v_completed_orders
            WHERE created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
              AND created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            GROUP BY order_segment
            ORDER BY MIN(total)
            """)
        .param("from", from)
        .param("to", to)
        .query(RecordRowMapper.of(OrderSegmentResponse.class))
        .list();
  }

  // ================================================================
  // 11. Tăng trưởng theo tháng
  // ================================================================

  /**
   * 12 tháng của {@code year}, mỗi tháng so với tháng trước và cùng tháng năm trước.
   *
   * <p>{@code LAG} đếm <b>row</b>, không đếm tháng — nên chạy trên lịch 24 tháng liền mạch (năm
   * trước + năm nay, tháng không có đơn = 0), và chỉ lọc còn năm nay <b>sau</b> khi tính {@code
   * LAG}: {@code WHERE} chạy trước window function, lọc cùng tầng thì {@code LAG} không còn thấy
   * năm trước.
   */
  public List<RevenueGrowthResponse> monthOverMonthGrowth(int year) {
    return jdbc.sql(
            """
            WITH months AS (
                SELECT (make_date(:year - 1, 1, 1) + make_interval(months => i))::date AS month
                FROM generate_series(0, 23) AS g(i)
            ),
            monthly AS (
                SELECT date_trunc('month', day::timestamp)::date AS month,
                       SUM(net_revenue)                          AS revenue
                FROM app.mv_daily_revenue
                WHERE day >= make_date(:year - 1, 1, 1) AND day < make_date(:year + 1, 1, 1)
                GROUP BY 1
            ),
            series AS (
                SELECT m.month, COALESCE(r.revenue, 0.00) AS revenue
                FROM months m
                LEFT JOIN monthly r ON r.month = m.month
            ),
            compared AS (
                SELECT
                    month,
                    revenue,
                    LAG(revenue, 1)  OVER w AS prev_month_revenue,
                    LAG(revenue, 12) OVER w AS same_month_last_year
                FROM series
                WINDOW w AS (ORDER BY month)
            )
            SELECT
                month,
                revenue,
                prev_month_revenue,
                revenue - prev_month_revenue AS abs_change,
                round(100.0 * (revenue - prev_month_revenue) / NULLIF(prev_month_revenue, 0), 2)
                    AS pct_change,
                same_month_last_year,
                round(100.0 * (revenue - same_month_last_year)
                            / NULLIF(same_month_last_year, 0), 2)
                    AS yoy_pct
            FROM compared
            WHERE month >= make_date(:year, 1, 1)
            ORDER BY month
            """)
        .param("year", year)
        .query(RecordRowMapper.of(RevenueGrowthResponse.class))
        .list();
  }

  // ================================================================
  // 12. Top category — cộng dồn theo cây
  // ================================================================

  /**
   * {@code n} category gốc có doanh số cao nhất trong {@code [from, to]}, mỗi category gốc gồm cả
   * hàng của mọi category con cháu.
   *
   * <p>Recursive CTE đi từ gốc xuống, mang theo {@code root_id}: mỗi category biết gốc của nó, sản
   * phẩm join vào cây là gộp được theo gốc. Category nằm trong một vòng lặp (trigger V4 đã chặn)
   * không đi tới được từ gốc nên không bị đếm.
   *
   * <p>Dòng hàng của khoảng lấy trước, trong CTE {@code MATERIALIZED}, rồi mới join sản phẩm và
   * cây. Planner không ước lượng được số row của recursive CTE; để nó tự xếp thứ tự join thì nó đi
   * từ cây → mọi sản phẩm → mọi dòng hàng, rồi mới lọc theo khoảng ngày — trên 100.000 sản phẩm,
   * 1,5 triệu dòng hàng, plan custom mất 2,2–2,5 s cho 30 ngày, 3,0–3,3 s cho 365 ngày (bản này:
   * 0,19–0,22 s và 0,62–0,85 s — {@code F08__search-performance/06-verify-and-pr.md} §11). {@code
   * MATERIALIZED} là rào chắn: câu trong CTE được lập plan riêng, đi từ đơn của khoảng như các báo
   * cáo khác.
   */
  public List<TopCategoryResponse> topCategoriesByRevenue(int n, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            WITH RECURSIVE tree AS (
                SELECT id, id AS root_id
                FROM app.categories
                WHERE parent_id IS NULL
                UNION ALL
                SELECT c.id, t.root_id
                FROM app.categories c
                JOIN tree t ON c.parent_id = t.id
            ),
            lines AS MATERIALIZED (
                SELECT o.id AS order_id, oi.product_id, oi.quantity, oi.line_total
                FROM app.v_completed_orders o
                JOIN app.order_items oi ON oi.order_id = o.id
                WHERE o.created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
                  AND o.created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            )
            SELECT
                RANK() OVER (ORDER BY SUM(l.line_total) DESC) AS revenue_rank,
                r.id                                          AS category_id,
                r.name                                        AS category_name,
                COUNT(DISTINCT l.order_id)                    AS order_count,
                SUM(l.quantity)                               AS units_sold,
                SUM(l.line_total)                             AS revenue
            FROM lines l
            JOIN app.products p   ON p.id = l.product_id
            JOIN tree t           ON t.id = p.category_id
            JOIN app.categories r ON r.id = t.root_id
            GROUP BY r.id
            ORDER BY revenue DESC, r.id
            LIMIT :n
            """)
        .param("from", from)
        .param("to", to)
        .param("n", n)
        .query(RecordRowMapper.of(TopCategoryResponse.class))
        .list();
  }

  // ================================================================
  // 13. Doanh số nhiều cấp — GROUPING SETS
  // ================================================================

  /**
   * Doanh số trong {@code [from, to]} ở 4 cấp trong một câu: tháng × category, tháng, category,
   * tổng cộng.
   *
   * <p>Không {@code COALESCE} cột {@code NULL} thành nhãn kiểu {@code '__ALL__'}: sản phẩm chưa
   * phân loại cũng có category {@code NULL}, gắn nhãn là trộn row chi tiết với row tổng. Loại row
   * nằm ở {@code GROUPING()} — xem {@link RevenueGroupingSetResponse}.
   *
   * <p>Khoảng không có đơn vẫn ra một row: tập gộp rỗng {@code ()} luôn sinh row tổng cộng, kể cả
   * trên tập rỗng — như {@code SELECT count(*)} trên bảng rỗng. {@code SUM} của tập rỗng là {@code
   * NULL}, nên doanh số được {@code COALESCE} về {@code 0.00} như mọi tổng tiền khác của dashboard.
   * Row của ba cấp kia luôn có ít nhất một dòng hàng, {@code SUM} của chúng không bao giờ {@code
   * NULL}.
   */
  public List<RevenueGroupingSetResponse> revenueGroupingSets(LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            WITH lines AS (
                SELECT
                    date_trunc('month', o.created_at AT TIME ZONE app.business_timezone())::date
                                  AS month,
                    o.id          AS order_id,
                    p.category_id AS category_id,
                    oi.line_total AS line_total
                FROM app.v_completed_orders o
                JOIN app.order_items oi ON oi.order_id = o.id
                JOIN app.products p     ON p.id = oi.product_id
                WHERE o.created_at >= :from::timestamp     AT TIME ZONE app.business_timezone()
                  AND o.created_at <  (:to + 1)::timestamp AT TIME ZONE app.business_timezone()
            )
            SELECT
                l.month                           AS month,
                l.category_id                     AS category_id,
                c.name                            AS category_name,
                COALESCE(SUM(l.line_total), 0.00) AS revenue,
                COUNT(DISTINCT l.order_id)        AS order_count,
                GROUPING(l.month, l.category_id)  AS grouping_level
            FROM lines l
            LEFT JOIN app.categories c ON c.id = l.category_id
            GROUP BY GROUPING SETS (
                (l.month, l.category_id, c.name),
                (l.month),
                (l.category_id, c.name),
                ()
            )
            ORDER BY grouping_level, l.month, revenue DESC, l.category_id
            """)
        .param("from", from)
        .param("to", to)
        .query(RecordRowMapper.of(RevenueGroupingSetResponse.class))
        .list();
  }

  // ================================================================
  // 14. Giá trị trọn đời của khách
  // ================================================================

  /**
   * LTV của khách {@code customerId}; rỗng khi không có khách đó.
   *
   * <p>{@code LEFT JOIN} vào view: điều kiện "đơn được tính" nằm trong view, nên khách chưa có đơn
   * vẫn giữ row (số đơn 0). Viết với bảng {@code orders} thì điều kiện status phải nằm ở {@code ON}
   * — đặt ở {@code WHERE} là biến LEFT JOIN thành INNER JOIN, mất đúng khách đó.
   */
  public Optional<CustomerLifetimeValueResponse> customerLifetimeValue(UUID customerId) {
    return jdbc.sql(
            """
            SELECT
                c.id                         AS customer_id,
                c.email                      AS email,
                COUNT(o.id)                  AS total_orders,
                COALESCE(SUM(o.total), 0.00) AS total_spent,
                round(AVG(o.total), 2)       AS avg_order_value,
                MIN(o.created_at)            AS first_order_at,
                MAX(o.created_at)            AS last_order_at
            FROM app.customers c
            LEFT JOIN app.v_completed_orders o ON o.customer_id = c.id
            WHERE c.id = :customerId
            GROUP BY c.id
            """)
        .param("customerId", customerId)
        .query(RecordRowMapper.of(CustomerLifetimeValueResponse.class))
        .optional();
  }

  // ================================================================
  // Bảo trì
  // ================================================================

  /**
   * Tính lại {@code app.mv_daily_revenue} — một trong hai lệnh <b>ghi</b> của class (cùng {@link
   * #populateDailyRevenue()}).
   *
   * <p>{@code CONCURRENTLY}: dashboard vẫn đọc bản cũ trong lúc refresh. Hai điều kiện của Postgres
   * (V16 đã thoả): UNIQUE index trên cột thường, không WHERE; MV đã có dữ liệu — chưa có thì lỗi
   * {@code 0A000}. Không chạy được trong transaction read-only ({@code 25006}).
   */
  public void refreshDailyRevenue() {
    jdbc.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY app.mv_daily_revenue").update();
  }

  /**
   * Nạp {@code app.mv_daily_revenue} khi nó đang ở trạng thái chưa có dữ liệu ({@code REFRESH ...
   * WITH NO DATA}) — lúc đó {@code CONCURRENTLY} lỗi {@code 0A000}. Không {@code CONCURRENTLY}: giữ
   * {@code AccessExclusiveLock} tới hết transaction, chặn cả SELECT — nhưng trước đó SELECT vào MV
   * vốn đã lỗi {@code 55000}.
   */
  public void populateDailyRevenue() {
    jdbc.sql("REFRESH MATERIALIZED VIEW app.mv_daily_revenue").update();
  }

  /** {@code false} khi MV đang {@code WITH NO DATA} ({@code pg_class.relispopulated}). */
  public boolean isDailyRevenuePopulated() {
    return jdbc.sql(
            """
            SELECT relispopulated FROM pg_class WHERE oid = 'app.mv_daily_revenue'::regclass
            """)
        .query(Boolean.class)
        .single();
  }

  /**
   * Khóa advisory cho việc refresh, theo transaction: {@code true} nếu lấy được; {@code false} NGAY
   * (không chờ) nếu transaction khác — instance khác của app — đang giữ. Tự nhả khi transaction kết
   * thúc (commit hay rollback), nên không có đường nào quên nhả, kể cả khi connection về lại pool.
   *
   * <p>Khóa là OID của chính MV ({@code 'app.mv_daily_revenue'::regclass}): duy nhất trong DB,
   * không phải tự chọn một con số và giữ cho nó không trùng.
   */
  public boolean tryLockDailyRevenueRefresh() {
    return jdbc.sql(
            """
            SELECT pg_try_advisory_xact_lock('app.mv_daily_revenue'::regclass::oid::bigint)
            """)
        .query(Boolean.class)
        .single();
  }

  /**
   * Múi giờ DB dùng để cắt ngày ({@code app.business_timezone()}, V16) — để so với {@code
   * app.business.timezone} của app.
   */
  public String businessTimezone() {
    return jdbc.sql("SELECT app.business_timezone()").query(String.class).single();
  }

  private static LocalDate firstDay(int year) {
    return Year.of(year).atDay(1);
  }

  private static LocalDate lastDay(int year) {
    return Year.of(year).atMonth(Month.DECEMBER).atEndOfMonth();
  }
}
