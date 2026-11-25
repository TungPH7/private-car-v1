package com.tungph7.learnpostgres.feature.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.InventorySnapshot;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.LowStockRow;

/**
 * Slice test cho atomic UPDATE pattern — chạy trên Postgres thật (Testcontainers).
 *
 * <p>Điểm cần chứng minh: điều kiện nghiệp vụ nằm trong {@code WHERE} nên khi không thoả thì 0 row
 * đổi, list trả về rỗng, và DB KHÔNG bị ghi sai chút nào.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class InventoryRepositoryTest extends AbstractContainerBaseTest {

  @Autowired InventoryRepository repository;
  @Autowired JdbcTemplate jdbc;

  /** Tạo product + inventory row với on_hand cho trước. */
  private UUID seedInventory(int onHand) {
    UUID pid = seedProduct();
    jdbc.update(
        "INSERT INTO app.product_inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)",
        pid,
        onHand);
    return pid;
  }

  private UUID seedProduct() {
    UUID pid = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app.products (id, name, slug, price, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        pid,
        "Test",
        "slug-" + pid,
        new BigDecimal("100"));
    return pid;
  }

  private int reservedOf(UUID pid) {
    Integer v =
        jdbc.queryForObject(
            "SELECT reserved FROM app.product_inventory WHERE product_id = ?", Integer.class, pid);
    return v == null ? -1 : v;
  }

  // -----------------------------------------------------------------
  // Init
  // -----------------------------------------------------------------

  @Test
  @DisplayName("initIfAbsent — idempotent: lần đầu 1 row, lần sau 0 row")
  void initIdempotent() {
    UUID pid = seedProduct();

    assertThat(repository.initIfAbsent(pid)).isEqualTo(1);
    assertThat(repository.initIfAbsent(pid)).isZero(); // ON CONFLICT DO NOTHING
  }

  // -----------------------------------------------------------------
  // Atomic UPDATE
  // -----------------------------------------------------------------

  @Test
  @DisplayName("stockIn — UPDATE ... RETURNING map được sang projection")
  void stockInReturnsSnapshot() {
    UUID pid = seedInventory(0);

    List<InventorySnapshot> result = repository.stockIn(pid, 100);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getOnHand()).isEqualTo(100);
    assertThat(result.get(0).getReserved()).isZero();
  }

  @Test
  @DisplayName("reserve — đủ stock trả snapshot sau khi ghi")
  void reserveSuccess() {
    UUID pid = seedInventory(100);

    List<InventorySnapshot> result = repository.reserve(pid, 30);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getOnHand()).isEqualTo(100);
    assertThat(result.get(0).getReserved()).isEqualTo(30);
  }

  @Test
  @DisplayName("reserve — thiếu stock trả empty và KHÔNG đổi gì trong DB")
  void reserveInsufficient() {
    UUID pid = seedInventory(50);

    List<InventorySnapshot> result = repository.reserve(pid, 60);

    assertThat(result).isEmpty();
    assertThat(reservedOf(pid)).isZero();
  }

  @Test
  @DisplayName("reserve — biên available == qty vẫn phải qua")
  void reserveBoundary() {
    UUID pid = seedInventory(50);

    List<InventorySnapshot> result = repository.reserve(pid, 50);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getReserved()).isEqualTo(50);
  }

  @Test
  @DisplayName("release — trả nhiều hơn đang giữ thì 0 row")
  void releaseInvalid() {
    UUID pid = seedInventory(100);
    repository.reserve(pid, 30);

    assertThat(repository.release(pid, 50)).isEmpty();
    assertThat(repository.release(pid, 30)).hasSize(1);
    assertThat(reservedOf(pid)).isZero();
  }

  @Test
  @DisplayName("commit — đổi đúng 3 cột on_hand, reserved, sold")
  void commitFull() {
    UUID pid = seedInventory(100);
    repository.reserve(pid, 30);

    List<InventorySnapshot> result = repository.commit(pid, 30);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getOnHand()).isEqualTo(70);
    assertThat(result.get(0).getReserved()).isZero();

    Long sold =
        jdbc.queryForObject(
            "SELECT sold FROM app.product_inventory WHERE product_id = ?", Long.class, pid);
    assertThat(sold).isEqualTo(30L);
  }

  @Test
  @DisplayName("adjust — delta âm và dương đều áp dụng")
  void adjustDelta() {
    UUID pid = seedInventory(100);

    List<InventorySnapshot> result = repository.adjust(pid, -10, 5);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getOnHand()).isEqualTo(90);
    assertThat(result.get(0).getReserved()).isEqualTo(5);
  }

  // -----------------------------------------------------------------
  // CHECK constraint — hàng rào cuối ở DB
  // -----------------------------------------------------------------

  @Test
  @DisplayName("CHECK — UPDATE thẳng đẩy reserved > on_hand bị chặn")
  void checkConstraintReservedLeOnHand() {
    UUID pid = seedInventory(10);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE app.product_inventory SET reserved = 20 WHERE product_id = ?", pid))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_inventory_reserved_le_on_hand");
  }

  @Test
  @DisplayName("CHECK — on_hand âm bị chặn")
  void checkConstraintOnHandNegative() {
    UUID pid = seedInventory(10);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE app.product_inventory SET on_hand = -1 WHERE product_id = ?", pid))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_inventory_on_hand_non_neg");
  }

  // -----------------------------------------------------------------
  // Read
  // -----------------------------------------------------------------

  @Test
  @DisplayName("findLowStock — lọc theo reorder_point, kèm tên và sku của product")
  void findLowStock() {
    UUID pid = seedInventory(3);
    jdbc.update("UPDATE app.product_inventory SET reorder_point = 10 WHERE product_id = ?", pid);

    List<LowStockRow> rows = repository.findLowStock(5, 100);

    assertThat(rows)
        .anySatisfy(
            row -> {
              assertThat(row.getProductId()).isEqualTo(pid);
              assertThat(row.getOnHand()).isEqualTo(3);
              assertThat(row.getAvailable()).isEqualTo(3);
              assertThat(row.getReorderPoint()).isEqualTo(10);
              assertThat(row.getProductName()).isEqualTo("Test");
            });
  }
}
