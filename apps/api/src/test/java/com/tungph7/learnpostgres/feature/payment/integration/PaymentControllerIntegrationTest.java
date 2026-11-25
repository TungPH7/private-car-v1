package com.tungph7.learnpostgres.feature.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.common.security.RequestActor;
import com.tungph7.learnpostgres.feature.order.dto.OrderResponse;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentResponse;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResponse;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.ResultKind;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Payment đi hết HTTP → controller → service → Postgres, không mock gì.
 *
 * <p>Hai lớp test đã có mỗi lớp thiếu một nửa: {@code PaymentControllerWebMvcTest} có HTTP nhưng
 * service là mock; {@link PaymentServiceIntegrationTest} có Postgres nhưng không qua HTTP. Ở đây
 * kiểm được những gì chỉ đúng khi ghép cả hai: 201 / 200 và {@code Location} trỏ tới URL đọc được;
 * payload gateway đi từ body JSON vào {@code jsonb} giữ nguyên từng chữ số; mã lỗi sinh ra từ dữ
 * liệu thật — kể cả lỗi do trigger V15 raise, đi qua handler thật; request bị từ chối không để lại
 * row nào; và số câu SQL của một request tạo payment (AC-18).
 *
 * <p>Order seed bằng SQL ({@link PaymentTestData}): PENDING, không items — payment chỉ cần tổng
 * tiền và trạng thái của order. Actor là {@code "anonymous"}: repo chưa có đăng nhập, request không
 * có principal ({@link RequestActor}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class PaymentControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String ORDERS = "/api/v1/orders";
  private static final String PAYMENTS = "/api/v1/payments";
  private static final String REFUNDS = "/api/v1/refunds";
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private static final String STRIPE = "{\"gateway\":\"STRIPE\"}";

  /** Key của request bị từ chối trong {@link #rejectedCreateLeavesNoPayment}. */
  private static final String REJECTED_KEY = "pay-http-rejected";

  /** {@code detail} khi trigger V15 chặn TỔNG refund — service chặn một refund thì detail khác. */
  private static final String TRIGGER_EXCEEDS_DETAIL =
      "Tổng refund đang giữ tiền (PENDING + COMPLETED) vượt số tiền đã thu của payment.";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired EntityManagerFactory entityManagerFactory;

  private PaymentTestData data;

  @BeforeEach
  void setUp() {
    data = new PaymentTestData(jdbc, tx, "payment-http-");
    cleanup();
  }

  /**
   * BẮT BUỘC dọn: payment / refund giữ FK RESTRICT tới order. Chỉ {@code @AfterEach} — {@link
   * #setUp()} đã tự gọi ở dòng đầu (hai method {@code @BeforeEach} không có thứ tự đảm bảo).
   */
  @AfterEach
  void cleanup() {
    data.cleanup();
  }

  // ================================================================
  // Tạo payment
  // ================================================================

  @Test
  @DisplayName(
      "AC-01/02: tạo → 201 + Location đọc được, actor anonymous; gửi lặp cùng key → 200 EXISTING,"
          + " vẫn 1 row")
  void createsPaymentAndReplaysTheKey() throws Exception {
    UUID orderId = data.pendingOrder("300000.00");

    MvcResult first =
        createPayment(orderId, "pay-http-create", STRIPE)
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"amount\":300000.00")))
            .andReturn();

    PaymentIntentResult created = read(first, PaymentIntentResult.class);
    assertThat(created)
        .isEqualTo(
            new PaymentIntentResult(
                created.paymentId(),
                "PENDING",
                new BigDecimal("300000.00"),
                "VND",
                "STRIPE",
                ResultKind.NEW));
    // Location trỏ tới một URL đọc được: GET đúng URL đó ra payment vừa tạo.
    String location = first.getResponse().getHeader(HttpHeaders.LOCATION);
    assertThat(location).endsWith(PAYMENTS + "/" + created.paymentId());
    PaymentResponse payment = read(mockMvc.perform(get(location)), PaymentResponse.class);
    assertThat(payment.orderId()).isEqualTo(orderId);
    assertThat(payment.idempotencyKey()).isEqualTo("pay-http-create");
    assertThat(payment.status()).isEqualTo("PENDING");
    assertThat(payment.paymentReference()).isNull();
    assertThat(payment.capturedAt()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT created_by FROM app.payments WHERE id = ?",
                String.class,
                created.paymentId()))
        .isEqualTo(RequestActor.ANONYMOUS);

    // AC-02: gửi lặp đúng key → 200, cùng payment, không Location, không thêm row.
    PaymentIntentResult replay =
        read(
            createPayment(orderId, "pay-http-create", STRIPE)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)),
            PaymentIntentResult.class);
    assertThat(replay.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(replay.paymentId()).isEqualTo(created.paymentId());
    assertThat(data.count("SELECT count(*) FROM app.payments WHERE order_id = ?", orderId)).isOne();
  }

  /**
   * Mỗi kịch bản bị chặn ở một tầng khác nhau: Spring (header / body, trước controller), service
   * đọc order, partial UNIQUE {@code uq_payments_order_non_failed} (ON CONFLICT bỏ qua, service tra
   * lại theo key), và key đã có (service tra key trước tiên).
   */
  static Stream<Arguments> rejectedCreates() {
    return Stream.of(
        rejection(
            "thiếu header Idempotency-Key",
            400,
            "INVALID_PARAMETER",
            f -> new Create(f.pending(), null, STRIPE)),
        rejection(
            "AC-14 gateway PAYPAL",
            400,
            "MALFORMED_REQUEST",
            f -> new Create(f.pending(), REJECTED_KEY, "{\"gateway\":\"PAYPAL\"}")),
        rejection(
            "order không tồn tại",
            404,
            "ORD_NOT_FOUND",
            f -> new Create(UUID.randomUUID(), REJECTED_KEY, STRIPE)),
        rejection(
            "AC-08 order đã hủy",
            409,
            "PAY_ORDER_NOT_PAYABLE",
            f -> new Create(f.cancelled(), REJECTED_KEY, STRIPE)),
        rejection(
            "order tổng 0",
            409,
            "PAY_ORDER_NOT_PAYABLE",
            f -> new Create(f.zeroTotal(), REJECTED_KEY, STRIPE)),
        rejection(
            "key mới, order đang có payment",
            409,
            "PAY_ORDER_ALREADY_HAS_PAYMENT",
            f -> new Create(f.paying(), REJECTED_KEY, STRIPE)),
        rejection(
            "key đã dùng cho order khác",
            422,
            "PAY_IDEMPOTENCY_KEY_REUSED",
            f -> new Create(f.pending(), f.takenKey(), STRIPE)),
        rejection(
            "cùng key, gateway khác",
            422,
            "PAY_IDEMPOTENCY_KEY_REUSED",
            f -> new Create(f.paying(), f.takenKey(), "{\"gateway\":\"VNPAY\"}")));
  }

  @ParameterizedTest(name = "{0} → {1} {2}")
  @MethodSource("rejectedCreates")
  @DisplayName("tạo payment bị từ chối → đúng status + code, không thêm payment nào")
  void rejectedCreateLeavesNoPayment(
      String scenario, int expectedStatus, String code, Function<Fixture, Create> request)
      throws Exception {
    Fixture fixture = openFixture();
    int before = data.paymentCount();
    Create create = request.apply(fixture);

    createPayment(create.orderId(), create.key(), create.body())
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.code").value(code));

    assertThat(data.paymentCount()).isEqualTo(before);
  }

  /**
   * AC-18 ghi "1 INSERT, 1 SELECT order" — thực tế là 3 câu: service tra key TRƯỚC khi nhìn order
   * (bài 03 §5.1), rồi đọc order, rồi {@code INSERT ... ON CONFLICT DO NOTHING RETURNING *}. Gửi
   * lặp dừng ngay ở câu tra key.
   *
   * <p>Đếm bằng Hibernate statistics như {@code
   * OrderControllerIntegrationTest.detailIsOneStatement} (F05): {@code getPrepareStatementCount()}
   * là số {@code PreparedStatement} Hibernate xin từ JDBC — mỗi câu SQL một cái, gồm cả native
   * query. Bật tại chỗ, tắt trong {@code finally}: đó là bộ đếm dùng chung của cả {@code
   * SessionFactory}; không test nào chạy song song nên con số chỉ là của request đang đo.
   */
  @Test
  @DisplayName(
      "AC-18: tạo payment = 3 câu SQL (tra key, đọc order, INSERT ... RETURNING); gửi lặp = 1 câu")
  void createRunsThreeStatementsAndReplayOne() throws Exception {
    UUID orderId = data.pendingOrder("300000.00");
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    statistics.setStatisticsEnabled(true);
    try {
      createPayment(orderId, "pay-http-sql", STRIPE).andExpect(status().isCreated());
      assertThat(statistics.getPrepareStatementCount()).as("lần đầu").isEqualTo(3);

      statistics.clear();
      createPayment(orderId, "pay-http-sql", STRIPE).andExpect(status().isOk());
      assertThat(statistics.getPrepareStatementCount()).as("gửi lặp").isOne();
    } finally {
      statistics.setStatisticsEnabled(false);
      statistics.clear();
    }
  }

  // ================================================================
  // Webhook thanh toán
  // ================================================================

  /**
   * Hai con số trong payload được chọn để lộ bẫy {@code double} ({@code 04} §1.9): qua {@code
   * double}, {@code 300000.00} thành {@code 300000.0}, {@code 0.000039215686274509803} thành {@code
   * 3.9215686274509805E-5}. Phép {@code @>} so số theo GIÁ TRỊ, nên {@code 300000.0} vẫn khớp
   * {@code 300000.00} — còn {@code fxRate} thì không: hai chữ số cuối đã khác.
   */
  @Test
  @DisplayName(
      "AC-03/04: webhook → CAPTURED + order PAID; payload vào jsonb đúng từng chữ số; gửi lặp →"
          + " 200 ack, không ghi đè; mã khác → 409")
  void captureMarksOrderPaidAndKeepsPayloadExact() throws Exception {
    UUID orderId = data.pendingOrder("300000.00");
    UUID paymentId = created(orderId, "pay-http-capture", STRIPE).paymentId();

    ConfirmPaymentResult captured =
        read(
            confirmPayment(
                    paymentId,
                    """
                    {"paymentReference":"pi_http_capture",
                     "gatewayResponse":{"transactionId":"txn_http_capture","amount":300000.00,
                                        "fxRate":0.000039215686274509803}}
                    """)
                .andExpect(status().isOk()),
            ConfirmPaymentResult.class);
    assertThat(captured).isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", false));

    // Capture và order → PAID trong cùng transaction; lịch sử ghi actor "system" (markPaid, F05).
    OrderResponse order = fetch(OrderResponse.class, ORDERS + "/{id}", orderId);
    assertThat(order.status()).isEqualTo("PAID");
    assertThat(order.paidAt()).isNotNull();
    assertThat(
            fetch(
                new TypeReference<List<StatusHistoryResponse>>() {},
                ORDERS + "/{id}/history",
                orderId))
        .extracting(
            StatusHistoryResponse::fromStatus,
            StatusHistoryResponse::toStatus,
            StatusHistoryResponse::reason,
            StatusHistoryResponse::actor)
        .containsExactly(tuple("PENDING", "PAID", "Payment confirmed", "system"));

    // jsonb sắp lại key và thêm khoảng trắng, nhưng giữ nguyên từng chữ số.
    String payload = data.gatewayResponse(paymentId);
    assertThat(payload)
        .isEqualTo(
            "{\"amount\": 300000.00, \"fxRate\": 0.000039215686274509803,"
                + " \"transactionId\": \"txn_http_capture\"}");
    // Payload là object thật nên containment khớp — với đúng con số đã gửi.
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM app.payments WHERE gateway_response @> CAST(? AS jsonb)",
                UUID.class,
                "{\"fxRate\": 0.000039215686274509803}"))
        .containsExactly(paymentId);

    // AC-04: gửi lặp đúng mã → 200 ack; payload + lịch sử của lần đầu giữ nguyên.
    assertThat(
            read(
                confirmPayment(paymentId, "{\"paymentReference\":\"pi_http_capture\"}")
                    .andExpect(status().isOk()),
                ConfirmPaymentResult.class))
        .isEqualTo(new ConfirmPaymentResult(paymentId, "CAPTURED", true));
    assertThat(data.gatewayResponse(paymentId)).isEqualTo(payload);
    assertThat(data.historyRows(orderId, "PAID")).isOne();

    // Mã khác: gateway có thể đã trừ tiền lần nữa — 409, việc của đối soát.
    confirmPayment(paymentId, "{\"paymentReference\":\"pi_http_other\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_INVALID_STATE"));

    PaymentResponse payment = fetch(PaymentResponse.class, PAYMENTS + "/{id}", paymentId);
    assertThat(payment.status()).isEqualTo("CAPTURED");
    assertThat(payment.paymentReference()).isEqualTo("pi_http_capture");
    assertThat(payment.capturedAt()).isNotNull();
  }

  // ================================================================
  // Hoàn tiền
  // ================================================================

  /**
   * AC-05/06/07. Gửi lặp TUẦN TỰ không bao giờ chạm trigger: service tra key trước rồi trả
   * EXISTING. Hai lần gửi lặp CHẠY CHỒNG mới cùng tới câu INSERT — ca đó ở {@code
   * PaymentConcurrencyIntegrationTest.concurrentRetriesOfALargeRefundCreateOne}.
   */
  @Test
  @DisplayName(
      "AC-05/06/07: hoàn 50% → PARTIALLY_REFUNDED, hoàn nốt → REFUNDED; gửi lặp key ở mọi bước →"
          + " 200 EXISTING")
  void refundsWalkToRefunded() throws Exception {
    UUID paymentId = capturedPayment("pay-http-refund", "pi_http_refund");

    MvcResult first =
        requestRefund(paymentId, "pay-http-r1", "150000", "Giao thieu hang")
            .andExpect(status().isCreated())
            .andReturn();
    RefundResult r1 = read(first, RefundResult.class);
    assertThat(r1.kind()).isEqualTo(ResultKind.NEW);
    assertThat(r1.status()).isEqualTo("PENDING");
    assertThat(r1.amount()).isEqualByComparingTo("150000");
    // Location trỏ tới địa chỉ riêng của refund, và đọc được.
    String location = first.getResponse().getHeader(HttpHeaders.LOCATION);
    assertThat(location).endsWith(REFUNDS + "/" + r1.refundId());
    RefundResponse pending = read(mockMvc.perform(get(location)), RefundResponse.class);
    assertThat(pending.paymentId()).isEqualTo(paymentId);
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.actor()).isEqualTo(RequestActor.ANONYMOUS);
    assertThat(pending.completedAt()).isNull();

    // AC-07: gửi lặp đúng key → 200 EXISTING, không Location.
    RefundResult replay =
        read(
            requestRefund(paymentId, "pay-http-r1", "150000", "Giao thieu hang")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)),
            RefundResult.class);
    assertThat(replay.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(replay.refundId()).isEqualTo(r1.refundId());

    // AC-05: gateway xác nhận → PARTIALLY_REFUNDED; webhook hoàn tiền gửi lặp → ack.
    assertThat(
            read(
                confirmRefund(
                        r1.refundId(),
                        "{\"refundReference\":\"re_http_1\","
                            + "\"gatewayResponse\":{\"status\":\"succeeded\"}}")
                    .andExpect(status().isOk()),
                ConfirmRefundResult.class))
        .isEqualTo(
            new ConfirmRefundResult(
                r1.refundId(), "COMPLETED", paymentId, "PARTIALLY_REFUNDED", false));
    assertThat(
            read(
                    confirmRefund(r1.refundId(), "{\"refundReference\":\"re_http_1\"}")
                        .andExpect(status().isOk()),
                    ConfirmRefundResult.class)
                .alreadyCompleted())
        .isTrue();
    // Webhook thanh toán gửi lại SAU khi đã hoàn một phần vẫn được ack.
    assertThat(
            read(
                confirmPayment(paymentId, "{\"paymentReference\":\"pi_http_refund\"}")
                    .andExpect(status().isOk()),
                ConfirmPaymentResult.class))
        .isEqualTo(new ConfirmPaymentResult(paymentId, "PARTIALLY_REFUNDED", true));

    // AC-06: hoàn nốt 50% → REFUNDED.
    RefundResult r2 =
        read(
            requestRefund(paymentId, "pay-http-r2", "150000.00", "Huy phan con lai")
                .andExpect(status().isCreated()),
            RefundResult.class);
    assertThat(
            read(
                    confirmRefund(r2.refundId(), "{\"refundReference\":\"re_http_2\"}")
                        .andExpect(status().isOk()),
                    ConfirmRefundResult.class)
                .paymentStatus())
        .isEqualTo("REFUNDED");

    // Gửi lặp key của refund đầu SAU khi đã hoàn hết → vẫn refund cũ, trạng thái hiện tại.
    RefundResult late =
        read(
            requestRefund(paymentId, "pay-http-r1", "150000", "Giao thieu hang")
                .andExpect(status().isOk()),
            RefundResult.class);
    assertThat(late.kind()).isEqualTo(ResultKind.EXISTING);
    assertThat(late.status()).isEqualTo("COMPLETED");
    // Key mới thì không: payment đã hoàn hết.
    requestRefund(paymentId, "pay-http-r3", "1", "Them")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_INVALID_STATE"));

    PaymentResponse payment = fetch(PaymentResponse.class, PAYMENTS + "/{id}", paymentId);
    assertThat(payment.status()).isEqualTo("REFUNDED");
    assertThat(payment.paymentReference()).isEqualTo("pi_http_refund");
    assertThat(payment.capturedAt()).isNotNull();
    assertThat(
            fetch(
                new TypeReference<List<RefundResponse>>() {},
                PAYMENTS + "/{id}/refunds",
                paymentId))
        .extracting(RefundResponse::refundReference, RefundResponse::status, RefundResponse::actor)
        .containsExactly(
            tuple("re_http_2", "COMPLETED", RequestActor.ANONYMOUS),
            tuple("re_http_1", "COMPLETED", RequestActor.ANONYMOUS));
  }

  /**
   * AC-10/11. Hai 409 cùng mã, khác {@code detail} vì khác tầng chặn: refund tự nó lớn hơn cả số đã
   * thu → service chặn trước khi ghi; tổng refund đang giữ tiền vượt → trigger V15 chặn sau khi
   * ghi, handler dịch message của trigger. Refund PENDING (chưa xác nhận) đã giữ tiền.
   */
  @Test
  @DisplayName(
      "AC-10/11: refund > số đã thu → 409 từ service; 60% rồi 50% → 409 từ trigger; vừa đủ phần"
          + " còn lại → 201")
  void refundBeyondCapturedAmountIsRejected() throws Exception {
    UUID paymentId = capturedPayment("pay-http-exceed", "pi_http_exceed");

    // AC-10: một refund lớn hơn cả số đã thu — 1 xu.
    requestRefund(paymentId, "pay-http-x1", "300000.01", "Qua tay")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_REFUND_EXCEEDS_PAYMENT"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Refund 300000.01 vượt số tiền đã thu 300000.00 của payment %s."
                        .formatted(paymentId)));

    // AC-11: 60% (còn PENDING) + 50% > 100% → trigger.
    requestRefund(paymentId, "pay-http-x2", "180000", "60%").andExpect(status().isCreated());
    requestRefund(paymentId, "pay-http-x3", "150000", "50%")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_REFUND_EXCEEDS_PAYMENT"))
        .andExpect(jsonPath("$.detail").value(TRIGGER_EXCEEDS_DETAIL));

    // Vừa đúng phần còn lại: tổng = số đã thu → qua (điều kiện là ≤).
    requestRefund(paymentId, "pay-http-x4", "120000", "40%").andExpect(status().isCreated());

    assertThat(
            jdbc.queryForList(
                "SELECT idempotency_key FROM app.payment_refunds WHERE payment_id = ?"
                    + " ORDER BY idempotency_key",
                String.class,
                paymentId))
        .containsExactly("pay-http-x2", "pay-http-x4");
    // Chưa refund nào COMPLETED → payment vẫn CAPTURED.
    assertThat(data.paymentStatus(paymentId)).isEqualTo("CAPTURED");
  }

  /**
   * Refund FAILED (gateway từ chối hoàn tiền) không giữ tiền nữa: trigger chỉ cộng PENDING +
   * COMPLETED. API chưa có đường đánh refund FAILED ({@code 04} §9) — dựng bằng SQL.
   */
  @Test
  @DisplayName(
      "refund FAILED nhả phần tiền đang giữ → hoàn lại được bằng key mới; xác nhận refund FAILED →"
          + " 409")
  void failedRefundReleasesItsAmount() throws Exception {
    UUID paymentId = capturedPayment("pay-http-release", "pi_http_release");
    RefundResult held =
        read(
            requestRefund(paymentId, "pay-http-f1", "300000", "Hoan toan bo")
                .andExpect(status().isCreated()),
            RefundResult.class);
    // Refund PENDING đang giữ đủ 300.000 → thêm 1 đồng cũng vượt.
    requestRefund(paymentId, "pay-http-f2", "1", "Them")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(TRIGGER_EXCEEDS_DETAIL));

    assertThat(data.failRefund(held.refundId())).isOne();

    RefundResponse failed = fetch(RefundResponse.class, REFUNDS + "/{id}", held.refundId());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.completedAt()).isNull();
    confirmRefund(held.refundId(), "{\"refundReference\":\"re_http_late\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_INVALID_STATE"));

    RefundResult again =
        read(
            requestRefund(paymentId, "pay-http-f3", "300000", "Hoan lai lan 2")
                .andExpect(status().isCreated()),
            RefundResult.class);
    assertThat(
            read(
                    confirmRefund(again.refundId(), "{\"refundReference\":\"re_http_f3\"}")
                        .andExpect(status().isOk()),
                    ConfirmRefundResult.class)
                .paymentStatus())
        .isEqualTo("REFUNDED");
    // Refund FAILED vẫn nằm trong danh sách — lịch sử không mất.
    assertThat(
            fetch(
                new TypeReference<List<RefundResponse>>() {},
                PAYMENTS + "/{id}/refunds",
                paymentId))
        .extracting(RefundResponse::id, RefundResponse::status)
        .containsExactly(tuple(again.refundId(), "COMPLETED"), tuple(held.refundId(), "FAILED"));
  }

  // ================================================================
  // Đánh fail
  // ================================================================

  /**
   * AC-12 và đường thanh toán lại: FAILED nằm ngoài predicate của {@code
   * uq_payments_order_non_failed}, nên order tạo được payment mới — với key mới.
   */
  @Test
  @DisplayName(
      "AC-12: payment FAILED → webhook tới sau → 409; key mới thanh toán lại được; đã thu tiền thì"
          + " không fail được")
  void failedPaymentFreesTheOrder() throws Exception {
    UUID orderId = data.pendingOrder("300000.00");
    UUID vnpay = created(orderId, "pay-http-vnpay", "{\"gateway\":\"VNPAY\"}").paymentId();

    PaymentResponse failed =
        read(
            failPayment(vnpay, "Khach huy tren trang VNPAY").andExpect(status().isOk()),
            PaymentResponse.class);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.failureReason()).isEqualTo("Khach huy tren trang VNPAY");
    assertThat(failed.failedAt()).isNotNull();
    // Gọi lại → no-op, giữ lý do + thời điểm của lần đầu.
    PaymentResponse again =
        read(failPayment(vnpay, "Lan hai").andExpect(status().isOk()), PaymentResponse.class);
    assertThat(again.failureReason()).isEqualTo("Khach huy tren trang VNPAY");
    assertThat(again.failedAt()).isEqualTo(failed.failedAt());

    // AC-12: webhook tới cho payment đã FAILED → 409, order vẫn chờ thanh toán.
    confirmPayment(vnpay, "{\"paymentReference\":\"vnp_http_late\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_INVALID_STATE"));
    assertThat(data.orderStatus(orderId)).isEqualTo("PENDING");

    UUID momo = created(orderId, "pay-http-momo", "{\"gateway\":\"MOMO\"}").paymentId();
    confirmPayment(momo, "{\"paymentReference\":\"momo_http_1\"}").andExpect(status().isOk());
    assertThat(data.orderStatus(orderId)).isEqualTo("PAID");
    // Đã thu tiền thì không fail được — hoàn bằng refund.
    failPayment(momo, "Nham")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAY_INVALID_STATE"));

    assertThat(
            fetch(
                new TypeReference<List<PaymentResponse>>() {},
                ORDERS + "/{orderId}/payments",
                orderId))
        .extracting(PaymentResponse::id, PaymentResponse::gateway, PaymentResponse::status)
        .containsExactly(tuple(momo, "MOMO", "CAPTURED"), tuple(vnpay, "VNPAY", "FAILED"));
  }

  // ================================================================
  // Helpers
  // ================================================================

  /** {@code key = null}: không gửi header {@code Idempotency-Key}. */
  private ResultActions createPayment(UUID orderId, String key, String body) throws Exception {
    MockHttpServletRequestBuilder request =
        post(ORDERS + "/{orderId}/payments", orderId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (key != null) {
      request.header(IDEMPOTENCY_KEY, key);
    }
    return mockMvc.perform(request);
  }

  private PaymentIntentResult created(UUID orderId, String key, String body) throws Exception {
    return read(
        createPayment(orderId, key, body).andExpect(status().isCreated()),
        PaymentIntentResult.class);
  }

  private ResultActions confirmPayment(UUID paymentId, String body) throws Exception {
    return mockMvc.perform(
        post(PAYMENTS + "/{id}/confirm", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions failPayment(UUID paymentId, String reason) throws Exception {
    return mockMvc.perform(
        post(PAYMENTS + "/{id}/fail", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"%s\"}".formatted(reason)));
  }

  private ResultActions requestRefund(UUID paymentId, String key, String amount, String reason)
      throws Exception {
    return mockMvc.perform(
        post(PAYMENTS + "/{id}/refunds", paymentId)
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_KEY, key)
            .content("{\"amount\":%s,\"reason\":\"%s\"}".formatted(amount, reason)));
  }

  private ResultActions confirmRefund(UUID refundId, String body) throws Exception {
    return mockMvc.perform(
        post(REFUNDS + "/{id}/confirm", refundId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** Payment 300.000 đã capture với mã {@code reference} — order đã PAID. */
  private UUID capturedPayment(String key, String reference) throws Exception {
    UUID paymentId = created(data.pendingOrder("300000.00"), key, STRIPE).paymentId();
    confirmPayment(paymentId, "{\"paymentReference\":\"%s\"}".formatted(reference))
        .andExpect(status().isOk());
    return paymentId;
  }

  private <T> T fetch(Class<T> type, String uriTemplate, Object... vars) throws Exception {
    return read(mockMvc.perform(get(uriTemplate, vars)), type);
  }

  private <T> T fetch(TypeReference<T> type, String uriTemplate, Object... vars) throws Exception {
    String json =
        mockMvc
            .perform(get(uriTemplate, vars))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(json, type);
  }

  /**
   * Chỉ đọc body của response 2xx: body lỗi (ProblemDetail) đọc vào record kết quả không ra gì có
   * nghĩa, nên dừng ngay ở status sai cho thông báo lỗi rõ. Status chính xác (201 hay 200) do từng
   * test tự kiểm.
   */
  private <T> T read(ResultActions actions, Class<T> type) throws Exception {
    return read(actions.andExpect(status().is2xxSuccessful()).andReturn(), type);
  }

  /**
   * Đọc body về record thay vì so bằng {@code jsonPath}: tiền là {@code BigDecimal}, còn JsonPath
   * trả {@code Double} hay {@code BigDecimal} là tuỳ provider (cùng lý do với {@code
   * OrderControllerIntegrationTest}).
   */
  private <T> T read(MvcResult result, Class<T> type) throws Exception {
    return objectMapper.readValue(result.getResponse().getContentAsString(), type);
  }

  private static Arguments rejection(
      String scenario, int status, String code, Function<Fixture, Create> request) {
    return Arguments.of(scenario, status, code, request);
  }

  /**
   * Đủ order cho mọi kịch bản của {@link #rejectedCreateLeavesNoPayment}: một order chờ thanh toán
   * chưa có payment; một order đã hủy qua API của F05; một order tổng 0 (coupon 100% cho ra đơn như
   * vậy); và một order đang có payment PENDING, tạo bằng {@code takenKey}.
   */
  private Fixture openFixture() throws Exception {
    UUID cancelled = data.pendingOrder("300000.00");
    mockMvc
        .perform(
            post(ORDERS + "/{id}/cancel", cancelled)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Khach doi y\"}"))
        .andExpect(status().isNoContent());
    UUID paying = data.pendingOrder("300000.00");
    String takenKey = "pay-http-taken";
    created(paying, takenKey, STRIPE);
    return new Fixture(
        data.pendingOrder("300000.00"), cancelled, data.pendingOrder("0.00"), paying, takenKey);
  }

  private record Fixture(
      UUID pending, UUID cancelled, UUID zeroTotal, UUID paying, String takenKey) {}

  /** Một request tạo payment: {@code key = null} là không gửi header. */
  private record Create(UUID orderId, String key, String body) {}
}
