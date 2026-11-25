package com.tungph7.learnpostgres.feature.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một payment — trả từ {@code GET /payments/{id}}, danh sách payment của order và {@code POST
 * /payments/{id}/fail}.
 *
 * <p>{@code status}, {@code gateway} là {@code String} như {@code OrderResponse.status} (F05): JSON
 * ổn định khi enum thêm hằng mới.
 *
 * <p>Hai field của entity cố ý không có ở đây:
 *
 * <ul>
 *   <li>{@code gatewayResponse} — payload gốc của gateway có thể mang dữ liệu của khách (email, 4
 *       số cuối thẻ, ...). Dùng cho đối soát nội bộ, không ra API.
 *   <li>{@code version} — mọi đường ghi payment là UPDATE native, không tăng {@code version} (bài
 *       02 §5.1): field luôn 0, không mang thông tin gì.
 * </ul>
 */
public record PaymentResponse(
    UUID id,
    UUID orderId,
    String idempotencyKey,
    String paymentReference,
    BigDecimal amount,
    String currency,
    String gateway,
    String status,
    String failureReason,
    Instant authorizedAt,
    Instant capturedAt,
    Instant failedAt,
    Instant createdAt) {}
