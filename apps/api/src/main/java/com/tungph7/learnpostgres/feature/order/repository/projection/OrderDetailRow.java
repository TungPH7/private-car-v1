package com.tungph7.learnpostgres.feature.order.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 1 row của {@code OrderRepository.findDetailById} — tên getter khớp alias SQL.
 *
 * <p>{@code couponId} / {@code couponCode} là {@code null} khi đơn không dùng coupon. {@code
 * itemsJson} và {@code historyJson} là JSON array (luôn có, rỗng thì {@code []}) — service parse ở
 * app layer.
 */
public interface OrderDetailRow {

  UUID getId();

  String getOrderNumber();

  String getStatus();

  BigDecimal getSubtotal();

  BigDecimal getDiscountAmount();

  BigDecimal getShippingFee();

  BigDecimal getTotal();

  String getShippingRecipient();

  String getShippingPhone();

  String getShippingLine1();

  String getShippingLine2();

  String getShippingCity();

  String getShippingCountry();

  Instant getCreatedAt();

  UUID getCustomerId();

  String getCustomerEmail();

  String getCustomerName();

  UUID getCouponId();

  String getCouponCode();

  String getItemsJson();

  String getHistoryJson();
}
