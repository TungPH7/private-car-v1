package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;

/**
 * Phễu của các đơn đặt trong khoảng thời gian.
 *
 * <p>{@code paid} / {@code shipped} / {@code delivered} đếm đơn đã <b>đi qua</b> bước đó (có mốc
 * {@code paid_at} / {@code shipped_at} / {@code delivered_at}), kể cả đơn bị hủy sau đó — mỗi bước
 * là tập con của bước trước. {@code pending} / {@code cancelled} / {@code expired} đếm theo trạng
 * thái hiện tại. Đối chiếu với doanh thu: {@code paid - cancelledAfterPaid} = số đơn được tính.
 *
 * <p>Tỷ lệ là phần trăm, làm tròn 2 chữ số, {@code null} khi mẫu số = 0.
 *
 * @param placed mọi đơn đặt trong khoảng
 * @param cancelledAfterPaid đơn CANCELLED đã từng thanh toán — đã nằm trong {@code paid}
 * @param paidRate {@code paid / placed}
 * @param shippedRate {@code shipped / paid}
 * @param deliveredRate {@code delivered / shipped}
 * @param cancelRate {@code cancelled / placed}
 * @param expireRate {@code expired / placed}
 */
public record OrderFunnelResponse(
    long placed,
    long paid,
    long shipped,
    long delivered,
    long pending,
    long cancelled,
    long cancelledAfterPaid,
    long expired,
    BigDecimal paidRate,
    BigDecimal shippedRate,
    BigDecimal deliveredRate,
    BigDecimal cancelRate,
    BigDecimal expireRate) {}
