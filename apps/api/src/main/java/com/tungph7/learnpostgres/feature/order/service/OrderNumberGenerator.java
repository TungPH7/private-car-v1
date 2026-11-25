package com.tungph7.learnpostgres.feature.order.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.feature.order.repository.OrderRepository;

/**
 * Sinh số đơn {@code ORD-YYYYMM-NNNNNN}.
 *
 * <p>Chia việc: DB cấp {@code NNNNNN} (sequence — duy nhất giữa mọi connection / instance), app
 * ghép chuỗi. Không format bằng SQL vì 2 lỗi đã gặp với bản {@code 'ORD-' || to_char(now(),
 * 'YYYYMM') || '-' || lpad(nextval(...)::text, 6, '0')}:
 *
 * <ul>
 *   <li><b>Tháng phụ thuộc {@code TimeZone} của session Postgres</b> — DB dev là {@code
 *       Asia/Ho_Chi_Minh}, Testcontainers là {@code UTC}: cùng một thời điểm ra 2 tháng khác nhau.
 *       Ở đây tháng tính theo business timezone lấy từ config ({@link BusinessProperties}), không
 *       phụ thuộc máy nào đang chạy.
 *   <li><b>{@code lpad} cắt chuỗi dài hơn độ dài đích</b> — từ đơn thứ 1.000.000 số bị cắt thành số
 *       của một đơn cũ. {@code %06d} chỉ pad, không bao giờ cắt.
 * </ul>
 *
 * <p>Thời gian lấy từ {@link Clock} inject vào (không gọi {@code YearMonth.now()} trần) để test cố
 * định được "bây giờ" và kiểm đúng mốc giao tháng. Cùng nguồn đồng hồ JVM với {@code created_at}
 * (JPA Auditing), nên tháng trong số đơn và {@code created_at} không lệch nhau vì đồng hồ app và
 * đồng hồ DB lệch.
 *
 * <p>Độ dài tối đa: {@code ORD-} (4) + {@code YYYYMM} (6) + {@code -} (1) + tối đa 19 chữ số của
 * {@code BIGINT} = 30 ký tự, vừa {@code order_number VARCHAR(32)}.
 */
@Component
public class OrderNumberGenerator {

  /** Locale.ROOT: số đơn là định danh, không được đổi theo locale mặc định của JVM. */
  private static final DateTimeFormatter YEAR_MONTH =
      DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

  private final OrderRepository orderRepository;
  private final Clock clock;
  private final ZoneId businessZone;

  public OrderNumberGenerator(
      OrderRepository orderRepository, Clock clock, BusinessProperties businessProperties) {
    this.orderRepository = orderRepository;
    this.clock = clock;
    this.businessZone = businessProperties.timezone();
  }

  /**
   * Số đơn kế tiếp. Phải gọi trong transaction read-write — {@code nextval()} bị từ chối trong
   * transaction read-only (SQLSTATE 25006).
   */
  public String next() {
    long sequence = orderRepository.nextOrderNumberSequence();
    YearMonth month = YearMonth.now(clock.withZone(businessZone));
    return String.format(Locale.ROOT, "ORD-%s-%06d", month.format(YEAR_MONTH), sequence);
  }
}
