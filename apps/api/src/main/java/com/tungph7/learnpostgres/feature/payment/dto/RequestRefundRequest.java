package com.tungph7.learnpostgres.feature.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu hoàn tiền — body của {@code POST /api/v1/payments/{id}/refunds}. Idempotency key đi qua
 * header {@code Idempotency-Key}.
 *
 * <p>{@code amount} theo đúng khuôn {@code NUMERIC(12,2)}: tối đa 10 chữ số phần nguyên, 2 chữ số
 * lẻ. Không chặn thì DB làm tròn im lặng — {@code 100.005} lưu thành {@code 100.01}, và retry gửi
 * lại đúng {@code 100.005} bị coi là request khác (422).
 *
 * <p>{@code @Digits} đếm số chữ số lẻ ĐÚNG NHƯ CLIENT GỬI: {@code 100.500} bị từ chối dù giá trị
 * vừa 2 chữ số lẻ. Service thì nhận nó (so sau {@code stripTrailingZeros}) vì còn được gọi từ code
 * Java, nơi phép tính {@code BigDecimal} hay sinh số 0 thừa. HTTP chặt hơn service là chiều an
 * toàn: giá trị nào qua được kiểm ở đây cũng qua kiểm đầu vào của service.
 *
 * @param reason cột {@code payment_refunds.reason VARCHAR(255)}
 */
public record RequestRefundRequest(
    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
    @NotBlank @Size(max = 255) String reason) {}
