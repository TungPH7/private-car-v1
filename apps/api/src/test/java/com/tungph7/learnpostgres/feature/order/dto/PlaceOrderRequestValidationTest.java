package com.tungph7.learnpostgres.feature.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation của {@link PlaceOrderRequest} — lớp chặn đầu tiên, trước khi request tới service.
 *
 * <p>Chạy Validator thật, không qua Spring MVC: thứ đang kiểm là annotation trên record, còn việc
 * controller gắn {@code @Valid} là chuyện của bài 04.
 */
class PlaceOrderRequestValidationTest {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  @AfterAll
  static void closeFactory() {
    FACTORY.close();
  }

  private static PlaceOrderRequest request(List<OrderItemRequest> items, String couponCode) {
    return new PlaceOrderRequest(UUID.randomUUID(), items, UUID.randomUUID(), couponCode, null);
  }

  private static Set<String> violatedPaths(PlaceOrderRequest req) {
    return VALIDATOR.validate(req).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("request hợp lệ → không vi phạm gì")
  void validRequest() {
    assertThat(violatedPaths(request(List.of(new OrderItemRequest(UUID.randomUUID(), 2)), "SUM10")))
        .isEmpty();
  }

  @Test
  @DisplayName("items rỗng → vi phạm ở items (AC-10)")
  void emptyItems() {
    assertThat(violatedPaths(request(List.of(), null))).containsExactly("items");
  }

  @Test
  @DisplayName("quantity 0 → vi phạm ở items[0].quantity (AC-11)")
  void zeroQuantity() {
    assertThat(violatedPaths(request(List.of(new OrderItemRequest(UUID.randomUUID(), 0)), null)))
        .containsExactly("items[0].quantity");
  }

  /** {@code @Valid} bỏ qua phần tử null khi cascade — chỉ {@code @NotNull} ở type argument chặn. */
  @Test
  @DisplayName("items chứa null → vi phạm ở phần tử đó, không lọt xuống service")
  void nullElement() {
    PlaceOrderRequest req =
        request(Arrays.asList(new OrderItemRequest(UUID.randomUUID(), 1), null), null);

    assertThat(violatedPaths(req)).containsExactly("items[1].<list element>");
  }

  @Test
  @DisplayName("couponCode dài hơn cột app.coupons.code (40) → vi phạm")
  void couponCodeTooLong() {
    assertThat(
            violatedPaths(
                request(List.of(new OrderItemRequest(UUID.randomUUID(), 1)), "X".repeat(41))))
        .containsExactly("couponCode");
  }
}
