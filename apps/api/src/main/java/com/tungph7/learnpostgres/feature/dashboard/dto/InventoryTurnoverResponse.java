package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vòng quay hàng tồn kho của một sản phẩm trong khoảng thời gian — tính từ sổ {@code
 * inventory_movements}, không từ đơn hàng.
 *
 * <p>Tồn kho tại một thời điểm = tồn kho hiện tại trừ tổng {@code delta_on_hand} từ thời điểm đó
 * tới nay. Tồn kho bình quân lấy trung bình hai đầu kỳ — xấp xỉ: hàng nhập rồi bán hết giữa kỳ
 * không làm hai đầu kỳ đổi.
 *
 * @param unitsSold tổng {@code delta_sold} trong khoảng — hàng chốt bán lúc đơn DELIVERED, nên theo
 *     ngày giao, không theo ngày đặt
 * @param onHandStart tồn kho lúc 00:00 ngày {@code from} (giờ cửa hàng)
 * @param onHandEnd tồn kho lúc 00:00 ngày sau {@code to}
 * @param turnoverRatio {@code unitsSold / ((onHandStart + onHandEnd) / 2)}, làm tròn 2 chữ số;
 *     {@code null} khi tồn kho bình quân = 0
 */
public record InventoryTurnoverResponse(
    UUID productId,
    String productName,
    String sku,
    long unitsSold,
    long onHandStart,
    long onHandEnd,
    BigDecimal turnoverRatio) {}
