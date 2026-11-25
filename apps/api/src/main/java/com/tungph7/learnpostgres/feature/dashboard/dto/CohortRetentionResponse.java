package com.tungph7.learnpostgres.feature.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Một tháng trong đường retention của cohort — khách đăng ký trong cùng một tháng của cửa hàng.
 *
 * <p>Mỗi tháng từ {@code monthOffset = 0} (chính tháng đăng ký) tới hết số tháng được hỏi đều có
 * row; tháng không ai mua thì {@code activeCustomers = 0}.
 *
 * @param month ngày 1 của tháng thứ {@code monthOffset}
 * @param cohortSize số khách đăng ký trong tháng cohort — mẫu số của retention
 * @param activeCustomers số khách của cohort có ít nhất một đơn được tính trong tháng này
 * @param retentionPct {@code activeCustomers / cohortSize} theo phần trăm, làm tròn 2 chữ số;
 *     {@code null} khi cohort rỗng
 */
public record CohortRetentionResponse(
    int monthOffset,
    LocalDate month,
    long cohortSize,
    long activeCustomers,
    BigDecimal retentionPct) {}
