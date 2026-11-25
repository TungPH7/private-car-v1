package com.tungph7.learnpostgres.feature.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.repository.projection.OrderDetailRow;

/**
 * Repository của {@code app.orders}.
 *
 * <p>Ba method ghi/khóa ({@link #nextOrderNumberSequence()}, {@link #transitionStatus}, {@link
 * #lockForExpiration}) phải chạy trong transaction <b>read-write</b>: {@code nextval()} và {@code
 * SELECT ... FOR UPDATE} bị Postgres từ chối trong transaction read-only (SQLSTATE 25006). Service
 * gọi chúng từ method {@code @Transactional} thường, không phải {@code readOnly = true}.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByOrderNumber(String orderNumber);

  Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

  // -----------------------------------------------------------------
  // Sequence + order_number
  // -----------------------------------------------------------------

  /**
   * Số kế tiếp của {@code app.orders_number_seq} — phần {@code NNNNNN} của số đơn.
   *
   * <p>DB chỉ lo phần duy nhất nó làm tốt hơn app: cấp số không trùng giữa mọi connection / mọi
   * instance. Ghép thành {@code ORD-YYYYMM-NNNNNN} là việc của {@code OrderNumberGenerator} — xem
   * lớp đó để biết vì sao KHÔNG format bằng SQL.
   *
   * <p>{@code nextval} không rollback theo transaction — đơn fail sau bước này để lại gap, chấp
   * nhận (xem {@code 01-migration.md §5.3}).
   */
  @Query(value = "SELECT nextval('app.orders_number_seq')", nativeQuery = true)
  long nextOrderNumberSequence();

  // -----------------------------------------------------------------
  // Atomic status transition — KEY pattern
  // -----------------------------------------------------------------

  /**
   * Chuyển trạng thái {@code from → to} nếu đơn vẫn đang ở {@code from}. Race-safe.
   *
   * <p>{@code WHERE status = :from} là guard: tx khác đã đổi trạng thái trước → 0 row → caller ném
   * {@code ORD_RACE_CONDITION}. Không cần {@code SELECT ... FOR UPDATE} trước đó — Postgres lock
   * row khi UPDATE chạm tới và re-check WHERE trên state mới nhất.
   *
   * <p>Timestamp tương ứng set trong cùng câu UPDATE, khớp 2 CHECK của V12: {@code cancelled_at} +
   * {@code cancel_reason} set cho cả CANCELLED lẫn EXPIRED ({@code
   * ck_orders_cancelled_at_with_status}); {@code paid_at} giữ nguyên khi đơn PAID bị hủy.
   * Transition có hợp lệ hay không ({@code OrderStatus.canTransitionTo}) caller kiểm TRƯỚC — câu
   * này chỉ chống race, không kiểm state machine.
   *
   * <p>{@code flushAutomatically} đẩy thay đổi đang chờ xuống DB trước câu UPDATE; {@code
   * clearAutomatically} xoá persistence context sau đó, để {@code findById} kế tiếp đọc trạng thái
   * mới thay vì instance cũ đang cache. Hai option này có tác dụng vì method trả {@code int} (đi
   * đường {@code ModifyingExecution}) — xem Javadoc của {@code InventoryRepository}.
   *
   * @param from tên {@code OrderStatus} hiện tại ({@code status.name()})
   * @param to tên {@code OrderStatus} đích
   * @param reason ghi vào {@code cancel_reason} khi {@code to} là CANCELLED / EXPIRED; bỏ qua với
   *     trạng thái khác (lý do vẫn nằm ở {@code order_status_history})
   * @return 1 nếu chuyển được, 0 nếu đơn không tồn tại hoặc không còn ở {@code from}
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.orders
          SET status        = :to,
              paid_at       = CASE WHEN :to = 'PAID'      THEN now() ELSE paid_at      END,
              shipped_at    = CASE WHEN :to = 'SHIPPED'   THEN now() ELSE shipped_at   END,
              delivered_at  = CASE WHEN :to = 'DELIVERED' THEN now() ELSE delivered_at END,
              cancelled_at  = CASE WHEN :to IN ('CANCELLED', 'EXPIRED')
                                   THEN now() ELSE cancelled_at END,
              cancel_reason = CASE WHEN :to IN ('CANCELLED', 'EXPIRED')
                                   THEN :reason ELSE cancel_reason END
          WHERE id = :orderId
            AND status = :from
          """,
      nativeQuery = true)
  int transitionStatus(
      @Param("orderId") UUID orderId,
      @Param("from") String from,
      @Param("to") String to,
      @Param("reason") String reason);

  // -----------------------------------------------------------------
  // Expire pending orders — ứng viên, rồi khóa TỪNG đơn bằng SKIP LOCKED
  // -----------------------------------------------------------------

  /**
   * Id các đơn PENDING tạo trước {@code cutoff}, cũ nhất trước — ứng viên để expire. KHÔNG khóa.
   *
   * <p>Không {@code FOR UPDATE} ở câu này vì job expire mỗi đơn trong một transaction riêng (bài
   * 03): đơn nào lỗi thì chỉ đơn đó rollback. Khóa cả batch ở đây thì phải xử lý cả batch trong
   * chính transaction đang giữ khóa — một đơn lỗi kéo cả batch rollback, và lần chạy sau lại gặp
   * đúng đơn đó ở đầu danh sách. Khóa từng đơn là việc của {@link #lockForExpiration}.
   *
   * <p>{@code status = 'PENDING'} là literal (không phải tham số) để planner khớp được partial
   * index {@code idx_orders_pending_created_at} (V12).
   */
  @Query(
      value =
          """
          SELECT id FROM app.orders
          WHERE status = 'PENDING'
            AND created_at < :cutoff
          ORDER BY created_at
          LIMIT :batchSize
          """,
      nativeQuery = true)
  List<UUID> findExpirationCandidates(
      @Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

  /**
   * Khóa một đơn để expire — chỉ khi nó VẪN PENDING, vẫn quá hạn, và không transaction nào khác
   * đang giữ nó.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED}: đơn đang bị khóa (worker khác đang expire nó, khách đang
   * thanh toán nó) thì bỏ qua ngay thay vì đứng chờ. Nhiều worker chạy song song vì vậy không chặn
   * nhau và không xử lý trùng một đơn; đơn bị bỏ qua được lần chạy sau nhìn lại với trạng thái cuối
   * cùng của nó.
   *
   * <p>Điều kiện status + cutoff lặp lại ở đây, không tin danh sách ứng viên: giữa lúc lấy ứng viên
   * và lúc khóa, đơn có thể đã được thanh toán hoặc đã bị worker khác expire.
   *
   * <p>Khóa giữ tới hết transaction của caller — caller expire đơn ngay trong transaction đó.
   */
  @Query(
      value =
          """
          SELECT * FROM app.orders
          WHERE id = :orderId
            AND status = 'PENDING'
            AND created_at < :cutoff
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  Optional<Order> lockForExpiration(
      @Param("orderId") UUID orderId, @Param("cutoff") Instant cutoff);

  // -----------------------------------------------------------------
  // Detail — 1 query cho 5 bảng: order + customer + coupon + items + history
  // -----------------------------------------------------------------

  /**
   * Detail bundle trong 1 query. Items và history gom thành 2 JSON array bằng {@code json_agg}.
   *
   * <ul>
   *   <li><b>Items qua LEFT JOIN, history qua subquery — cố ý khác nhau.</b> LEFT JOIN cả hai bảng
   *       1-n sẽ nhân chéo row (3 item × 4 history = 12 row) và {@code json_agg} của items lặp lại
   *       theo số history. Subquery tự aggregate trong phạm vi của nó nên không nhân gì.
   *   <li>{@code FILTER (WHERE oi.id IS NOT NULL)} + {@code COALESCE(..., '[]')}: đơn chưa có item
   *       ra {@code []} thay vì {@code [null]} (LEFT JOIN không khớp vẫn sinh 1 row toàn NULL).
   *   <li>{@code GROUP BY o.id, c.id, cp.id}: group theo PK nên SELECT được mọi cột khác của 3 bảng
   *       đó (functional dependency) mà không phải liệt kê.
   *   <li>CAST sang {@code text} là bắt buộc: {@code email} là {@code citext}, {@code json_agg} trả
   *       {@code json} — pgjdbc trả cả hai dưới dạng {@code PGobject}, interface projection không
   *       convert được sang {@code String}.
   *   <li>{@code changedAt} trong {@code historyJson} là chuỗi ISO-8601 kèm offset theo {@code
   *       TimeZone} của session (dev {@code +07:00}, test {@code +00:00}) — cùng một instant, parse
   *       bằng {@code OffsetDateTime} chứ đừng so chuỗi.
   * </ul>
   */
  @Query(
      value =
          """
          SELECT
              o.id                       AS id,
              o.order_number             AS orderNumber,
              o.status                   AS status,
              o.subtotal                 AS subtotal,
              o.discount_amount          AS discountAmount,
              o.shipping_fee             AS shippingFee,
              o.total                    AS total,
              o.shipping_recipient       AS shippingRecipient,
              o.shipping_phone           AS shippingPhone,
              o.shipping_address_line1   AS shippingLine1,
              o.shipping_address_line2   AS shippingLine2,
              o.shipping_city            AS shippingCity,
              o.shipping_country         AS shippingCountry,
              o.created_at               AS createdAt,
              c.id                       AS customerId,
              CAST(c.email AS text)      AS customerEmail,
              c.first_name || ' ' || c.last_name AS customerName,
              cp.id                      AS couponId,
              cp.code                    AS couponCode,
              CAST(COALESCE(
                  json_agg(
                      json_build_object(
                          'id',          oi.id,
                          'productId',   oi.product_id,
                          'productName', oi.product_name,
                          'productSku',  oi.product_sku,
                          'quantity',    oi.quantity,
                          'unitPrice',   oi.unit_price,
                          'lineTotal',   oi.line_total
                      ) ORDER BY oi.created_at, oi.id
                  ) FILTER (WHERE oi.id IS NOT NULL),
                  CAST('[]' AS json)) AS text) AS itemsJson,
              CAST(COALESCE(
                  (SELECT json_agg(
                              json_build_object(
                                  'fromStatus', h.from_status,
                                  'toStatus',   h.to_status,
                                  'reason',     h.reason,
                                  'actor',      h.actor,
                                  'changedAt',  h.changed_at
                              ) ORDER BY h.changed_at, h.id)
                   FROM app.order_status_history h
                   WHERE h.order_id = o.id),
                  CAST('[]' AS json)) AS text) AS historyJson
          FROM app.orders o
          JOIN app.customers c          ON c.id = o.customer_id
          LEFT JOIN app.coupons cp      ON cp.id = o.coupon_id
          LEFT JOIN app.order_items oi  ON oi.order_id = o.id
          WHERE o.id = :id
          GROUP BY o.id, c.id, cp.id
          """,
      nativeQuery = true)
  Optional<OrderDetailRow> findDetailById(@Param("id") UUID id);
}
