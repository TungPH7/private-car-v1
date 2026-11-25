package com.tungph7.learnpostgres.common.domain;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Hibernate {@link UserType} cho cột Postgres {@code tstzrange} ↔ {@link TstzRange}.
 *
 * <p><b>Vì sao viết tay thay vì dùng thư viện.</b> Lựa chọn quen thuộc là {@code
 * hypersistence-utils} với {@code PostgreSQLRangeType} + {@code Range<OffsetDateTime>}. Hai lý do
 * loại:
 *
 * <ol>
 *   <li>{@code PostgreSQLRangeType} chỉ hỗ trợ {@code LocalDate}, {@code LocalDateTime}, {@code
 *       ZonedDateTime} cho kiểu thời gian. Dùng {@code OffsetDateTime} — kiểu mà F04 dựng toàn bộ
 *       API quanh nó — ném {@code IllegalStateException: The class [java.time.OffsetDateTime] is
 *       not supported!} lúc chạy.
 *   <li>Artifact của thư viện đặt tên theo Hibernate MINOR ({@code -hibernate-71}, {@code -73}).
 *       Project chạy Hibernate 7.2.x — không có bản khớp, và mỗi lần bump Spring Boot lại phải dò
 *       lại artifact.
 * </ol>
 *
 * <p>Đây cũng là cách repo đã xử lý {@code citext} — xem {@link CitextType}.
 *
 * <p><b>Bind (ghi).</b> {@link #getSqlType()} trả {@code Types.OTHER} để {@code ddl-auto: validate}
 * khớp metadata của {@code tstzrange} (cũng là OTHER). Giá trị gửi đi dạng text với {@code
 * setObject(..., Types.OTHER)}; JDBC URL có {@code stringtype=unspecified} nên pgjdbc gửi với
 * {@code Oid.UNSPECIFIED} và server tự cast sang {@code tstzrange}. Cùng cơ chế {@link CitextType}
 * dùng.
 *
 * <p><b>Đọc.</b> Postgres trả literal dạng {@code ["2026-06-01 00:00:00+00","2026-07-01
 * 00:00:00+00")}. Parser dưới đây cố ý <em>nghiêm ngặt</em>: gặp bound không phải {@code [...)},
 * gặp bound vô hạn hoặc range rỗng thì ném lỗi thay vì đọc sai thành một khoảng khác. {@code
 * ck_coupons_period_bounded} đã chặn vô hạn/rỗng ở tầng DB, nhưng bound style thì CHECK không chặn
 * — ai đó INSERT {@code tstzrange(a, b, '(]')} bằng SQL thô vẫn qua được CHECK. Ném lỗi ở đây là
 * lớp bảo vệ cuối.
 */
public class TstzRangeType implements UserType<TstzRange> {

  /**
   * Số chữ số tối đa của phần giây lẻ khi parse. {@code timestamptz} của Postgres chỉ tới
   * microsecond (6 chữ số); để 9 là phần dư an toàn, không làm literal hợp lệ nào bị từ chối.
   */
  private static final int MAX_FRACTION_DIGITS = 9;

  /** Literal ngắn nhất về mặt cú pháp: {@code [}, dấu phẩy, {@code )}. */
  private static final int MIN_LITERAL_LENGTH = 3;

  /**
   * Format timestamptz mà Postgres trả về với {@code DateStyle=ISO} (pgjdbc luôn set khi connect).
   *
   * <p>Phần offset có 3 dạng tuỳ giá trị: {@code +00} (giờ tròn), {@code +05:30} (lệch nửa giờ),
   * {@code +00:53:28} (LMT của mốc rất cũ). Thử lần lượt từ dài tới ngắn — {@code appendOffset} bắt
   * buộc khớp đúng pattern nên phải liệt kê đủ, không thể chỉ dùng một dạng.
   */
  private static final DateTimeFormatter PG_TIMESTAMPTZ =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, MAX_FRACTION_DIGITS, true)
          .optionalStart()
          .appendOffset("+HH:MM:ss", "Z")
          .optionalEnd()
          .optionalStart()
          .appendOffset("+HH", "Z")
          .optionalEnd()
          .toFormatter(Locale.ROOT);

  /** Format khi ghi: ISO-8601 với {@code T} và offset đầy đủ — Postgres parse được chắc chắn. */
  private static final DateTimeFormatter PG_LITERAL_OUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx", Locale.ROOT);

  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<TstzRange> returnedClass() {
    return TstzRange.class;
  }

  @Override
  public boolean equals(TstzRange x, TstzRange y) {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(TstzRange x) {
    return Objects.hashCode(x);
  }

  @Override
  public TstzRange nullSafeGet(
      ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
      throws SQLException {
    return parse(rs.getString(position));
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, TstzRange value, int index, SharedSessionContractImplementor session)
      throws SQLException {
    if (value == null) {
      st.setNull(index, Types.OTHER);
    } else {
      st.setObject(index, toLiteral(value), Types.OTHER);
    }
  }

  @Override
  public TstzRange deepCopy(TstzRange value) {
    // record bất biến, 2 field cũng bất biến → share tham chiếu là an toàn.
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(TstzRange value) {
    return value;
  }

  @Override
  public TstzRange assemble(Serializable cached, Object owner) {
    return (TstzRange) cached;
  }

  // -----------------------------------------------------------------
  // Literal <-> TstzRange
  // -----------------------------------------------------------------

  /** {@code ["2026-06-01T00:00:00.000000+00:00","2026-07-01T00:00:00.000000+00:00")}. */
  static String toLiteral(TstzRange value) {
    return "[\"%s\",\"%s\")"
        .formatted(
            PG_LITERAL_OUT.format(value.from().withOffsetSameInstant(ZoneOffset.UTC)),
            PG_LITERAL_OUT.format(value.to().withOffsetSameInstant(ZoneOffset.UTC)));
  }

  /** Parse literal Postgres trả về. Trả null khi cột NULL. Ném lỗi khi range ngoài miền hỗ trợ. */
  static TstzRange parse(String literal) {
    if (literal == null) {
      return null;
    }
    String s = literal.trim();

    if ("empty".equalsIgnoreCase(s)) {
      throw new IllegalStateException(
          "tstzrange rỗng không được hỗ trợ — ck_coupons_period_bounded đáng lẽ đã chặn. "
              + "Literal: "
              + literal);
    }
    if (s.length() < MIN_LITERAL_LENGTH || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ')') {
      throw new IllegalStateException(
          "Chỉ hỗ trợ tstzrange nửa-mở dạng [from,to). Literal đọc được: " + literal);
    }

    String body = s.substring(1, s.length() - 1);
    int comma = splitIndex(body, literal);
    OffsetDateTime from = parseBound(body.substring(0, comma), literal);
    OffsetDateTime to = parseBound(body.substring(comma + 1), literal);
    return new TstzRange(from, to);
  }

  /** Vị trí dấu phẩy ngăn 2 bound, bỏ qua phẩy nằm trong cặp nháy kép. */
  private static int splitIndex(String body, String literal) {
    boolean quoted = false;
    for (int i = 0; i < body.length(); i++) {
      char ch = body.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == ',' && !quoted) {
        return i;
      }
    }
    throw new IllegalStateException("tstzrange thiếu dấu phẩy ngăn 2 bound: " + literal);
  }

  private static OffsetDateTime parseBound(String raw, String literal) {
    String v = raw.trim();
    if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
      v = v.substring(1, v.length() - 1);
    }
    if (v.isEmpty() || "infinity".equalsIgnoreCase(v) || "-infinity".equalsIgnoreCase(v)) {
      throw new IllegalStateException(
          "tstzrange phải bounded 2 đầu — ck_coupons_period_bounded đáng lẽ đã chặn. "
              + "Literal: "
              + literal);
    }
    try {
      return OffsetDateTime.parse(v, PG_TIMESTAMPTZ).withOffsetSameInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw new IllegalStateException(
          "Không parse được bound '%s' của tstzrange %s".formatted(v, literal), e);
    }
  }
}
