package com.tungph7.learnpostgres.feature.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;
import com.tungph7.learnpostgres.feature.customer.domain.Customer;

/** Unit test cho phần logic nằm trong entity: tính total, chuẩn hoá tiền, chụp snapshot. */
class OrderTest {

  private static final ShippingSnapshot SHIPPING =
      new ShippingSnapshot("John Doe", "+84901234567", "1 Le Loi", "Tầng 3", "HCM", "VN");

  private static Order order(String subtotal, String discount, String shippingFee) {
    return new Order(
        "ORD-202609-000001",
        UUID.randomUUID(),
        UUID.randomUUID(),
        SHIPPING,
        new BigDecimal(subtotal),
        new BigDecimal(discount),
        new BigDecimal(shippingFee),
        null);
  }

  @Test
  @DisplayName("total = subtotal - discount + shipping, do entity tính")
  void totalIsComputed() {
    Order o = order("500000", "125000", "30000");

    assertThat(o.getTotal()).isEqualByComparingTo("405000");
  }

  @Test
  @DisplayName("tiền chuẩn hoá về scale 2 — khớp NUMERIC(12,2)")
  void moneyIsNormalisedToScaleTwo() {
    Order o = order("100", "0", "0.5");

    assertThat(o.getSubtotal()).hasToString("100.00");
    assertThat(o.getDiscountAmount()).hasToString("0.00");
    assertThat(o.getShippingFee()).hasToString("0.50");
    assertThat(o.getTotal()).hasToString("100.50");
  }

  @Test
  @DisplayName("tiền có hơn 2 chữ số thập phân → từ chối, không âm thầm làm tròn")
  void moreThanTwoDecimalsIsRejected() {
    // 10.00 - 3.335 → DB lưu 3.34 nhưng total tính từ 3.335: đúng case làm ck_orders_total_match
    // fail ở 01-migration §1.4. Chặn ở đây để lỗi chỉ đúng nguyên nhân.
    assertThatThrownBy(() -> order("10.00", "3.335", "0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("discountAmount");
  }

  @Test
  @DisplayName("số 0 ở cuối không bị coi là chữ số thập phân thừa")
  void trailingZerosAreAccepted() {
    assertThat(order("10.000", "0", "0").getSubtotal()).hasToString("10.00");
  }

  @Test
  @DisplayName("đơn mới: PENDING, chưa có timestamp trạng thái nào")
  void newOrderIsPending() {
    Order o = order("100", "0", "0");

    assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(o.getPaidAt()).isNull();
    assertThat(o.getShippedAt()).isNull();
    assertThat(o.getDeliveredAt()).isNull();
    assertThat(o.getCancelledAt()).isNull();
    assertThat(o.getCancelReason()).isNull();
  }

  @Test
  @DisplayName("snapshot copy đủ 6 field vào entity")
  void snapshotFieldsAreCopied() {
    Order o = order("100", "0", "0");

    assertThat(o.getShippingRecipient()).isEqualTo("John Doe");
    assertThat(o.getShippingPhone()).isEqualTo("+84901234567");
    assertThat(o.getShippingAddressLine1()).isEqualTo("1 Le Loi");
    assertThat(o.getShippingAddressLine2()).isEqualTo("Tầng 3");
    assertThat(o.getShippingCity()).isEqualTo("HCM");
    assertThat(o.getShippingCountry()).isEqualTo("VN");
  }

  @Test
  @DisplayName("ShippingSnapshot.of(address) chụp cả line2 và country")
  void snapshotFromAddress() {
    Customer customer = new Customer("snap@example.com", null, "John", "Doe");
    Address address =
        new Address(
            customer, AddressType.SHIPPING, "John Doe", "+84901234567", "1 Le Loi", "HCM", "vn");
    address.setLine2("Tầng 3");

    assertThat(ShippingSnapshot.of(address))
        .isEqualTo(
            new ShippingSnapshot("John Doe", "+84901234567", "1 Le Loi", "Tầng 3", "HCM", "VN"));
  }
}
