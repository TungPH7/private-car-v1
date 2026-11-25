package com.tungph7.learnpostgres.common.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Khoảng thời gian nửa-mở {@code [from, to)} — value object cho cột Postgres {@code tstzrange}.
 *
 * <p>Cố ý KHÔNG mô hình hoá đầy đủ mọi range của Postgres (vô hạn, empty, bound {@code (]} ...).
 * Schema đã thu hẹp miền giá trị: {@code ck_coupons_period_bounded} (V10) bắt buộc {@code lower} và
 * {@code upper} non-null và {@code lower < upper}. Giữ record ở đúng miền đó làm mọi consumer
 * (service, mapper, DTO) không phải viết nhánh xử lý trường hợp không bao giờ xảy ra.
 *
 * <p>Bound style cố định {@code [from, to)} là chủ ý: nửa-mở khiến 2 campaign liên tiếp ({@code
 * [6/1,7/1)} + {@code [7/1,8/1)}) không overlap, nên EXCLUDE constraint chấp nhận cả hai. Nếu DB
 * chứa range kiểu khác, {@link TstzRangeType} ném lỗi thay vì đọc sai — xem lớp đó.
 *
 * <p><b>Cả 2 mốc được chuẩn hoá về UTC</b> ngay trong constructor. Lý do: {@code timestamptz} KHÔNG
 * lưu offset — nó là một instant, và Postgres trả về theo TimeZone của session. Không chuẩn hoá thì
 * ghi {@code 10:00+07} rồi đọc lại ra {@code 03:00Z}, và {@code equals()} sinh bởi record (so từng
 * field) trả false dù cùng một mốc thời gian. Chuẩn hoá làm round-trip equality luôn đúng, đồng
 * thời khớp đúng ngữ nghĩa mà DB thực sự lưu.
 */
public record TstzRange(OffsetDateTime from, OffsetDateTime to) implements Serializable {

  public TstzRange {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException(
          "tstzrange rỗng hoặc đảo ngược: from=%s phải trước to=%s".formatted(from, to));
    }
    from = from.withOffsetSameInstant(ZoneOffset.UTC);
    to = to.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Tạo range từ 2 mốc, ngữ nghĩa {@code [from, to)}. */
  public static TstzRange of(OffsetDateTime from, OffsetDateTime to) {
    return new TstzRange(from, to);
  }

  /**
   * {@code at} có nằm trong khoảng không — tương đương toán tử {@code @>} của Postgres.
   *
   * <p>{@code from <= at < to}, so theo instant. Biên trái thuộc khoảng, biên phải thì không.
   */
  public boolean contains(OffsetDateTime at) {
    Objects.requireNonNull(at, "at must not be null");
    return !at.isBefore(from) && at.isBefore(to);
  }

  /**
   * Có giao với khoảng khác không — tương đương toán tử {@code &&} của Postgres.
   *
   * <p>Dùng để dự đoán trước EXCLUDE constraint sẽ chặn hay không; DB vẫn là nơi quyết định.
   */
  public boolean overlaps(TstzRange other) {
    Objects.requireNonNull(other, "other must not be null");
    return from.isBefore(other.to) && other.from.isBefore(to);
  }
}
