package com.tungph7.learnpostgres.feature.order.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Đặt đơn.
 *
 * <p>Không có trường tiền nào: subtotal, discount, total đều do server tính từ giá hiện tại của
 * product và từ coupon.
 *
 * <p>{@code List<@NotNull OrderItemRequest>}: {@code @Valid} bỏ qua phần tử {@code null} khi
 * cascade, nên thiếu {@code @NotNull} ở type argument thì body {@code "items": [null]} lọt qua
 * validation và nổ NPE trong service.
 *
 * <p>Bean Validation chỉ chặn được hình dạng request. Những rule cần nhìn cả danh sách hoặc dữ liệu
 * đang có thì service kiểm: không trùng product giữa các dòng, product còn bán, address thuộc đúng
 * customer, coupon còn dùng được.
 *
 * @param couponCode {@code null} hoặc rỗng = không dùng coupon. Tối đa 40 ký tự như {@code
 *     app.coupons.code}
 */
public record PlaceOrderRequest(
    @NotNull UUID customerId,
    @NotEmpty @Valid List<@NotNull OrderItemRequest> items,
    @NotNull UUID shippingAddressId,
    @Size(max = 40) String couponCode,
    @Size(max = 4000) String customerNote) {}
