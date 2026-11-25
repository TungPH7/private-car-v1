package com.tungph7.learnpostgres.feature.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;
import com.tungph7.learnpostgres.feature.order.dto.OrderDetailResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderItemResponse;
import com.tungph7.learnpostgres.feature.order.dto.OrderResponse;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;
import com.tungph7.learnpostgres.feature.order.integration.OrderTestData.Buyer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Đơn hàng đi hết HTTP → controller → service → Postgres, không mock gì.
 *
 * <p>Hai lớp test đã có mỗi lớp thiếu một nửa: {@code OrderControllerWebMvcTest} có HTTP nhưng
 * service là mock; {@link OrderServiceIntegrationTest} có Postgres nhưng không qua HTTP. Ở đây kiểm
 * được những gì chỉ đúng khi ghép cả hai: JSON dựng từ dữ liệu thật (tiền từ {@code NUMERIC}, lịch
 * sử có actor của request), mã lỗi của từng AC sinh ra từ dữ liệu thật và không để lại gì, và số
 * câu SQL của một request (AC-08).
 *
 * <p>Actor của các lần chuyển trạng thái là {@code "anonymous"}: repo chưa có đăng nhập, request
 * không có principal ({@code OrderController.actorOf}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class OrderControllerIntegrationTest extends AbstractContainerBaseTest {

  private static final String ORDERS = "/api/v1/orders";

  private static final String ANONYMOUS = "anonymous";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired EntityManagerFactory entityManagerFactory;

  private OrderTestData data;
  private Buyer buyer;

  @BeforeEach
  void setUp() {
    data = new OrderTestData(jdbc, tx, "order-http-");
    cleanup();
    buyer = data.buyer();
  }

  /**
   * BẮT BUỘC dọn — thứ tự FK và lý do ở {@link OrderTestData#cleanup()}. Chỉ {@code @AfterEach}:
   * {@link #setUp()} đã tự gọi ở dòng đầu.
   */
  @AfterEach
  void cleanup() {
    data.cleanup();
  }

  // ================================================================
  // Đặt đơn
  // ================================================================

  /**
   * AC-02. Subtotal 238.01, coupon 10% → 23.801, làm tròn HALF_UP 2 chữ số (F04) còn 23.80 → total
   * 214.21. Tiền đi từ {@code NUMERIC(12,2)} ra JSON giữ đủ 2 chữ số thập phân.
   */
  @Test
  @DisplayName(
      "AC-02: POST 3 dòng + coupon → 201, giảm giá làm tròn đúng, coupon +1, giữ từng dòng")
  void placesThreeLinesWithCoupon() throws Exception {
    UUID book = data.activeProduct("100.00", 10);
    UUID pen = data.activeProduct("12.34", 10);
    UUID sticker = data.activeProduct("0.99", 10);
    String code = data.couponCode();
    UUID coupon = data.percentCoupon(code, 10, 100);

    MvcResult result =
        mockMvc
            .perform(
                post(ORDERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        placeBody(
                            buyer.customerId(),
                            buyer.addressId(),
                            code,
                            line(book, 2),
                            line(pen, 3),
                            line(sticker, 1))))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("\"discountAmount\":23.80")))
            .andExpect(content().string(containsString("\"shippingFee\":0.00")))
            .andReturn();

    OrderResponse order = read(result, OrderResponse.class);
    assertThat(order.status()).isEqualTo("PENDING");
    assertThat(order.orderNumber()).matches("ORD-\\d{6}-\\d{6,}");
    assertThat(order.couponId()).isEqualTo(coupon);
    assertThat(order.subtotal()).isEqualByComparingTo("238.01");
    assertThat(order.discountAmount()).isEqualByComparingTo("23.80");
    assertThat(order.total()).isEqualByComparingTo("214.21");

    // Location trỏ đúng đơn vừa tạo.
    String location = result.getResponse().getHeader("Location");
    assertThat(location).endsWith(ORDERS + "/" + order.id());
    mockMvc
        .perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderNumber").value(order.orderNumber()));

    assertThat(data.reservedOf(book)).isEqualTo(2);
    assertThat(data.reservedOf(pen)).isEqualTo(3);
    assertThat(data.reservedOf(sticker)).isEqualTo(1);
    assertThat(data.usedCountOf(coupon)).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT discount_amount FROM app.coupon_redemptions WHERE order_id = ?",
                BigDecimal.class,
                order.id()))
        .isEqualByComparingTo("23.80");
  }

  /**
   * Mỗi kịch bản trả status + code của đúng AC và không để lại dấu vết: AC-12..16 bị chặn trước câu
   * ghi đầu tiên; AC-17 đã ghi order, items, history và giữ xong 2 dòng rồi mới hỏng — phải
   * rollback hết.
   */
  static Stream<Arguments> rejections() {
    return Stream.of(
        rejection(
            "AC-12 customer không tồn tại",
            404,
            "CUS_NOT_FOUND",
            shop -> placeBody(UUID.randomUUID(), shop.address(), null, line(shop.product(), 1))),
        rejection(
            "AC-13 address của customer khác",
            403,
            "ORD_ADDRESS_FORBIDDEN",
            shop ->
                placeBody(shop.customer(), shop.strangersAddress(), null, line(shop.product(), 1))),
        rejection(
            "AC-14 address đã xoá mềm",
            400,
            "ORD_ADDRESS_INVALID",
            shop ->
                placeBody(shop.customer(), shop.deletedAddress(), null, line(shop.product(), 1))),
        rejection(
            "AC-15 product ARCHIVED",
            400,
            "ORD_PRODUCT_INACTIVE",
            shop -> placeBody(shop.customer(), shop.address(), null, line(shop.archived(), 1))),
        rejection(
            "AC-16 coupon hết hạn",
            400,
            "CPN_EXPIRED",
            shop ->
                placeBody(
                    shop.customer(),
                    shop.address(),
                    shop.expiredCoupon(),
                    line(shop.product(), 1))),
        // Dòng thiếu hàng có productId LỚN NHẤT → bulkReserve đã giữ xong 2 dòng kia mới tới nó.
        rejection(
            "AC-17 1 trong 3 dòng thiếu hàng",
            409,
            "INV_INSUFFICIENT_STOCK",
            shop ->
                placeBody(
                    shop.customer(),
                    shop.address(),
                    null,
                    line(shop.scarce(), 5),
                    line(shop.first(), 1),
                    line(shop.second(), 1))));
  }

  @ParameterizedTest(name = "{0} → {1} {2}")
  @MethodSource("rejections")
  @DisplayName(
      "đặt đơn bị từ chối → đúng status + code, không để lại đơn, giữ hàng hay lượt coupon")
  void rejectedOrderLeavesNothingBehind(
      String scenario, int expectedStatus, String code, Function<Shop, String> body)
      throws Exception {
    Shop shop = openShop();

    mockMvc
        .perform(post(ORDERS).contentType(MediaType.APPLICATION_JSON).content(body.apply(shop)))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.code").value(code));

    assertThat(
            data.count(
                "SELECT count(*) FROM app.orders WHERE customer_id IN (?, ?)",
                shop.customer(),
                shop.stranger()))
        .isZero();
    assertThat(
            data.count(
                "SELECT coalesce(sum(reserved), 0) FROM app.product_inventory"
                    + " WHERE product_id IN (?, ?, ?, ?, ?)",
                shop.product(),
                shop.archived(),
                shop.first(),
                shop.second(),
                shop.scarce()))
        .isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.inventory_movements"
                    + " WHERE product_id IN (?, ?, ?, ?, ?)",
                shop.product(),
                shop.archived(),
                shop.first(),
                shop.second(),
                shop.scarce()))
        .isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.coupon_redemptions WHERE customer_id = ?",
                shop.customer()))
        .isZero();
  }

  // ================================================================
  // Vòng đời
  // ================================================================

  @Test
  @DisplayName("AC-01/03/04/05/09/19: PENDING → PAID → SHIPPED → DELIVERED qua HTTP, rồi hủy → 409")
  void walksTheLifecycleOverHttp() throws Exception {
    UUID product = data.activeProduct("100000.00", 10);

    OrderResponse placed = place(buyer, line(product, 3));
    assertThat(placed.status()).isEqualTo("PENDING");
    assertThat(data.reservedOf(product)).isEqualTo(3);

    OrderResponse paid = transition(placed.id(), "PAID", "Payment confirmed");
    OrderResponse shipped = transition(placed.id(), "SHIPPED", "Handed to carrier");
    OrderResponse delivered = transition(placed.id(), "DELIVERED", "Signed by customer");

    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(paid.paidAt()).isNotNull();
    assertThat(shipped.status()).isEqualTo("SHIPPED");
    assertThat(shipped.shippedAt()).isNotNull();
    assertThat(shipped.paidAt()).isEqualTo(paid.paidAt());
    assertThat(delivered.status()).isEqualTo("DELIVERED");
    assertThat(delivered.deliveredAt()).isNotNull();
    // AC-05: DELIVERED chốt tồn kho — hàng rời reserved và on_hand, sang sold.
    assertThat(inventoryOf(product)).containsExactly(7, 0, 3);

    // AC-09: mỗi lần chuyển một dòng, cũ nhất trước; dòng đặt đơn có actor là customer.
    assertThat(history(placed.id()))
        .extracting(
            StatusHistoryResponse::fromStatus,
            StatusHistoryResponse::toStatus,
            StatusHistoryResponse::reason,
            StatusHistoryResponse::actor)
        .containsExactly(
            tuple(null, "PENDING", "Order placed", buyer.customerId().toString()),
            tuple("PENDING", "PAID", "Payment confirmed", ANONYMOUS),
            tuple("PAID", "SHIPPED", "Handed to carrier", ANONYMOUS),
            tuple("SHIPPED", "DELIVERED", "Signed by customer", ANONYMOUS));

    // AC-19: đơn đã giao không hủy được — và không có gì đổi theo.
    mockMvc
        .perform(
            post(ORDERS + "/{id}/cancel", placed.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Too late\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORD_INVALID_STATUS_TRANSITION"));
    assertThat(fetch(placed.id()).status()).isEqualTo("DELIVERED");
    assertThat(history(placed.id())).hasSize(4);
    assertThat(inventoryOf(product)).containsExactly(7, 0, 3);
  }

  @Test
  @DisplayName("AC-18: PENDING → DELIVERED (nhảy bước) → 409, đơn và tồn kho không đổi")
  void skippingAStepChangesNothing() throws Exception {
    UUID product = data.activeProduct("100.00", 10);
    OrderResponse placed = place(buyer, line(product, 2));

    mockMvc
        .perform(
            post(ORDERS + "/{id}/transitions", placed.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"DELIVERED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORD_INVALID_STATUS_TRANSITION"));

    OrderResponse after = fetch(placed.id());
    assertThat(after.status()).isEqualTo("PENDING");
    assertThat(after.deliveredAt()).isNull();
    assertThat(after.version()).isEqualTo(placed.version());
    assertThat(inventoryOf(product)).containsExactly(10, 2, 0);
    assertThat(history(placed.id())).hasSize(1);
    assertThat(data.movementsOf(placed.id())).containsExactly("RESERVE");
  }

  /**
   * AC-07. Hủy sau khi đã thanh toán vẫn nhả hàng (chỉ đơn SHIPPED mới giữ — {@code
   * OrderLifecycle}) và hoàn lượt coupon; {@code paid_at} giữ nguyên để còn biết đơn từng được trả
   * tiền (F06 hoàn tiền dựa vào nó).
   */
  @Test
  @DisplayName(
      "AC-07: hủy đơn PAID → 204, nhả hàng + hoàn coupon, paidAt giữ, history PAID → CANCELLED")
  void cancellingAPaidOrderReturnsStockAndCoupon() throws Exception {
    UUID product = data.activeProduct("50.00", 10);
    String code = data.couponCode();
    UUID coupon = data.percentCoupon(code, 10, 100);
    OrderResponse placed = placeWithCoupon(buyer, code, line(product, 2));
    OrderResponse paid = transition(placed.id(), "PAID", "Payment confirmed");

    mockMvc
        .perform(
            post(ORDERS + "/{id}/cancel", placed.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Khách đổi ý\"}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    OrderResponse cancelled = fetch(placed.id());
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelReason()).isEqualTo("Khách đổi ý");
    assertThat(cancelled.cancelledAt()).isNotNull();
    assertThat(cancelled.paidAt()).isEqualTo(paid.paidAt());

    assertThat(inventoryOf(product)).containsExactly(10, 0, 0);
    assertThat(data.movementsOf(placed.id())).containsExactly("RESERVE", "RELEASE");
    assertThat(data.usedCountOf(coupon)).isZero();
    assertThat(
            data.count(
                "SELECT count(*) FROM app.coupon_redemptions WHERE order_id = ?", placed.id()))
        .isZero();
    assertThat(history(placed.id()))
        .last()
        .extracting(
            StatusHistoryResponse::fromStatus,
            StatusHistoryResponse::toStatus,
            StatusHistoryResponse::reason,
            StatusHistoryResponse::actor)
        .containsExactly("PAID", "CANCELLED", "Khách đổi ý", ANONYMOUS);
  }

  // ================================================================
  // Detail
  // ================================================================

  /**
   * AC-08: cả request {@code GET /{id}/detail} chỉ chạy đúng 1 câu SQL.
   *
   * <p>Đếm bằng Hibernate statistics: {@code getPrepareStatementCount()} là số {@code
   * PreparedStatement} Hibernate xin từ JDBC — mỗi câu SQL một cái. Statistics bật tại chỗ và tắt
   * lại ngay: đó là bộ đếm dùng chung của cả {@code SessionFactory}, không có test nào chạy song
   * song ({@code junit-platform.properties}) nên con số chỉ là của request này.
   */
  @Test
  @DisplayName(
      "AC-08: GET /{id}/detail → đúng 1 câu SQL cho header, customer, coupon, items, history")
  void detailIsOneStatement() throws Exception {
    UUID book = data.activeProduct("100.00", 10);
    UUID pen = data.activeProduct("12.34", 10);
    String code = data.couponCode();
    data.percentCoupon(code, 10, 100);
    OrderResponse placed = placeWithCoupon(buyer, code, line(book, 2), line(pen, 1));
    transition(placed.id(), "PAID", "Payment confirmed");

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    statistics.setStatisticsEnabled(true);
    String json;
    try {
      json =
          mockMvc
              .perform(get(ORDERS + "/{id}/detail", placed.id()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(statistics.getPrepareStatementCount()).isOne();
      assertThat(statistics.getEntityLoadCount()).isZero();
    } finally {
      statistics.setStatisticsEnabled(false);
      statistics.clear();
    }

    OrderDetailResponse detail = objectMapper.readValue(json, OrderDetailResponse.class);
    assertThat(detail.orderNumber()).isEqualTo(placed.orderNumber());
    assertThat(detail.status()).isEqualTo("PAID");
    assertThat(detail.total()).isEqualByComparingTo(placed.total());
    assertThat(detail.customerId()).isEqualTo(buyer.customerId());
    assertThat(detail.customerEmail()).startsWith("order-http-");
    assertThat(detail.customerName()).isEqualTo("Order Probe");
    assertThat(detail.couponCode()).isEqualTo(code);
    // Snapshot address lúc đặt (address của OrderFixtures.insertShippingAddress).
    assertThat(detail.shippingRecipient()).isEqualTo("Order Fixture");
    assertThat(detail.shippingCity()).isEqualTo("HCM");
    assertThat(detail.shippingCountry()).isEqualTo("VN");
    assertThat(detail.items())
        .extracting(
            OrderItemResponse::productId,
            OrderItemResponse::productSku,
            OrderItemResponse::quantity,
            OrderItemResponse::lineTotal)
        .containsExactlyInAnyOrder(
            tuple(book, "SKU-" + book, 2, new BigDecimal("200.00")),
            tuple(pen, "SKU-" + pen, 1, new BigDecimal("12.34")));
    assertThat(detail.history())
        .extracting(StatusHistoryResponse::fromStatus, StatusHistoryResponse::toStatus)
        .containsExactly(tuple(null, "PENDING"), tuple("PENDING", "PAID"));
  }

  // ================================================================
  // Helpers
  // ================================================================

  private OrderResponse place(Buyer who, String... lines) throws Exception {
    return placeWithCoupon(who, null, lines);
  }

  private OrderResponse placeWithCoupon(Buyer who, String couponCode, String... lines)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(ORDERS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(placeBody(who.customerId(), who.addressId(), couponCode, lines)))
            .andExpect(status().isCreated())
            .andReturn();
    return read(result, OrderResponse.class);
  }

  private OrderResponse transition(UUID orderId, String to, String reason) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(ORDERS + "/{id}/transitions", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"to\":\"%s\",\"reason\":\"%s\"}".formatted(to, reason)))
            .andExpect(status().isOk())
            .andReturn();
    return read(result, OrderResponse.class);
  }

  private OrderResponse fetch(UUID orderId) throws Exception {
    MvcResult result =
        mockMvc.perform(get(ORDERS + "/{id}", orderId)).andExpect(status().isOk()).andReturn();
    return read(result, OrderResponse.class);
  }

  private List<StatusHistoryResponse> history(UUID orderId) throws Exception {
    String json =
        mockMvc
            .perform(get(ORDERS + "/{id}/history", orderId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(json, new TypeReference<List<StatusHistoryResponse>>() {});
  }

  /**
   * Đọc body về record thay vì so bằng {@code jsonPath}: tiền là {@code BigDecimal}, còn JsonPath
   * trả {@code Double} hay {@code BigDecimal} là tuỳ provider — {@code isEqualByComparingTo} thì
   * không phụ thuộc chuyện đó (cùng lý do với {@code CouponControllerIntegrationTest}).
   */
  private <T> T read(MvcResult result, Class<T> type) throws Exception {
    return objectMapper.readValue(result.getResponse().getContentAsString(), type);
  }

  /** {@code [on_hand, reserved, sold]}. */
  private List<Integer> inventoryOf(UUID productId) {
    return jdbc.queryForObject(
        "SELECT on_hand, reserved, sold FROM app.product_inventory WHERE product_id = ?",
        (rs, rowNum) -> List.of(rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("sold")),
        productId);
  }

  private static String placeBody(
      UUID customerId, UUID addressId, String couponCode, String... lines) {
    String coupon = couponCode == null ? "" : ",\"couponCode\":\"%s\"".formatted(couponCode);
    return "{\"customerId\":\"%s\",\"shippingAddressId\":\"%s\",\"items\":[%s]%s}"
        .formatted(customerId, addressId, String.join(",", lines), coupon);
  }

  private static String line(UUID productId, int quantity) {
    return "{\"productId\":\"%s\",\"quantity\":%d}".formatted(productId, quantity);
  }

  private static Arguments rejection(
      String scenario, int status, String code, Function<Shop, String> body) {
    return Arguments.of(scenario, status, code, body);
  }

  /**
   * Đủ dữ liệu cho mọi kịch bản của {@link #rejectedOrderLeavesNothingBehind}: customer + address,
   * address của người khác, address đã xoá, product bán được, product ARCHIVED, coupon hết hạn, và
   * 3 product có productId tăng dần mà product cuối chỉ còn 1 món.
   */
  private Shop openShop() {
    Buyer stranger = data.buyer();
    List<UUID> ascending = Stream.generate(UUID::randomUUID).limit(3).sorted().toList();
    String expiredCode = data.couponCode();
    data.expiredCoupon(expiredCode);
    return new Shop(
        buyer.customerId(),
        buyer.addressId(),
        stranger.customerId(),
        stranger.addressId(),
        data.deletedAddress(buyer.customerId()),
        data.activeProduct("10.00", 10),
        data.product(UUID.randomUUID(), "10.00", 10, "ARCHIVED"),
        data.activeProduct(ascending.get(0), "10.00", 10),
        data.activeProduct(ascending.get(1), "10.00", 10),
        data.activeProduct(ascending.get(2), "10.00", 1),
        expiredCode);
  }

  private record Shop(
      UUID customer,
      UUID address,
      UUID stranger,
      UUID strangersAddress,
      UUID deletedAddress,
      UUID product,
      UUID archived,
      UUID first,
      UUID second,
      UUID scarce,
      String expiredCoupon) {}
}
