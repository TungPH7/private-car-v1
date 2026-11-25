package com.tungph7.learnpostgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;

/**
 * Câu của các feature trước dùng đúng index của chúng — AC-08, AC-09 của F08. AC-06 (câu tìm sản
 * phẩm) ở {@code SearchQueryRepositoryJdbcTest.searchStatementUsesTheTrigramAndSkuIndexes}.
 *
 * <p>Hỏi "index có dùng được cho câu này không", không phải "planner có chọn nó không": bảng của
 * test vài row thì planner chọn Seq Scan, nên Seq Scan bị tắt ({@code SET LOCAL} trong transaction
 * của {@code @JdbcTest}, rollback cuối test). AC-08 còn tắt mọi đường cần Sort và bỏ index cạnh
 * tranh trong transaction đó — xem comment trong test. Planner tự chọn partial index của đơn
 * PENDING trên 1 triệu đơn: {@code F08__search-performance/06-verify-and-pr.md} §8.2.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IndexPlanJdbcTest extends AbstractContainerBaseTest {

  /** Một tham số dạng tên ({@code :x}, {@code :#{...}}) — không tính dấu ép kiểu {@code ::}. */
  private static final String NAMED_PARAMETER = "(?<!:):[A-Za-z#]";

  @Autowired JdbcClient jdbc;

  /**
   * SQL của một {@code @Query(nativeQuery = true)} — đúng chuỗi app gửi đi, không phải bản chép.
   */
  private static String nativeQuery(Class<?> repository, String method, Class<?>... parameters)
      throws NoSuchMethodException {
    return repository.getMethod(method, parameters).getAnnotation(Query.class).value();
  }

  private List<String> plan(String sql, String name, Object value) {
    return jdbc.sql("EXPLAIN (COSTS OFF) " + sql).param(name, value).query(String.class).list();
  }

  @Test
  @DisplayName(
      "AC-08: ứng viên của job expire (findExpirationCandidates) đi idx_orders_pending_created_at"
          + " theo thứ tự created_at — không Sort, không Filter status, cả plan generic")
  void expirationCandidatesUseThePendingPartialIndex() throws NoSuchMethodException {
    String sql =
        nativeQuery(OrderRepository.class, "findExpirationCandidates", Instant.class, int.class);
    String positional = sql.replace(":cutoff", "$1").replace(":batchSize", "$2");
    // status phải là literal: tham số thì plan generic không chứng minh được điều kiện của
    // partial index (WHERE status = 'PENDING') — Javadoc của findExpirationCandidates
    assertThat(positional)
        .as("chỉ cutoff, batchSize là tham số — status là literal")
        .doesNotContainPattern(NAMED_PARAMETER);
    // Chỉ để lại một đường đọc đơn PENDING theo thứ tự created_at: tắt Seq Scan và mọi đường
    // cần Sort, bỏ idx_orders_status_created_at — index đó cũng đọc được theo thứ tự (Index Scan
    // Backward). Chỉ tắt Seq Scan thì trên bảng vài row planner chọn đường nào tùy thống kê lúc
    // chạy. DROP INDEX nằm trong transaction của @JdbcTest — rollback cuối test.
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    jdbc.sql("SET LOCAL enable_sort = off").update();
    jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
    jdbc.sql("DROP INDEX app.idx_orders_status_created_at").update();

    List<String> custom =
        jdbc.sql("EXPLAIN (COSTS OFF) " + sql)
            .param("cutoff", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30))
            .param("batchSize", 100)
            .query(String.class)
            .list();
    jdbc.sql("SET LOCAL plan_cache_mode = force_generic_plan").update();
    jdbc.sql("PREPARE expiration_candidates(timestamptz, int) AS " + positional).update();
    List<String> generic =
        jdbc.sql("EXPLAIN (COSTS OFF) EXECUTE expiration_candidates(now(), 100)")
            .query(String.class)
            .list();
    jdbc.sql("DEALLOCATE expiration_candidates").update();

    for (List<String> plan : List.of(custom, generic)) {
      assertThat(plan)
          .anyMatch(line -> line.contains("Index Scan using idx_orders_pending_created_at"))
          // index đã theo thứ tự created_at; điều kiện status nằm sẵn trong định nghĩa index
          .noneMatch(line -> line.contains("Sort"))
          .noneMatch(line -> line.contains("Filter:"));
    }
    assertThat(generic).anyMatch(line -> line.contains("Index Cond: (created_at < $1)"));
  }

  @Test
  @DisplayName(
      "AC-09: containment trên gateway_response (@>) dùng idx_payments_gateway_response_gin; so"
          + " một field bằng ->> thì không — GIN chỉ phục vụ toán tử của nó")
  void gatewayResponseContainmentUsesTheGinIndex() {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();

    assertThat(
            plan(
                "SELECT id FROM app.payments WHERE gateway_response @> CAST(:probe AS jsonb)",
                "probe",
                "{\"transactionId\": \"txn_42\"}"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_payments_gateway_response_gin"));
    assertThat(
            plan(
                "SELECT id FROM app.payments WHERE gateway_response ->> 'transactionId' = :txn",
                "txn",
                "txn_42"))
        .noneMatch(line -> line.contains("idx_payments_gateway_response_gin"));
  }
}
