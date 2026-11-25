package com.tungph7.learnpostgres.feature.order.domain;

import com.tungph7.learnpostgres.feature.customer.domain.Address;

/**
 * Bản chụp địa chỉ giao hàng tại thời điểm đặt đơn — 6 cột {@code shipping_*} của {@code
 * app.orders} (V12).
 *
 * <p>Tồn tại vì 2 lý do:
 *
 * <ul>
 *   <li>Constructor của {@link Order} không vượt ngưỡng checkstyle {@code ParameterNumber} (max 8):
 *       6 field địa chỉ gom thành 1 tham số.
 *   <li>{@link #of(Address)} chụp ĐỦ 6 field trong một chỗ. Chụp tay từng field ở service là cách
 *       dễ quên nhất — quên {@code line2} là đơn giao thiếu số nhà mà không có lỗi nào báo.
 * </ul>
 *
 * <p>{@code ward / district / state_region / postal_code} của {@code addresses} không có cột tương
 * ứng ở V12 nên không chụp (xem {@code 01-migration.md §1.3}).
 */
public record ShippingSnapshot(
    String recipient,
    String phone,
    String addressLine1,
    String addressLine2,
    String city,
    String country) {

  /** Chụp từ address của customer — dùng ở {@code placeOrder}. */
  public static ShippingSnapshot of(Address address) {
    return new ShippingSnapshot(
        address.getFullName(),
        address.getPhone(),
        address.getLine1(),
        address.getLine2(),
        address.getCity(),
        address.getCountryCode());
  }
}
