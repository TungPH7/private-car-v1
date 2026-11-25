package com.tungph7.learnpostgres.feature.customer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.customer.domain.Customer;
import com.tungph7.learnpostgres.feature.customer.repository.projection.CustomerProfileRow;
import com.tungph7.learnpostgres.feature.customer.repository.projection.CustomerSearchRow;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

  Optional<Customer> findByEmail(String email);

  boolean existsByEmail(String email);

  /**
   * Phone unique chỉ trên ACTIVE customer (partial unique). Trả Optional vì có thể 0 hoặc 1 row.
   */
  @Query(
      "SELECT c FROM Customer c WHERE c.phone = :phone AND c.status ="
          + " com.tungph7.learnpostgres.feature.customer.domain.CustomerStatus.ACTIVE")
  Optional<Customer> findByPhoneActive(@Param("phone") String phone);

  // -----------------------------------------------------------------
  // Dynamic search với COUNT(*) OVER () pagination
  // -----------------------------------------------------------------

  /**
   * Search customers với filter dynamic — q (email/name), status, dateRange. Pattern (:param IS
   * NULL OR ...) cho phép null bypass filter.
   *
   * <p>COUNT(*) OVER () trả total trong cùng query → 1 round-trip thay vì 2. Trade-off: với page
   * sâu sẽ đắt vì phải count hết.
   *
   * <p>WARN: native query — projection field name = alias SQL.
   */
  @Query(
      value =
          """
          SELECT
              c.id              AS id,
              CAST(c.email AS text) AS email,
              c.phone           AS phone,
              c.first_name      AS firstName,
              c.last_name       AS lastName,
              c.status          AS status,
              c.email_verified  AS emailVerified,
              c.created_at      AS createdAt,
              COUNT(*) OVER ()  AS totalCount
          FROM app.customers c
          WHERE (CAST(:q AS text) IS NULL OR
                 c.email ILIKE '%' || CAST(:q AS text) || '%' OR
                 (c.first_name || ' ' || c.last_name) ILIKE '%' || CAST(:q AS text) || '%')
            AND (CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))
            AND (CAST(:emailVerified AS boolean) IS NULL
                 OR c.email_verified = CAST(:emailVerified AS boolean))
            AND (CAST(:registeredFrom AS timestamptz) IS NULL
                 OR c.created_at >= CAST(:registeredFrom AS timestamptz))
            AND (CAST(:registeredTo AS timestamptz) IS NULL
                 OR c.created_at <  CAST(:registeredTo AS timestamptz))
          ORDER BY c.created_at DESC
          LIMIT :size OFFSET :offset
          """,
      nativeQuery = true)
  List<CustomerSearchRow> searchCustomers(
      @Param("q") String q,
      @Param("status") String status,
      @Param("emailVerified") Boolean emailVerified,
      @Param("registeredFrom") Instant registeredFrom,
      @Param("registeredTo") Instant registeredTo,
      @Param("size") int size,
      @Param("offset") int offset);

  // -----------------------------------------------------------------
  // Customer profile bundle — LEFT JOIN aggregate
  // -----------------------------------------------------------------

  /**
   * Trả customer kèm {@code ordersCount}, {@code totalSpent}, {@code lastOrderAt} — 1 query.
   *
   * <p><b>Cả 3 chỉ số tính trên CÙNG một tập đơn: đơn đã thanh toán và không bị hủy</b> ({@code
   * PAID}, {@code SHIPPED}, {@code DELIVERED}). Không tính:
   *
   * <ul>
   *   <li>{@code PENDING} — chưa trả tiền, có thể hết hạn.
   *   <li>{@code EXPIRED} — chưa bao giờ trả tiền.
   *   <li>{@code CANCELLED} — kể cả hủy sau khi đã PAID: tiền không còn là chi tiêu của khách.
   * </ul>
   *
   * <p>Chung một tập nên 3 số nói cùng một chuyện: {@code totalSpent / ordersCount} là giá trị đơn
   * trung bình, {@code lastOrderAt} là lần mua gần nhất. Tập lệch nhau (vd đếm mọi đơn nhưng chỉ
   * cộng tiền đơn DELIVERED) thì khách có 5 đơn EXPIRED hiện {@code ordersCount = 5, totalSpent =
   * 0} — không đọc được.
   *
   * <p><b>Điều kiện status nằm ở {@code ON}, không ở {@code WHERE}.</b> Đặt ở {@code WHERE} thì
   * customer không có đơn hợp lệ nào (chưa mua, hoặc toàn đơn EXPIRED) bị lọc mất khỏi LEFT JOIN →
   * endpoint trả 404 thay vì {@code 0 / 0 / null}.
   *
   * <p>{@code COALESCE(SUM(...), 0.00)}: SUM trên tập rỗng là NULL. Literal {@code 0.00} giữ scale
   * 2 như cột {@code total}; không CAST về {@code numeric(12,2)} vì tổng nhiều đơn có thể vượt trần
   * của một đơn.
   */
  @Query(
      value =
          """
          SELECT
              c.id              AS id,
              CAST(c.email AS text) AS email,
              c.first_name      AS firstName,
              c.last_name       AS lastName,
              c.phone           AS phone,
              c.status          AS status,
              c.created_at      AS createdAt,
              COUNT(o.id)                   AS ordersCount,
              COALESCE(SUM(o.total), 0.00)  AS totalSpent,
              MAX(o.created_at)             AS lastOrderAt
          FROM app.customers c
          LEFT JOIN app.orders o
                 ON o.customer_id = c.id
                AND o.status IN ('PAID', 'SHIPPED', 'DELIVERED')
          WHERE c.id = :id
          GROUP BY c.id
          """,
      nativeQuery = true)
  Optional<CustomerProfileRow> findProfile(@Param("id") UUID id);
}
