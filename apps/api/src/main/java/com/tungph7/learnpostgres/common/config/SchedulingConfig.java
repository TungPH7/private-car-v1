package com.tungph7.learnpostgres.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật {@code @Scheduled} cho toàn app.
 *
 * <p>Chỉ bật hạ tầng. Mỗi job tự quyết có chạy hay không bằng {@code @ConditionalOnProperty} của
 * chính nó (vd {@code OrderExpirationJob}) — nhờ vậy profile test tắt được từng job mà không phải
 * tắt scheduling của cả app.
 *
 * <p>Số thread của scheduler = số job ({@code spring.task.scheduling.pool.size}, hiện là 2: {@code
 * OrderExpirationJob}, {@code DailyRevenueRefreshJob}). Spring Boot mặc định 1 thread cho mọi job:
 * job refresh MV đứng chờ khóa thì job expire đơn cũng đứng theo — đơn quá hạn giữ hàng và lượt
 * coupon tới khi refresh xong. Một job không bao giờ tự chạy chồng chính nó (lần sau chỉ được xếp
 * lịch khi lần trước xong), nên thêm thread chỉ để các job khác nhau không chờ nhau.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
