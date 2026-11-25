package com.tungph7.learnpostgres.feature.coupon.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.feature.coupon.dto.CouponResponse;
import com.tungph7.learnpostgres.feature.coupon.dto.CreateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.RedemptionResponse;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponRequest;
import com.tungph7.learnpostgres.feature.coupon.dto.ValidateCouponResponse;
import com.tungph7.learnpostgres.feature.coupon.mapper.CouponMapper;
import com.tungph7.learnpostgres.feature.coupon.service.CouponService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API cho coupon.
 *
 * <p><b>{@code redeem} và {@code revoke} cố ý KHÔNG có endpoint.</b> Hai thao tác đó đổi {@code
 * used_count} và phải nằm trong cùng transaction với việc tạo/huỷ đơn hàng. Mở chúng ra HTTP thì
 * mỗi lần gọi là một transaction riêng, nên một đơn hàng tạo hỏng sau khi đã redeem sẽ để lại
 * counter đã trừ mà không có gì kéo lại. F05 gọi thẳng {@link CouponService} trong transaction của
 * {@code placeOrder}.
 *
 * <p>Cùng lý do {@code InventoryController} không expose {@code reserve} / {@code release} / {@code
 * commit}.
 */
@RestController
@RequestMapping("/api/v1/coupons")
@Tag(name = "Coupons", description = "Coupon management (F04)")
public class CouponController {

  private final CouponService service;
  private final CouponMapper mapper;

  public CouponController(CouponService service, CouponMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  // -----------------------------------------------------------------
  // Admin CRUD
  // -----------------------------------------------------------------

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Tạo coupon (admin)",
      description =
          "409 CPN_PERIOD_OVERLAPS nếu đã có campaign cùng code trùng khoảng thời gian. "
              + "Cùng code với khoảng thời gian nối tiếp thì hợp lệ.")
  public ResponseEntity<CouponResponse> create(
      @Valid @RequestBody CreateCouponRequest req, UriComponentsBuilder uriBuilder) {
    var saved = service.create(req);
    URI location = uriBuilder.path("/api/v1/coupons/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location).body(mapper.toResponse(saved));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Lấy coupon theo id")
  public CouponResponse getById(@PathVariable UUID id) {
    return mapper.toResponse(service.getById(id));
  }

  /**
   * Trả campaign đang hiệu lực tại thời điểm gọi, không phải "row active duy nhất của code".
   *
   * <p>Một code có thể có nhiều campaign active nối tiếp nhau — EXCLUDE constraint chỉ chặn trùng
   * khoảng thời gian. Code có thật nhưng đang nằm giữa hai campaign → 404.
   */
  @GetMapping("/by-code/{code}")
  @Operation(
      summary = "Lấy coupon theo code",
      description = "Trả campaign đang hiệu lực tại thời điểm gọi. Ngoài mọi khoảng → 404.")
  public CouponResponse getByCode(@PathVariable String code) {
    return mapper.toResponse(service.getByCode(code));
  }

  @PostMapping("/{id}/deactivate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Ngừng áp dụng coupon (admin)",
      description =
          "Soft disable — không xoá row vì lịch sử redeem tham chiếu tới nó. "
              + "Deactivate cũng nhả lại khoảng thời gian cho campaign mới cùng code.")
  public void deactivate(@PathVariable UUID id) {
    service.deactivate(id);
  }

  // -----------------------------------------------------------------
  // Validate — giỏ hàng gọi trước khi đặt
  // -----------------------------------------------------------------

  /**
   * Luôn trả 200, kể cả khi mã không dùng được — xem {@link ValidateCouponResponse}.
   *
   * <p>Dùng POST chứ không GET dù đây là thao tác đọc: tham số gồm {@code customerId} và {@code
   * orderSubtotal}, đưa lên query string thì customerId nằm trong access log của mọi proxy trên
   * đường đi. Body không bị log mặc định.
   */
  @PostMapping("/validate")
  @Operation(
      summary = "Kiểm tra coupon cho một customer + giá trị đơn",
      description =
          "Chỉ đọc. Luôn trả 200: hợp lệ thì có discountAmount, không hợp lệ thì có "
              + "errorCode + message để hiển thị ở giỏ hàng.")
  public ValidateCouponResponse validate(@Valid @RequestBody ValidateCouponRequest req) {
    return service.validate(req);
  }

  // -----------------------------------------------------------------
  // Lịch sử
  // -----------------------------------------------------------------

  /**
   * Đặt ở {@code CouponController} chứ không phải {@code /customers/{id}/coupon-redemptions}.
   *
   * <p>Cách thứ hai đọc xuôi hơn về mặt REST, nhưng buộc {@code CustomerController} phải inject
   * {@code CouponService} — một phụ thuộc customer → coupon mà repo hiện chưa có ở đâu. Tài nguyên
   * này thuộc về miền coupon, nên để nó ở đây và giữ hai feature độc lập.
   */
  @GetMapping("/redemptions")
  @Operation(
      summary = "Lịch sử redeem của một customer",
      description = "Mới nhất trước. Mỗi dòng kèm couponCode, lấy bằng 2 query cố định.")
  public List<RedemptionResponse> listRedemptions(@RequestParam UUID customerId) {
    return service.listCustomerRedemptions(customerId);
  }
}
