package com.tungph7.learnpostgres.feature.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.ConcurrencyTestSupport;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService.ReserveItem;

/**
 * Trọng tâm của F03: chứng minh atomic UPDATE thật sự không mất update, và bulkReserve sắp xếp theo
 * productId thật sự chặn được deadlock.
 *
 * <p>Cố ý KHÔNG đặt {@code @Transactional} lên class: mỗi lời gọi service phải chạy trong
 * transaction RIÊNG thì mới có tranh chấp thật. Gắn {@code @Transactional} vào test là mọi thread
 * dùng chung một transaction, không còn gì để đo — và dữ liệu cũng bị rollback mất.
 *
 * <p>Hệ quả: dữ liệu ghi ra là thật, commit thật, không tự biến mất sau mỗi test — nên phải tự dọn
 * ở {@code @AfterEach}, xem lý do ở {@link #cleanup()}.
 */
@SpringBootTest
@Tag("integration")
class InventoryConcurrencyIntegrationTest extends AbstractContainerBaseTest {

  /**
   * Số thread cho bài stress. Hikari ở profile test giới hạn {@code maximum-pool-size: 5}, nên con
   * số này là số thread ĐỢI nhau ở cửa connection pool chứ không phải số transaction chạy song song
   * thật. Để 200 là đủ tạo tranh chấp gắt mà không biến test thành bài đo pool.
   */
  private static final int STRESS_THREADS = 200;

  private static final int STRESS_STOCK = 100;

  @Autowired InventoryService service;
  @Autowired JdbcTemplate jdbc;

  /**
   * Hikari đặt {@code auto-commit: false}. Trong {@code @SpringBootTest} không có transaction bao
   * quanh, nên mọi ghi qua {@code JdbcTemplate} sẽ bị rollback lúc trả connection về pool — im
   * lặng, không lỗi. Seed phải bọc trong {@code TransactionTemplate} mới thật sự commit.
   */
  @Autowired TransactionTemplate tx;

  private UUID productId;

  @BeforeEach
  void setUp() {
    cleanup();
    productId = UUID.randomUUID();
    seedProduct(productId);
  }

  /**
   * BẮT BUỘC dọn, không phải cho sạch mắt.
   *
   * <p>{@code inventory_movements} có FK {@code ON DELETE RESTRICT} tới products (V9). Để sót
   * movement là mọi {@code DELETE FROM app.products} về sau đều bị chặn — kể cả {@code
   * repository.deleteAll()} trong {@code @AfterEach} của {@code ProductIntegrationTest}. Cleanup
   * của họ chết, data rác đọng lại, rồi test của họ đỏ vì slug trùng. Chạy riêng thì không thấy gì,
   * chỉ {@code ./mvnw verify} mới lộ.
   *
   * <p>Thứ tự bắt buộc: movement trước, product sau. {@code product_inventory} tự CASCADE.
   */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM app.inventory_movements WHERE product_id IN"
                  + " (SELECT id FROM app.products WHERE slug LIKE 'conc-%')");
          jdbc.update("DELETE FROM app.products WHERE slug LIKE 'conc-%'");
        });
  }

  // -----------------------------------------------------------------
  // AC-12
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-12: 2 reserve đồng thời trên on_hand=70, mỗi bên xin 50 — đúng 1 thắng")
  void twoConcurrentReserveExactStock() {
    seedInventory(productId, 70);

    AtomicInteger success = new AtomicInteger();
    AtomicInteger fail = new AtomicInteger();

    ConcurrencyTestSupport.runConcurrently(
        2,
        () -> {
          try {
            service.reserve(productId, 50, UUID.randomUUID(), "test");
            success.incrementAndGet();
          } catch (RuntimeException ex) {
            fail.incrementAndGet();
          }
          return null;
        });

    assertThat(success.get()).isEqualTo(1);
    assertThat(fail.get()).isEqualTo(1);

    // Điểm mấu chốt: 50, KHÔNG phải 100. 100 nghĩa là cả hai cùng đọc "available=70"
    // rồi cùng ghi — đúng định nghĩa lost update.
    assertThat(reservedOf(productId)).isEqualTo(50);
  }

  // -----------------------------------------------------------------
  // AC-14
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-14: 100 reserve qty=1 đồng thời trên on_hand=50 — đúng 50 thắng 50 thua")
  void hundredConcurrentExactCap() {
    seedInventory(productId, 50);

    AtomicInteger success = new AtomicInteger();
    AtomicInteger fail = new AtomicInteger();

    ConcurrencyTestSupport.runConcurrently(
        100,
        () -> {
          try {
            service.reserve(productId, 1, UUID.randomUUID(), "test");
            success.incrementAndGet();
          } catch (RuntimeException ex) {
            fail.incrementAndGet();
          }
          return null;
        });

    assertThat(success.get()).isEqualTo(50);
    assertThat(fail.get()).isEqualTo(50);
    assertThat(reservedOf(productId)).isEqualTo(50);

    // Movement chỉ được ghi khi reserve thành công — không thừa, không thiếu.
    Integer movements =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM app.inventory_movements "
                + "WHERE product_id = ? AND movement_type = 'RESERVE'",
            Integer.class,
            productId);
    assertThat(movements).isEqualTo(50);
  }

  // -----------------------------------------------------------------
  // AC-13
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-13: 20 bulkReserve với thứ tự product xáo trộn — không deadlock")
  void bulkReserveCrossOrder() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();
    seedProductWithStock(p1, 100);
    seedProductWithStock(p2, 100);
    seedProductWithStock(p3, 100);

    AtomicInteger errors = new AtomicInteger();

    ConcurrencyTestSupport.runConcurrently(
        20,
        () -> {
          try {
            List<UUID> ids = new ArrayList<>(List.of(p1, p2, p3));
            Collections.shuffle(ids);
            List<ReserveItem> items = ids.stream().map(id -> new ReserveItem(id, 1)).toList();
            service.bulkReserve(items, UUID.randomUUID(), "test-bulk");
          } catch (RuntimeException ex) {
            errors.incrementAndGet();
          }
          return null;
        });

    // Không có bulkReserve nào chết: nếu service không sort theo productId thì ở đây sẽ có
    // transaction bị Postgres giết vì deadlock detected.
    assertThat(errors.get()).isZero();

    for (UUID pid : List.of(p1, p2, p3)) {
      assertThat(reservedOf(pid)).isEqualTo(20);
    }
  }

  // -----------------------------------------------------------------
  // AC-15
  // -----------------------------------------------------------------

  @Test
  @DisplayName("AC-15: tranh chấp nặng — số reserve thành công đúng bằng stock, không dư")
  void heavyContentionNoLostUpdate() {
    seedInventory(productId, STRESS_STOCK);

    AtomicInteger success = new AtomicInteger();

    ConcurrencyTestSupport.runConcurrently(
        STRESS_THREADS,
        () -> {
          try {
            service.reserve(productId, 1, UUID.randomUUID(), "stress");
            success.incrementAndGet();
          } catch (RuntimeException ignored) {
            // hết hàng — đúng như mong đợi cho phần thread dư
          }
          return null;
        });

    assertThat(success.get()).isEqualTo(STRESS_STOCK);
    assertThat(reservedOf(productId)).isEqualTo(STRESS_STOCK);
  }

  // -----------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------

  private void seedProduct(UUID pid) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "INSERT INTO app.products (id, name, slug, price, status)"
                    + " VALUES (?, ?, ?, ?, 'ACTIVE')",
                pid,
                "Conc Test",
                "conc-" + pid,
                new BigDecimal("100")));
  }

  private void seedInventory(UUID pid, int onHand) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "INSERT INTO app.product_inventory (product_id, on_hand, reserved) VALUES (?, ?,"
                    + " 0)",
                pid,
                onHand));
  }

  private void seedProductWithStock(UUID pid, int stock) {
    seedProduct(pid);
    seedInventory(pid, stock);
  }

  private int reservedOf(UUID pid) {
    Integer v =
        jdbc.queryForObject(
            "SELECT reserved FROM app.product_inventory WHERE product_id = ?", Integer.class, pid);
    return v == null ? -1 : v;
  }
}
