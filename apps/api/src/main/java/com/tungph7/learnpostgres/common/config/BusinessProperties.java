package com.tungph7.learnpostgres.common.config;

import java.time.ZoneId;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình nghiệp vụ dùng chung — prefix {@code app.business}.
 *
 * <p>{@code timezone} là <b>business timezone</b>: múi giờ của cửa hàng, dùng để quyết định "ngày /
 * tháng nghiệp vụ" (vd tháng trong số đơn {@code ORD-YYYYMM-...}). DB và API vẫn lưu / trả UTC;
 * business timezone chỉ dùng khi cần cắt thời gian thành ngày/tháng theo lịch của cửa hàng. Quy ước
 * đầy đủ: {@code study-guide-impl/timezone/study-timezone.md}.
 *
 * <p>Tách khỏi {@code TZ} của JVM / container và {@code TimeZone} của session Postgres: hai thứ đó
 * là chi tiết hạ tầng, đổi theo môi trường (dev, Testcontainers, prod) — business timezone thì
 * không được đổi theo máy chạy.
 *
 * <p>Fail-fast: thiếu hoặc sai tên zone → app không start. Tên zone sai (vd {@code Asia/Hanoi}) bị
 * Spring từ chối ngay lúc bind vì {@link ZoneId#of} ném lỗi; giá trị rỗng bị chặn ở compact
 * constructor.
 */
@ConfigurationProperties(prefix = "app.business")
public record BusinessProperties(ZoneId timezone) {

  public BusinessProperties {
    Objects.requireNonNull(timezone, "app.business.timezone là bắt buộc (vd Asia/Ho_Chi_Minh)");
  }
}
