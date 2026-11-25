package com.tungph7.learnpostgres.feature.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một refund — trả từ {@code GET /refunds/{id}} và danh sách refund của payment.
 *
 * <p>Không có {@code gatewayResponse}, cùng lý do {@link PaymentResponse}.
 *
 * @param actor người yêu cầu hoàn tiền — lấy từ principal của request lúc tạo
 * @param completedAt có khi và chỉ khi {@code status = COMPLETED}
 */
public record RefundResponse(
    UUID id,
    UUID paymentId,
    String idempotencyKey,
    String refundReference,
    BigDecimal amount,
    String reason,
    String status,
    Instant requestedAt,
    Instant completedAt,
    String actor) {}
