package com.tungph7.learnpostgres.feature.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.GlobalExceptionHandler;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.ProblemDetailFactory;
import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;
import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponRedemption;
import com.tungph7.learnpostgres.feature.coupon.domain.CouponType;
import com.tungph7.learnpostgres.feature.coupon.dto.RedemptionResponse;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;
import com.tungph7.learnpostgres.feature.coupon.mapper.CouponMapper;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;

import tools.jackson.databind.ObjectMapper;

/** Web layer: routing, mapping, status code, và cách lỗi của service hiện ra ngoài HTTP. */
@WebMvcTest(CouponController.class)
@Import({
  TestSecurityConfig.class,
  GlobalExceptionHandler.class,
  ProblemDetailFactory.class,
  CouponControllerWebMvcTest.MapperConfig.class
})
@EnableConfigurationProperties(LoggingProperties.class)
class CouponControllerWebMvcTest {

  private static final UUID COUPON_ID = UUID.fromString("01930000-0000-7000-8000-00000000c0de");
  private static final UUID CUSTOMER_ID = UUID.fromString("01930000-0000-7000-8000-0000000c0501");
  private static final OffsetDateTime JUN = OffsetDateTime.parse("2026-06-01T00:00:00Z");
  private static final OffsetDateTime JUL = OffsetDateTime.parse("2026-07-01T00:00:00Z");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;

  @MockitoBean CouponService service;

  /**
   * Mapper là MapStruct singleton → bind bean thật cho slice.
   *
   * <p>Phải @Import tường minh ở class level chứ không chỉ khai @TestConfiguration: các lớp @Nested
   * dựng context riêng và KHÔNG tự nhặt @TestConfiguration của lớp bao ngoài.
   */
  @TestConfiguration
  static class MapperConfig {
    @Bean
    CouponMapper couponMapper() {
      return Mappers.getMapper(CouponMapper.class);
    }
  }

  private static Coupon coupon(String code, String value) {
    Coupon c = new Coupon(code, CouponType.PERCENT, new BigDecimal(value), JUN, JUL);
    setField(c, "id", COUPON_ID);
    return c;
  }

  private static String createBody(String code, String from, String to) {
    return """
           {"code":"%s","type":"PERCENT","value":25,"validFrom":"%s","validTo":"%s"}
           """
        .formatted(code, from, to);
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Class<?> type = target.getClass();
      while (type != null) {
        try {
          var f = type.getDeclaredField(name);
          f.setAccessible(true);
          f.set(target, value);
          return;
        } catch (NoSuchFieldException ignored) {
          type = type.getSuperclass();
        }
      }
      throw new IllegalStateException("Không tìm thấy field " + name);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================
  // Create
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/coupons")
  class Create {

    @Test
    @DisplayName("201 + Location, và validFrom/validTo phẳng ra khỏi TstzRange")
    void createsAndFlattensPeriod() throws Exception {
      when(service.create(any())).thenReturn(coupon("SUM25", "25"));

      mockMvc
          .perform(
              post("/api/v1/coupons")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody("SUM25", "2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z")))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", Matchers.endsWith("/api/v1/coupons/" + COUPON_ID)))
          .andExpect(jsonPath("$.code").value("SUM25"))
          // enum → String, không phụ thuộc cách Jackson serialize enum
          .andExpect(jsonPath("$.type").value("PERCENT"))
          // DTO không lộ TstzRange: 2 field phẳng
          .andExpect(jsonPath("$.validFrom").value("2026-06-01T00:00:00Z"))
          .andExpect(jsonPath("$.validTo").value("2026-07-01T00:00:00Z"))
          .andExpect(jsonPath("$.validPeriod").doesNotExist())
          .andExpect(jsonPath("$.usedCount").value(0))
          .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("code chữ thường → 400 VALIDATION_FAILED, chặn ngay ở Bean Validation")
    void rejectsLowercaseCodeBeforeReachingService() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/coupons")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody("sum25", "2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z")))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
          .andExpect(jsonPath("$.errors[0].field").value("code"));

      verify(service, Mockito.never()).create(any());
    }

    /**
     * EXCLUDE constraint là đường duy nhất phát hiện overlap, nên lỗi tới controller dưới dạng
     * {@code DataIntegrityViolationException} chứ không phải một exception nghiệp vụ.
     */
    @Test
    @DisplayName("trùng khoảng thời gian → 409 CPN_PERIOD_OVERLAPS")
    void mapsExcludeViolationToPeriodOverlaps() throws Exception {
      when(service.create(any()))
          .thenThrow(
              new DataIntegrityViolationException(
                  "conflicting key value violates exclusion constraint"
                      + " \"ex_coupons_code_no_overlap\""));

      mockMvc
          .perform(
              post("/api/v1/coupons")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody("SUM25", "2026-06-15T00:00:00Z", "2026-08-01T00:00:00Z")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.CPN_PERIOD_OVERLAPS.code()));
    }
  }

  // ================================================================
  // Read
  // ================================================================

  @Nested
  @DisplayName("GET")
  class Read {

    @Test
    @DisplayName("GET /{id} — 404 khi không có")
    void getByIdNotFound() throws Exception {
      when(service.getById(COUPON_ID))
          .thenThrow(new NotFoundException(ErrorCode.CPN_NOT_FOUND, "không tồn tại"));

      mockMvc
          .perform(get("/api/v1/coupons/{id}", COUPON_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.CPN_NOT_FOUND.code()))
          .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("GET /by-code/{code} — 404 khi code có thật nhưng đang giữa 2 campaign")
    void getByCodeOutsideEveryPeriod() throws Exception {
      when(service.getByCode("SUM25"))
          .thenThrow(new NotFoundException(ErrorCode.CPN_NOT_FOUND, "ngoài khoảng hiệu lực"));

      mockMvc
          .perform(get("/api/v1/coupons/by-code/{code}", "SUM25"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.CPN_NOT_FOUND.code()));
    }

    /**
     * {@code /redemptions} và {@code /{id}} cùng nằm ở tầng đầu dưới {@code /coupons}, mà {@code
     * {id}} lại là UUID. Nếu Spring ưu tiên biến thì "redemptions" sẽ bị ép sang UUID và trả 400.
     * Test này chốt lại rằng segment cố định thắng.
     */
    @Test
    @DisplayName("GET /redemptions — không bị nuốt bởi route /{id}")
    void redemptionsPathIsNotSwallowedByIdPath() throws Exception {
      CouponRedemption row =
          new CouponRedemption(COUPON_ID, UUID.randomUUID(), CUSTOMER_ID, new BigDecimal("250000"));
      setField(row, "id", UUID.randomUUID());
      setField(row, "redeemedAt", Instant.parse("2026-06-15T10:00:00Z"));
      when(service.listCustomerRedemptions(CUSTOMER_ID))
          .thenReturn(List.of(RedemptionResponse.ofHistory(row, "SUM25")));

      mockMvc
          .perform(get("/api/v1/coupons/redemptions").param("customerId", CUSTOMER_ID.toString()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].couponCode").value("SUM25"))
          .andExpect(jsonPath("$[0].discountAmount").value(250000))
          // wasNew không áp dụng cho lịch sử — ofHistory() đặt false thay vì để call site đoán
          .andExpect(jsonPath("$[0].wasNew").value(false));
    }

    /**
     * Trước F04 chưa endpoint nào có {@code @RequestParam} bắt buộc, nên {@code
     * MissingServletRequestParameterException} rơi thẳng xuống catch-all và trả 500 cho một lỗi rõ
     * ràng của client. Handler cho {@code MissingRequestValueException} thêm ở bài này.
     */
    @Test
    @DisplayName("GET /redemptions — thiếu customerId → 400 INVALID_PARAMETER, không phải 500")
    void redemptionsRequiresCustomerId() throws Exception {
      mockMvc
          .perform(get("/api/v1/coupons/redemptions"))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
          .andExpect(jsonPath("$.parameter").value("customerId"));
    }

    @Test
    @DisplayName("GET /redemptions — customerId không phải UUID → 400 INVALID_PARAMETER")
    void redemptionsRejectsMalformedCustomerId() throws Exception {
      mockMvc
          .perform(get("/api/v1/coupons/redemptions").param("customerId", "khong-phai-uuid"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
    }
  }

  // ================================================================
  // Validate
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/coupons/validate")
  class Validate {

    @Test
    @DisplayName("mã hợp lệ → 200 + discountAmount")
    void validCoupon() throws Exception {
      when(service.validate(any()))
          .thenReturn(
              ValidateCouponResponse.ok(
                  COUPON_ID, "SUM25", CouponType.PERCENT, new BigDecimal("250000"), false));

      mockMvc
          .perform(
              post("/api/v1/coupons/validate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validateBody("SUM25", "1000000")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.valid").value(true))
          .andExpect(jsonPath("$.discountAmount").value(250000))
          .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    /**
     * Gõ nhầm mã là luồng bình thường của giỏ hàng, không phải lỗi giao thức — endpoint trả 200 kèm
     * {@code valid=false} để FE không phải bắt exception cho việc xảy ra hàng ngày.
     */
    @Test
    @DisplayName("mã không dùng được vẫn trả 200, KHÔNG phải 4xx")
    void invalidCouponStillReturns200() throws Exception {
      when(service.validate(any()))
          .thenReturn(
              ValidateCouponResponse.fail(ErrorCode.CPN_EXPIRED, "Mã đã hết thời gian áp dụng."));

      mockMvc
          .perform(
              post("/api/v1/coupons/validate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validateBody("SUM25", "1000000")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.valid").value(false))
          .andExpect(jsonPath("$.errorCode").value(ErrorCode.CPN_EXPIRED.code()))
          .andExpect(jsonPath("$.discountAmount").value(0));
    }

    @Test
    @DisplayName("orderSubtotal âm → 400 VALIDATION_FAILED")
    void rejectsNegativeSubtotal() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/coupons/validate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validateBody("SUM25", "-1")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    private static String validateBody(String code, String subtotal) {
      return """
             {"code":"%s","orderSubtotal":%s,"customerId":"%s"}
             """
          .formatted(code, subtotal, CUSTOMER_ID);
    }
  }

  // ================================================================
  // Deactivate
  // ================================================================

  @Nested
  @DisplayName("POST /api/v1/coupons/{id}/deactivate")
  class Deactivate {

    @Test
    @DisplayName("204, không có body")
    void returnsNoContent() throws Exception {
      mockMvc
          .perform(post("/api/v1/coupons/{id}/deactivate", COUPON_ID))
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));

      verify(service).deactivate(COUPON_ID);
    }

    @Test
    @DisplayName("coupon không tồn tại → 404")
    void notFound() throws Exception {
      Mockito.doThrow(new NotFoundException(ErrorCode.CPN_NOT_FOUND, "không tồn tại"))
          .when(service)
          .deactivate(COUPON_ID);

      mockMvc
          .perform(post("/api/v1/coupons/{id}/deactivate", COUPON_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(ErrorCode.CPN_NOT_FOUND.code()));
    }
  }

  /**
   * {@code redeem} / {@code revoke} không có endpoint — chúng phải chạy trong transaction của
   * {@code placeOrder} ở F05. Test này đỏ ngay nếu ai đó mở chúng ra HTTP.
   */
  @Test
  @DisplayName("redeem/revoke KHÔNG expose qua HTTP")
  void redeemAndRevokeAreNotExposed() throws Exception {
    mockMvc
        .perform(post("/api/v1/coupons/{id}/redeem", COUPON_ID))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/v1/coupons/{id}/revoke", COUPON_ID))
        .andExpect(status().isNotFound());
  }
}
