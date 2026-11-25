package com.tungph7.learnpostgres.feature.coupon.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * 9 kịch bản coupon endpoint — đi qua đủ controller → service → Postgres thật.
 *
 * <p>Khác {@code CouponControllerWebMvcTest}: ở đó service là mock, nên test chỉ trả lời
 * "controller có route/serialize đúng không". Ở đây service và DB là thật, nên test trả lời được
 * bốn câu mà slice không đụng tới:
 *
 * <ul>
 *   <li>CHECK và EXCLUDE của V10 có thật sự nổ khi đi qua HTTP không;
 *   <li>và nếu nổ thì {@code GlobalExceptionHandler} có dịch đúng mã lỗi không — quan trọng vì
 *       {@code CouponServiceImpl.create} dùng {@code save()} chứ không {@code saveAndFlush()}, nên
 *       vi phạm chỉ lộ ra lúc COMMIT, sau khi đã ra khỏi repository proxy;
 *   <li>discount tính từ dữ liệu thật trong DB có khớp con số kỳ vọng không;
 *   <li>status code đi ra ngoài dây có đúng không — AC-10 từng trả 500 cho một request sai của
 *       client, xem {@link #invertedPeriodIsClientErrorNotServerError()}.
 * </ul>
 *
 * <p>Không có endpoint {@code redeem} / {@code revoke} (cố ý — xem {@code CouponController}), nên
 * test này không sinh dòng {@code coupon_redemptions} nào; phần đó thuộc về {@link
 * CouponConcurrencyIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class CouponControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String BASE = "/api/v1/coupons";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;

  /**
   * Hikari đặt {@code auto-commit: false}. Trong {@code @SpringBootTest} không có transaction bao
   * quanh, nên mọi ghi qua {@code JdbcTemplate} sẽ bị rollback lúc trả connection về pool — im
   * lặng, không lỗi. Seed và cleanup phải bọc trong {@code TransactionTemplate} mới thật sự commit.
   */
  @Autowired TransactionTemplate tx;

  private UUID customerId;

  @BeforeEach
  void setUp() {
    cleanup();
    customerId = seedCustomer();
  }

  /**
   * Không dùng {@code @Transactional} trên class vì cần mỗi request commit thật. Hệ quả là dữ liệu
   * tồn tại sau test và phải tự dọn.
   *
   * <p>Coupon bắt buộc phải xoá: EXCLUDE constraint nhớ theo {@code (code, valid_period)}, nên để
   * sót một row {@code CTRL-%} active là lần chạy sau tạo lại đúng code đó sẽ ăn 409 — test đỏ mà
   * nhìn code test thì không hiểu tại sao.
   *
   * <p>CHỈ {@code @AfterEach}: {@link #setUp()} đã gọi tay ở dòng đầu. Gắn thêm {@code @BeforeEach}
   * là thành hai method {@code @BeforeEach} không có thứ tự đảm bảo giữa chúng.
   */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM app.coupons WHERE code LIKE 'CTRL-%'");
          jdbc.update("DELETE FROM app.customers WHERE email LIKE 'ctrlcpn-%'");
        });
  }

  // ================================================================
  // POST /coupons — admin create
  // ================================================================

  @Test
  @DisplayName("POST /coupons — 201 + Location, valid_period phẳng ra validFrom/validTo")
  void createsCouponAndReturnsLocation() throws Exception {
    OffsetDateTime from = OffsetDateTime.parse("2026-06-01T00:00:00Z");
    OffsetDateTime to = OffsetDateTime.parse("2026-07-01T00:00:00Z");

    create("CTRL-SUM25", "PERCENT", "25", from, to)
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.code").value("CTRL-SUM25"))
        .andExpect(jsonPath("$.type").value("PERCENT"))
        .andExpect(jsonPath("$.usedCount").value(0))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.validFrom").value("2026-06-01T00:00:00Z"))
        .andExpect(jsonPath("$.validTo").value("2026-07-01T00:00:00Z"));

    // Row có thật trong DB, không chỉ có trong response.
    assertThat(countCoupons("CTRL-SUM25")).isOne();
  }

  /**
   * AC-08. {@code value = 150} qua được Bean Validation ({@code @PositiveOrZero}) vì muốn biết 150
   * sai hay đúng thì phải nhìn cả {@code type} — đó là việc của {@code ck_coupons_value}.
   *
   * <p>Điểm đáng test: vi phạm xảy ra lúc COMMIT chứ không lúc {@code save()}, nên nó đi qua {@code
   * JpaTransactionManager} rồi mới được dịch. Nếu chuỗi dịch đứt ở đâu đó thì client nhận 500 thay
   * vì 400 — slice test với {@code flush()} tay không phát hiện được.
   */
  @Test
  @DisplayName("POST /coupons — PERCENT value=150 → 400 CPN_INVALID_VALUE (CHECK ở DB)")
  void percentAbove100IsRejectedByCheckConstraint() throws Exception {
    OffsetDateTime from = OffsetDateTime.now().plusDays(1);

    create("CTRL-BAD", "PERCENT", "150", from, from.plusDays(10))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CPN_INVALID_VALUE"));

    assertThat(countCoupons("CTRL-BAD")).isZero();
  }

  /** AC-11. */
  @Test
  @DisplayName("POST /coupons — cùng code, khoảng thời gian chồng lấn → 409 CPN_PERIOD_OVERLAPS")
  void overlappingPeriodSameCodeIsConflict() throws Exception {
    OffsetDateTime from = OffsetDateTime.now().plusDays(1);

    create("CTRL-DUP", "PERCENT", "10", from, from.plusDays(30)).andExpect(status().isCreated());

    // Bắt đầu muộn hơn nhưng vẫn nằm trong khoảng cũ.
    create("CTRL-DUP", "PERCENT", "20", from.plusDays(10), from.plusDays(40))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CPN_PERIOD_OVERLAPS"));

    assertThat(countCoupons("CTRL-DUP")).isOne();
  }

  /**
   * AC-12 — nửa còn lại của AC-11, và là lý do V10 dùng EXCLUDE chứ không UNIQUE index.
   *
   * <p>Hai campaign sát nhau: {@code [d1, d2)} và {@code [d2, d3)}. Range nửa-mở nên {@code d2} chỉ
   * thuộc campaign thứ hai → {@code &&} cho false → cả hai cùng sống. Một {@code UNIQUE (code)
   * WHERE is_active} sẽ chặn cái thứ hai và làm test này đỏ.
   */
  @Test
  @DisplayName("POST /coupons — cùng code, khoảng nối tiếp (touch) → cả 2 đều 201")
  void adjacentPeriodsSameCodeBothSucceed() throws Exception {
    OffsetDateTime d1 = OffsetDateTime.now().plusDays(1);
    OffsetDateTime d2 = d1.plusDays(30);
    OffsetDateTime d3 = d2.plusDays(30);

    create("CTRL-SEQ", "PERCENT", "10", d1, d2).andExpect(status().isCreated());
    create("CTRL-SEQ", "PERCENT", "20", d2, d3).andExpect(status().isCreated());

    assertThat(countCoupons("CTRL-SEQ")).isEqualTo(2);
  }

  /**
   * AC-09. Đối xứng với AC-08 nhưng dừng sớm hơn một lớp: {@code @PositiveOrZero} nhìn một field là
   * đủ kết luận, nên request bị chặn ở edge và không có SQL nào chạy.
   */
  @Test
  @DisplayName("POST /coupons — AMOUNT value âm → 400 VALIDATION_FAILED, chặn ở Bean Validation")
  void negativeAmountIsRejectedBeforeReachingDatabase() throws Exception {
    OffsetDateTime from = OffsetDateTime.now().plusDays(1);

    create("CTRL-NEG", "AMOUNT", "-1", from, from.plusDays(10))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("value"));

    assertThat(countCoupons("CTRL-NEG")).isZero();
  }

  /**
   * AC-10. Trước khi có {@code CreateCouponRequest.isPeriodOrdered()}, request này ném {@code
   * IllegalArgumentException} từ {@code TstzRange}, không handler nào bắt, và client nhận
   * <b>500</b> — tức là API đổ lỗi cho server vì một request sai của client.
   *
   * <p>Giữ test này ở tầng integration chứ không phải unit: nó canh đúng cái đã hỏng, là <em>status
   * code đi ra ngoài dây</em>, chứ không phải việc constraint có tồn tại hay không.
   */
  @Test
  @DisplayName("POST /coupons — validTo trước validFrom → 400 (không phải 500)")
  void invertedPeriodIsClientErrorNotServerError() throws Exception {
    OffsetDateTime from = OffsetDateTime.now().plusDays(10);

    create("CTRL-INV", "PERCENT", "10", from, from.minusDays(5))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("periodOrdered"))
        .andExpect(jsonPath("$.errors[0].message").value("validTo phải sau validFrom"));

    assertThat(countCoupons("CTRL-INV")).isZero();
  }

  // ================================================================
  // POST /coupons/validate
  // ================================================================

  /** AC-02: PERCENT 25 trên subtotal 1.000.000 → 250.000. */
  @Test
  @DisplayName("POST /validate — mã đang hiệu lực → valid=true, discountAmount đúng")
  void validateReturnsDiscountForEligibleOrder() throws Exception {
    createNowActive("CTRL-OK", "PERCENT", "25", null);

    ValidateCouponResponse body = validate("CTRL-OK", "1000000");

    assertThat(body.valid()).isTrue();
    assertThat(body.code()).isEqualTo("CTRL-OK");
    assertThat(body.couponId()).isNotNull();
    assertThat(body.discountAmount()).isEqualByComparingTo(new BigDecimal("250000"));
    assertThat(body.freeShipping()).isFalse();
    assertThat(body.errorCode()).isNull();
  }

  /**
   * AC-03. Điểm chính không phải con số mà là <b>HTTP status 200</b>: gõ mã chưa đủ điều kiện là
   * luồng bình thường của giỏ hàng, không phải lỗi giao thức.
   */
  @Test
  @DisplayName("POST /validate — subtotal dưới min_order → vẫn 200, valid=false")
  void validateBelowMinimumOrderIsNotAnHttpError() throws Exception {
    createNowActive("CTRL-MIN", "AMOUNT", "100000", "500000");

    ValidateCouponResponse body = validate("CTRL-MIN", "400000");

    assertThat(body.valid()).isFalse();
    assertThat(body.errorCode()).isEqualTo("CPN_MIN_ORDER_NOT_MET");
    assertThat(body.message()).contains("500000");
    assertThat(body.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * AC-14. Coupon có thật, {@code is_active = TRUE}, chỉ là {@code now()} chưa rơi vào khoảng —
   * phải ra CPN_EXPIRED chứ không phải CPN_NOT_FOUND.
   */
  @Test
  @DisplayName("POST /validate — campaign chưa tới kỳ → 200, errorCode=CPN_EXPIRED")
  void validateOutsideEveryPeriodReturnsExpired() throws Exception {
    OffsetDateTime from = OffsetDateTime.now().plusDays(10);
    create("CTRL-FUTURE", "PERCENT", "30", from, from.plusDays(30)).andExpect(status().isCreated());

    ValidateCouponResponse body = validate("CTRL-FUTURE", "1000000");

    assertThat(body.valid()).isFalse();
    assertThat(body.errorCode()).isEqualTo("CPN_EXPIRED");
    assertThat(body.couponId()).isNull();
  }

  // ================================================================
  // Helpers
  // ================================================================

  private ResultActions create(
      String code, String type, String value, OffsetDateTime from, OffsetDateTime to)
      throws Exception {
    return mockMvc.perform(
        post(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(code, type, value, from, to, null)));
  }

  /** Coupon đang hiệu lực NGAY BÂY GIỜ — {@code validate()} so với {@code OffsetDateTime.now()}. */
  private void createNowActive(String code, String type, String value, String minOrderAmount)
      throws Exception {
    String body =
        createBody(
            code,
            type,
            value,
            OffsetDateTime.now().minusDays(1),
            OffsetDateTime.now().plusDays(1),
            minOrderAmount);

    mockMvc
        .perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  private ValidateCouponResponse validate(String code, String subtotal) throws Exception {
    String json =
        mockMvc
            .perform(
                post(BASE + "/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"code":"%s","orderSubtotal":%s,"customerId":"%s"}
                        """
                            .formatted(code, subtotal, customerId)))
            // Validate LUÔN 200 — kể cả khi mã không dùng được.
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Đọc về record thay vì jsonPath: discountAmount là BigDecimal, so sánh qua jsonPath sẽ
    // phụ thuộc vào JsonPath trả Double hay BigDecimal — isEqualByComparingTo thì không.
    return objectMapper.readValue(json, ValidateCouponResponse.class);
  }

  private static String createBody(
      String code,
      String type,
      String value,
      OffsetDateTime from,
      OffsetDateTime to,
      String minOrderAmount) {
    String minOrder = minOrderAmount == null ? "" : ",\"minOrderAmount\":" + minOrderAmount;
    return """
           {"code":"%s","type":"%s","value":%s,"validFrom":"%s","validTo":"%s"%s}
           """
        .formatted(code, type, value, from, to, minOrder);
  }

  private UUID seedCustomer() {
    UUID id = UUID.randomUUID();
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "INSERT INTO app.customers (id, email, first_name, last_name) VALUES (?,?,?,?)",
                id,
                "ctrlcpn-" + id + "@example.com",
                "Ctrl",
                "Coupon"));
    return id;
  }

  private int countCoupons(String code) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM app.coupons WHERE code = ?", Integer.class, code);
  }
}
