package com.tungph7.learnpostgres.common.jdbc;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SimplePropertyRowMapper;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * Map mỗi row vào một record theo tên cột — tên đúng như component ({@code netRevenue}) hoặc dạng
 * gạch dưới ({@code net_revenue}), không phân biệt hoa thường — như {@link SimplePropertyRowMapper}
 * mà {@code JdbcClient.query(Class)} dùng, nhưng tìm chỉ số cột <b>một lần</b> cho mỗi kết quả, từ
 * metadata.
 *
 * <p>Vì sao không dùng thẳng {@code query(Class)}: {@code SimplePropertyRowMapper} (Spring 7.0.7)
 * tìm cột bằng {@code rs.findColumn(tên component)}, và chỉ khi lệnh đó ném {@code SQLException}
 * mới thử dạng gạch dưới — cho <b>mỗi</b> row, <b>mỗi</b> component. Alias {@code snake_case} của
 * repository không bao giờ khớp lần thử đầu: 365 row × 9 cột là hơn 3.000 exception kèm stack trace
 * cho một request. Đo trên app thật, 1 triệu đơn: {@code /revenue/daily} 365 ngày p50 ~60 ms, trong
 * khi câu SQL chạy ~2 ms ({@code F07__dashboard/06-verify-and-pr.md §5.2}). Spring 7.0.10 đảo thứ
 * tự thử, dạng gạch dưới trước (gh-37297) — lên bản đó thì đo lại bằng §5.2 rồi mới tính bỏ lớp
 * này; riêng {@code Instant} vẫn tốn một exception của pgjdbc cho mỗi giá trị ({@link #instantOf}).
 *
 * <p>Giữ nguyên các luật khác của {@code SimplePropertyRowMapper}: giá trị đọc bằng {@link
 * JdbcUtils#getResultSetValue} (riêng {@code Instant}: {@link #instantOf}), đổi kiểu bằng {@link
 * DefaultConversionService} — {@code NULL} vào component kiểu nguyên thủy ({@code long}) ném lỗi
 * chứ không thành {@code 0}; component không có cột nào khớp thì lỗi ngay ở row đầu ({@code 42703},
 * như {@code findColumn} của pgjdbc), không lặng lẽ để {@code null}.
 *
 * <p>Một instance cho một lần gọi: chỉ số cột lưu trong instance, tìm lại ở row đầu của mỗi kết quả
 * — không dùng chung một instance giữa các thread.
 *
 * <p>Viết cho repository của dashboard (F07); F08 dùng lại cho search và admin — vì vậy nằm ở
 * {@code common} (quy ước PS-003: dùng bởi từ 2 feature trở lên).
 */
public final class RecordRowMapper<T extends Record> implements RowMapper<T> {

  private static final ConversionService CONVERSION = DefaultConversionService.getSharedInstance();

  /**
   * SQLSTATE {@code undefined_column} — cùng mã pgjdbc ném khi {@code findColumn} không thấy cột.
   */
  private static final String UNDEFINED_COLUMN = "42703";

  private final Class<T> type;
  private final Constructor<T> constructor;
  private final String[] names;
  private final Class<?>[] classes;
  private final TypeDescriptor[] targets;
  private int[] columns;

  private RecordRowMapper(Class<T> type) {
    this.type = type;
    RecordComponent[] components = type.getRecordComponents();
    this.names = new String[components.length];
    this.classes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      names[i] = components[i].getName();
      classes[i] = components[i].getType();
    }
    try {
      this.constructor = type.getDeclaredConstructor(classes);
    } catch (NoSuchMethodException ex) {
      throw new IllegalStateException(
          "Record " + type.getName() + " không có canonical constructor", ex);
    }
    this.targets = new TypeDescriptor[components.length];
    for (int i = 0; i < components.length; i++) {
      targets[i] = new TypeDescriptor(new MethodParameter(constructor, i));
    }
  }

  public static <T extends Record> RecordRowMapper<T> of(Class<T> type) {
    return new RecordRowMapper<>(type);
  }

  @Override
  public T mapRow(ResultSet rs, int rowNum) throws SQLException {
    if (rowNum == 0 || columns == null) {
      columns = resolveColumns(rs.getMetaData());
    }
    Object[] args = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      Object value =
          classes[i] == Instant.class
              ? instantOf(rs, columns[i])
              : JdbcUtils.getResultSetValue(rs, columns[i], classes[i]);
      args[i] = CONVERSION.convert(value, targets[i]);
    }
    return BeanUtils.instantiateClass(constructor, args);
  }

  /**
   * pgjdbc 42.7 không đọc thẳng {@code timestamptz} thành {@link Instant} ({@code getObject(i,
   * Instant.class)} ném {@code 22023}); {@code JdbcUtils} bắt lỗi đó rồi đọc lại — lại một
   * exception cho mỗi giá trị. Đọc {@link OffsetDateTime}, kiểu pgjdbc hỗ trợ, rồi đổi.
   */
  private static Instant instantOf(ResultSet rs, int column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** Chỉ số cột (từ 1) của từng component, theo thứ tự component. */
  private int[] resolveColumns(ResultSetMetaData metaData) throws SQLException {
    Map<String, Integer> byLabel = new HashMap<>();
    // Từ cột cuối về cột đầu: nhãn trùng nhau thì cột đầu thắng, như findColumn
    for (int i = metaData.getColumnCount(); i >= 1; i--) {
      byLabel.put(JdbcUtils.lookupColumnName(metaData, i).toLowerCase(Locale.ROOT), i);
    }
    int[] resolved = new int[names.length];
    for (int i = 0; i < names.length; i++) {
      String underscored = JdbcUtils.convertPropertyNameToUnderscoreName(names[i]);
      Integer index = byLabel.get(names[i].toLowerCase(Locale.ROOT));
      if (index == null) {
        index = byLabel.get(underscored);
      }
      if (index == null) {
        throw new SQLException(
            "Không có cột '%s' hay '%s' cho component của %s"
                .formatted(names[i], underscored, type.getSimpleName()),
            UNDEFINED_COLUMN);
      }
      resolved[i] = index;
    }
    return resolved;
  }
}
