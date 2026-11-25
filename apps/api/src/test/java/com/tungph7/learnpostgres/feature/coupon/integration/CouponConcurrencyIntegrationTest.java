package com.tungph7.learnpostgres.feature.coupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.coupon.dto.CreateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.RedemptionResponse;
import com.tungph7.learnpostgres.feature.coupon.repository.CouponRepository;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;
import com.tungph7.learnpostgres.feature.order.OrderFixtures;

/**
 * Trọng tâm của F04: chứng minh ba thứ chỉ lộ ra khi chạy song song thật.
 *
 * <ul>
 *   <li>Atomic counter không mất update — 100 lần redeem trên {@code max_uses = 50} cho đúng 50.
 *   <li>UNIQUE {@code (coupon_id, order_id)} làm redeem idempotent — 10 lần cùng một order, từ hai
 *       customer khác nhau, vẫn chỉ tăng counter 1.
 *   <li>EXCLUDE constraint là thứ quyết định khi hai request cùng tạo campaign trùng khoảng thời
 *       gian — không cần và không thể pre-check ở service.
 * </ul>
 *
 * <p>Cố ý KHÔNG đặt {@code @Transactional} lên class: mỗi lời gọi service phải chạy trong
 * transaction RIÊNG thì mới có tranh chấp thật. Gắn {@code @Transactional} vào test là mọi thread
 * dùng chung một transaction, không còn gì để đo — và dữ liệu cũng bị rollback mất.
 *
 * <p>Hệ quả: dữ liệu ghi ra là thật và commit thật, nên phải tự dọn — xem {@link #cleanup()}.
 *
 * <p><b>Tên class phải kết thúc bằng {@code IntegrationTest}, không phải {@code IT}.</b> Project
 * không có failsafe plugin; surefire chỉ nhặt {@code *Test} / {@code *Tests} / {@code Test*}. Một
 * file tên {@code CouponConcurrencyIT} sẽ không bao giờ chạy và cũng không báo gì.
 */
@SpringBootTest
@Tag("integration")
class CouponConcurrencyIntegrationTest extends AbstractContainerBaseTest {

  /**
   * AC-17: 100 lần redeem đồng thời trên coupon còn 50 lượt.
   *
   * <p>Hikari ở profile test giới hạn {@code maximum-pool-size: 5}, nên đây là số thread ĐỢI nhau ở
   * cửa connection pool chứ không phải số transaction chạy song song thật. Không sao: thứ đang đo
   * là counter có mất update hay không, và 100 thread tranh nhau cùng một row là quá đủ để lộ.
   */
  private static final int REDEEM_THREADS = 100;

  private static final int MAX_USES = 50;

  /** AC-18: nhiều lần redeem đồng thời cho CÙNG một order, từ hai customer khác nhau. */
  private static final int SAME_ORDER_THREADS = 10;

  private static final BigDecimal DISCOUNT = new BigDecimal("100.00");

  @Autowired CouponService service;
  @Autowired CouponRepository couponRepo;
  @Autowired JdbcTemplate jdbc;

  /**
   * Hikari đặt {@code auto-commit: false}. Trong {@code @SpringBootTest} không có transaction bao
   * quanh, nên mọi ghi qua {@code JdbcTemplate} sẽ bị rollback lúc trả connection về pool — im
   * lặng, không lỗi. Seed và cleanup phải bọc trong {@code TransactionTemplate} mới thật sự commit.
   */
  @Autowired TransactionTemplate tx;

  private UUID couponId;
  private UUID customerId;

  /** Address giao hàng của {@link #customerId} — order seed ra cần nó (NOT NULL + FK). */
  private UUID addressId;

  /** AC-18 nói "customer A và B" — UNIQUE key không chứa customer nên phải có người thứ hai. */
  private UUID otherCustomerId;

  @BeforeEach
  void setUp() {
    cleanup();
    customerId = seedCustomer();
    otherCustomerId = seedCustomer();
    addressId = tx.execute(status -> OrderFixtures.insertShippingAddress(jdbc, customerId));
    couponId = seedCoupon("CONC-RACE", MAX_USES);
  }

  /**
   * BẮT BUỘC dọn, không phải cho sạch mắt.
   *
   * <p>{@code coupon_redemptions} có FK RESTRICT tới {@code coupons}, {@code customers} (V11) và
   * {@code orders} (V12). Để sót một dòng redemption là mọi {@code DELETE FROM app.customers} về
   * sau đều bị chặn — kể cả cleanup của {@code CustomerIntegrationTest}. Chạy riêng thì không thấy
   * gì, chỉ {@code ./mvnw verify} mới lộ.
   *
   * <p>Thứ tự bắt buộc: redemption → order → coupon → customer. Order phải đi trước customer vì
   * {@code orders.customer_id} và {@code orders.shipping_address_id} là RESTRICT: CASCADE từ
   * customer xuống address bị chặn nếu address còn order trỏ tới.
   *
   * <p>Coupon cũng phải xoá vì EXCLUDE constraint: để sót một row {@code CONC-%} đang active là lần
   * chạy sau tạo lại cùng code cùng khoảng thời gian sẽ bị 409.
   *
   * <p>CHỈ {@code @AfterEach}, không kèm {@code @BeforeEach}: {@link #setUp()} đã gọi tay ở dòng
   * đầu. Gắn cả hai thì thành hai method {@code @BeforeEach} trong cùng một class, mà JUnit không
   * đảm bảo thứ tự giữa chúng — cleanup chạy sau setUp là xoá mất chính coupon vừa seed, và mọi
   * thread nhận {@code CPN_NOT_FOUND}.
   */
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM app.coupon_redemptions WHERE coupon_id IN"
                  + " (SELECT id FROM app.coupons WHERE code LIKE 'CONC-%')");
          jdbc.update(
              "DELETE FROM app.orders WHERE customer_id IN"
                  + " (SELECT id FROM app.customers WHERE email LIKE 'conc-%')");
          jdbc.update("DELETE FROM app.coupons WHERE code LIKE 'CONC-%'");
          jdbc.update("DELETE FROM app.customers WHERE email LIKE 'conc-%'");
        });
  }

  // ================================================================
  // AC-17 — atomic counter
  // ================================================================

  @Test
  @DisplayName("100 redeem đồng thời trên max_uses=50 → đúng 50 thành công, counter đúng 50")
  void hundredRedeemsOnFiftySlots() {
    List<UUID> orderIds = seedOrders(REDEEM_THREADS);
    AtomicInteger nextOrder = new AtomicInteger();
    Outcomes outcomes = new Outcomes();

    ConcurrencyTestSupport.runConcurrently(
        REDEEM_THREADS,
        () -> {
          // Mỗi thread một order riêng → UNIQUE không chặn, mọi thread đều tới được bước
          // increment. Thứ duy nhất quyết định ai thắng là câu UPDATE atomic.
          UUID orderId = orderIds.get(nextOrder.getAndIncrement());
          record(outcomes, () -> service.redeem(couponId, orderId, customerId, DISCOUNT));
          return null;
        });

    outcomes.requireNoUnexpectedFailure();
    assertThat(outcomes.redeemed).hasSize(MAX_USES);
    assertThat(outcomes.exhausted).hasSize(REDEEM_THREADS - MAX_USES);

    // Không mất update: counter phải khớp số lần thành công.
    assertThat(usedCount()).isEqualTo(MAX_USES);

    // 50 transaction hỏng đã rollback cả dòng redemption vừa insert.
    assertThat(redemptionCount()).isEqualTo(MAX_USES);
  }

  // ================================================================
  // AC-18 — idempotency
  // ================================================================

  /**
   * Đây là test sẽ đỏ nếu ai đó đảo thứ tự insert/increment trong {@code redeem}: increment trước
   * thì mỗi lần gọi lặp đều +1 rồi mới phát hiện đã tồn tại, counter phình lên sai.
   *
   * <p>Hai customer luân phiên gửi cùng một {@code order_id} — đúng chữ của AC-18. UNIQUE key là
   * {@code (coupon_id, order_id)}, KHÔNG có customer trong đó, nên người thứ hai cũng bị chặn. Đó
   * là chủ ý: một đơn hàng chỉ được hưởng một lần giảm giá, bất kể ai bấm nút.
   */
  @Test
  @DisplayName("10 redeem đồng thời cùng (coupon, order) từ 2 customer → 1 wasNew, counter chỉ +1")
  void concurrentRedeemsOfSameOrderAreIdempotent() {
    UUID sharedOrderId = seedOrders(1).get(0);
    Outcomes outcomes = new Outcomes();
    AtomicInteger turn = new AtomicInteger();

    ConcurrencyTestSupport.runConcurrently(
        SAME_ORDER_THREADS,
        () -> {
          UUID caller = turn.getAndIncrement() % 2 == 0 ? customerId : otherCustomerId;
          record(outcomes, () -> service.redeem(couponId, sharedOrderId, caller, DISCOUNT));
          return null;
        });

    outcomes.requireNoUnexpectedFailure();
    assertThat(outcomes.redeemed).hasSize(SAME_ORDER_THREADS);

    long wasNew = outcomes.redeemed.stream().filter(RedemptionResponse::wasNew).count();
    assertThat(wasNew).isOne();

    // Mọi lần gọi đều trả về cùng một row — kết quả của lần redeem đầu.
    assertThat(outcomes.redeemed.stream().map(RedemptionResponse::id).distinct()).hasSize(1);

    // Và cùng một customer: 9 người thua nhận lại row của người thắng, không phải row của
    // chính mình. Đây là chỗ phân biệt "idempotent thật" với "mỗi người một dòng".
    assertThat(outcomes.redeemed.stream().map(RedemptionResponse::customerId).distinct())
        .hasSize(1);

    assertThat(usedCount()).isOne();
    assertThat(redemptionCount()).isOne();
  }

  // ================================================================
  // EXCLUDE constraint dưới tranh chấp
  // ================================================================

  /**
   * Hai request cùng tạo campaign trùng khoảng thời gian. Service cố ý không pre-check bằng SELECT
   * — nếu có thì cả hai đều thấy "chưa có gì" rồi cùng ghi. EXCLUDE constraint là thứ duy nhất
   * quyết định được, và test này chứng minh nó quyết định đúng ngay cả khi hai bên chạy cùng lúc.
   *
   * <p><b>Bên thua trượt theo một trong HAI cách, tuỳ hai transaction xen kẽ ra sao.</b> Đây không
   * phải chi tiết vụn vặt của test — production gặp cả hai:
   *
   * <ul>
   *   <li><b>23P01</b> {@code exclusion_violation}: B insert sau khi A đã kiểm tra xong, nên B thấy
   *       tuple của A và bị từ chối thẳng. Message có tên constraint.
   *   <li><b>40P01</b> {@code deadlock_detected}: cả hai kịp insert trước khi bên nào kiểm tra xong
   *       → A đợi transaction của B, B đợi A. Kiểm tra EXCLUDE là một phép <em>đợi ở mức
   *       transaction</em>, nên hai chiều đợi nhau tạo thành vòng và deadlock detector huỷ một bên.
   *       Message không có tên constraint, chỉ có dòng {@code Where: while checking exclusion
   *       constraint ... in relation "coupons"}.
   * </ul>
   *
   * <p>Cả hai đều để lại đúng 1 row, nên bất biến nghiệp vụ không đổi. Nhưng client thì khác nhau:
   * 23P01 là "thua vĩnh viễn, đừng thử lại", 40P01 là "va chạm, thử lại thì có thể thắng". Giả định
   * chỉ có 23P01 chính là thứ làm test này đỏ ngẫu nhiên ở lần chạy thứ hai của {@code ./mvnw
   * verify}.
   */
  @Test
  @DisplayName("2 request đồng thời tạo coupon cùng code trùng khoảng → đúng 1 thắng")
  void overlappingCouponsRaceIsDecidedByExclude() {
    OffsetDateTime from = OffsetDateTime.now().plusDays(10);
    OffsetDateTime to = from.plusDays(30);

    List<Object> results =
        ConcurrencyTestSupport.runConcurrently(
            2,
            () -> {
              try {
                return service.create(
                    new CreateCouponRequest(
                        "CONC-EXCL",
                        null,
                        CouponType.PERCENT,
                        new BigDecimal("10"),
                        null,
                        null,
                        null,
                        null,
                        from,
                        to));
              } catch (RuntimeException ex) {
                return ex;
              }
            });

    long created = results.stream().filter(Coupon.class::isInstance).count();
    assertThat(created).isOne();

    // Bên thua phải trượt vì đúng EXCLUDE constraint, không phải vì một lỗi nào khác. Chấp nhận
    // cả hai hình thức (xem javadoc) nhưng KHÔNG chấp nhận một lỗi bất kỳ: cả hai chuỗi dưới đây
    // chỉ xuất hiện khi Postgres đang kiểm tra đúng exclusion constraint này.
    String loserMessage =
        results.stream()
            .filter(r -> !(r instanceof Coupon))
            .map(CouponConcurrencyIntegrationTest::describe)
            .findFirst()
            .orElse("(không có bên thua)");
    assertThat(loserMessage)
        .as("Bên thua phải trượt vì EXCLUDE (23P01) hoặc deadlock khi kiểm tra EXCLUDE (40P01)")
        .containsAnyOf("ex_coupons_code_no_overlap", "exclusion constraint");

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.coupons WHERE code = 'CONC-EXCL'", Integer.class))
        .isOne();
  }

  // ================================================================
  // Helpers
  // ================================================================

  /**
   * Phân loại kết quả của một lần redeem.
   *
   * <p>Bắt trần {@code catch (Exception)} rồi đếm chung là cái bẫy của test concurrency: một lỗi hạ
   * tầng (hết connection trong pool, timeout) sẽ bị tính thành "hết lượt" và assert vẫn có thể xanh
   * hoặc đỏ với con số vô nghĩa. Tách riêng để thông báo lỗi chỉ đúng chỗ.
   */
  private static void record(Outcomes outcomes, ThrowingCall call) {
    try {
      outcomes.redeemed.add(call.run());
    } catch (BusinessException ex) {
      if (ex.getCode() == ErrorCode.CPN_EXHAUSTED) {
        outcomes.exhausted.add(ex);
      } else {
        outcomes.unexpected.add(ex);
      }
    } catch (RuntimeException ex) {
      outcomes.unexpected.add(ex);
    }
  }

  @FunctionalInterface
  private interface ThrowingCall {
    RedemptionResponse run();
  }

  private static final class Outcomes {
    final ConcurrentLinkedQueue<RedemptionResponse> redeemed = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<RuntimeException> exhausted = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<RuntimeException> unexpected = new ConcurrentLinkedQueue<>();

    void requireNoUnexpectedFailure() {
      assertThat(unexpected)
          .as(
              "Có thread hỏng vì lý do ngoài nghiệp vụ (pool, timeout, deadlock...): %s",
              unexpected.stream().map(CouponConcurrencyIntegrationTest::describe).toList())
          .isEmpty();
    }
  }

  private static String describe(Object throwable) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = (Throwable) throwable; t != null && sb.length() < 2000; t = t.getCause()) {
      sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  private UUID seedCustomer() {
    UUID id = UUID.randomUUID();
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "INSERT INTO app.customers (id, email, first_name, last_name) VALUES (?,?,?,?)",
                id,
                "conc-" + id + "@example.com",
                "Conc",
                "Test"));
    return id;
  }

  /**
   * {@code count} order thật của {@link #customerId}, seed TRƯỚC khi chạy song song.
   *
   * <p>Từ V12, {@code coupon_redemptions.order_id} có FK tới {@code app.orders} — {@code
   * UUID.randomUUID()} không còn là order_id hợp lệ. Seed một lượt trong transaction riêng để các
   * thread chỉ còn tranh nhau đúng thứ đang đo: counter của coupon.
   */
  private List<UUID> seedOrders(int count) {
    return tx.execute(status -> OrderFixtures.insertOrders(jdbc, addressId, count));
  }

  private UUID seedCoupon(String code, int maxUses) {
    return tx.execute(
        status -> {
          Coupon coupon =
              new Coupon(
                  code,
                  CouponType.PERCENT,
                  new BigDecimal("10"),
                  OffsetDateTime.now().minusDays(1),
                  OffsetDateTime.now().plusDays(1));
          coupon.setMaxUses(maxUses);
          return couponRepo.saveAndFlush(coupon).getId();
        });
  }

  private int usedCount() {
    return jdbc.queryForObject(
        "SELECT used_count FROM app.coupons WHERE id = ?", Integer.class, couponId);
  }

  private int redemptionCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM app.coupon_redemptions WHERE coupon_id = ?", Integer.class, couponId);
  }
}
