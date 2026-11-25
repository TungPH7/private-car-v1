package com.tungph7.learnpostgres.feature.inventory.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

/** 6 kịch bản admin endpoint của F03 — đi qua đủ controller → service → DB thật. */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class InventoryControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String BASE = "/api/v1/inventory";

  @Autowired MockMvc mockMvc;
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
    write(
        "INSERT INTO app.products (id, name, slug, price, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        productId,
        "Ctrl Test",
        "ctrl-" + productId,
        new BigDecimal("199"));
  }

  /**
   * Dọn là bắt buộc: {@code inventory_movements} có FK {@code ON DELETE RESTRICT} tới products
   * (V9), nên movement còn sót sẽ chặn {@code repository.deleteAll()} ở {@code @AfterEach} của
   * {@code ProductIntegrationTest} và làm đỏ test của feature khác.
   *
   * <p>Thứ tự bắt buộc: movement trước, product sau ({@code product_inventory} tự CASCADE).
   */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM app.inventory_movements WHERE product_id IN"
                  + " (SELECT id FROM app.products WHERE slug LIKE 'ctrl-%')");
          jdbc.update("DELETE FROM app.products WHERE slug LIKE 'ctrl-%'");
        });
  }

  private void write(String sql, Object... args) {
    tx.executeWithoutResult(status -> jdbc.update(sql, args));
  }

  private void stockIn(int qty) throws Exception {
    mockMvc
        .perform(
            post(BASE + "/{id}/stock-in", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"quantity": %d, "reason": "seed", "actor": "admin"}
                    """
                        .formatted(qty)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("POST /init — idempotent, gọi 2 lần đều 200 và đều all-zero")
  void initIsIdempotent() throws Exception {
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(post(BASE + "/{id}/init", productId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.productId").value(productId.toString()))
          .andExpect(jsonPath("$.onHand").value(0))
          .andExpect(jsonPath("$.available").value(0));
    }
  }

  @Test
  @DisplayName("POST /stock-in — trả snapshot mới và ghi 1 movement STOCK_IN")
  void stockInReturnsSnapshotAndLogsMovement() throws Exception {
    mockMvc.perform(post(BASE + "/{id}/init", productId)).andExpect(status().isOk());

    mockMvc
        .perform(
            post(BASE + "/{id}/stock-in", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"quantity": 100, "reason": "from supplier", "actor": "admin-1"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onHand").value(100))
        .andExpect(jsonPath("$.available").value(100))
        .andExpect(jsonPath("$.lastRestockAt").isNotEmpty());

    mockMvc
        .perform(get(BASE + "/{id}/movements", productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].movementType").value("STOCK_IN"))
        .andExpect(jsonPath("$[0].deltaOnHand").value(100))
        .andExpect(jsonPath("$[0].onHandAfter").value(100))
        .andExpect(jsonPath("$[0].actor").value("admin-1"));
  }

  @Test
  @DisplayName("POST /adjust — delta đẩy on_hand xuống âm → 409 INV_INSUFFICIENT_STOCK")
  void adjustBelowZeroIsConflict() throws Exception {
    mockMvc.perform(post(BASE + "/{id}/init", productId)).andExpect(status().isOk());
    stockIn(10);

    mockMvc
        .perform(
            post(BASE + "/{id}/adjust", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"deltaOnHand": -50, "deltaReserved": 0,
                     "reason": "test negative", "actor": "admin"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INV_INSUFFICIENT_STOCK"));
  }

  @Test
  @DisplayName("POST /stock-in — quantity=0 vi phạm @Valid → 400 VALIDATION_FAILED")
  void stockInRejectsZeroQuantity() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/{id}/stock-in", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"quantity": 0, "reason": "x", "actor": "admin"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("quantity"));
  }

  @Test
  @DisplayName("GET /{id} — chưa init inventory → 404 INV_NOT_FOUND")
  void getUninitializedIsNotFound() throws Exception {
    mockMvc
        .perform(get(BASE + "/{id}", productId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INV_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET /low-stock — product dưới reorder_point có trong danh sách")
  void lowStockListsProductBelowReorderPoint() throws Exception {
    mockMvc.perform(post(BASE + "/{id}/init", productId)).andExpect(status().isOk());
    stockIn(3);
    write("UPDATE app.product_inventory SET reorder_point = 10 WHERE product_id = ?", productId);

    mockMvc
        .perform(get(BASE + "/low-stock").param("threshold", "5").param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.productId == '%s')].available".formatted(productId)).value(3))
        .andExpect(
            jsonPath("$[?(@.productId == '%s')].productName".formatted(productId))
                .value("Ctrl Test"));
  }

  @Test
  @DisplayName("GET /reconcile — khớp thì inSync=true, sửa lén DB thì phát hiện drift")
  void reconcileDetectsDrift() throws Exception {
    mockMvc.perform(post(BASE + "/{id}/init", productId)).andExpect(status().isOk());
    stockIn(100);

    mockMvc
        .perform(get(BASE + "/{id}/reconcile", productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inSync").value(true))
        .andExpect(jsonPath("$.onHandDrift").value(0));

    // Sửa thẳng DB, không ghi movement → đúng thứ reconcile sinh ra để bắt.
    write(
        "UPDATE app.product_inventory SET on_hand = on_hand + 50 WHERE product_id = ?", productId);

    mockMvc
        .perform(get(BASE + "/{id}/reconcile", productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inSync").value(false))
        .andExpect(jsonPath("$.onHandDrift").value(50));
  }
}
