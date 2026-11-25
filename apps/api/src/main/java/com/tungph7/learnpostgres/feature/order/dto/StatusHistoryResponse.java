package com.tungph7.learnpostgres.feature.order.dto;

import java.time.Instant;

/**
 * Một lần đổi trạng thái của đơn. {@code fromStatus = null} là dòng khởi tạo lúc đặt đơn.
 *
 * <p>Dùng chung cho {@code GET /orders/{id}/history} và mảng {@code history} của {@link
 * OrderDetailResponse} — cùng hình dạng, nên không có {@code orderId} (đã nằm trên URL / ở đầu
 * detail). Tên field trùng khóa JSON của {@code historyJson} trong {@code
 * OrderRepository.findDetailById}.
 */
public record StatusHistoryResponse(
    String fromStatus, String toStatus, String reason, String actor, Instant changedAt) {}
