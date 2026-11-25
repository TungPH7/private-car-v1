package com.tungph7.learnpostgres.feature.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Đánh fail payment — body của {@code POST /api/v1/payments/{id}/fail}: gateway báo thất bại / hết
 * hạn, hoặc admin hủy payment để khách thanh toán lại bằng cách khác.
 *
 * <p>Lý do bắt buộc: payment FAILED mà không có lý do thì đối soát không biết vì sao. Cột {@code
 * failure_reason} là {@code TEXT}; 4000 là trần chung của repo cho cột {@code TEXT} nhận từ request
 * ({@code customerNote}, {@code description}).
 */
public record FailPaymentRequest(@NotBlank @Size(max = 4000) String reason) {}
