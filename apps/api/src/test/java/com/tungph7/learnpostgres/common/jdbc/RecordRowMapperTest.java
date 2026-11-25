package com.tungph7.learnpostgres.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.convert.ConversionFailedException;

/**
 * {@link RecordRowMapper} — cùng luật map theo tên với {@code SimplePropertyRowMapper}, nhưng tìm
 * chỉ số cột một lần cho mỗi kết quả, không bao giờ qua {@code findColumn} (lần tìm trượt của nó là
 * một {@code SQLException} kèm stack trace, cho mỗi row). Repository test của từng feature
 * (dashboard, search, admin) đã kiểm việc map mọi record thật trên Postgres; ở đây là những thứ chỉ
 * thấy được khi đếm lời gọi lên {@code ResultSet}.
 */
@ExtendWith(MockitoExtension.class)
class RecordRowMapperTest {

  record Sample(LocalDate periodStart, long orderCount, BigDecimal netRevenue) {}

  record Moment(Instant lastOrderAt) {}

  private static final LocalDate DAY = LocalDate.of(2025, 4, 1);
  private static final BigDecimal MONEY = new BigDecimal("6000000.00");

  @Mock ResultSet rs;
  @Mock ResultSetMetaData metaData;
  @Mock ResultSet otherRs;
  @Mock ResultSetMetaData otherMetaData;

  private void columns(ResultSet resultSet, ResultSetMetaData meta, String... labels)
      throws SQLException {
    when(resultSet.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(labels.length);
    for (int i = 0; i < labels.length; i++) {
      when(meta.getColumnLabel(i + 1)).thenReturn(labels[i]);
    }
  }

  @Test
  @DisplayName(
      "map theo nhãn gạch dưới hoặc đúng tên (không phân biệt hoa thường); metadata đọc một lần mỗi"
          + " kết quả, không gọi findColumn")
  void resolvesColumnsOncePerResultWithoutFindColumn() throws SQLException {
    RecordRowMapper<Sample> mapper = RecordRowMapper.of(Sample.class);
    columns(rs, metaData, "period_start", "ORDERCOUNT", "net_revenue");
    when(rs.getObject(1, LocalDate.class)).thenReturn(DAY);
    when(rs.getLong(2)).thenReturn(5L);
    when(rs.getBigDecimal(3)).thenReturn(MONEY);

    for (int row = 0; row < 3; row++) {
      assertThat(mapper.mapRow(rs, row)).isEqualTo(new Sample(DAY, 5, MONEY));
    }

    verify(metaData, times(1)).getColumnCount();
    verify(rs, never()).findColumn(anyString());
  }

  @Test
  @DisplayName("kết quả mới (row 0) tìm lại chỉ số cột — thứ tự cột khác vẫn map đúng")
  void resolvesAgainForTheNextResult() throws SQLException {
    RecordRowMapper<Sample> mapper = RecordRowMapper.of(Sample.class);
    columns(rs, metaData, "period_start", "order_count", "net_revenue");
    when(rs.getObject(1, LocalDate.class)).thenReturn(DAY);
    when(rs.getLong(2)).thenReturn(5L);
    when(rs.getBigDecimal(3)).thenReturn(MONEY);
    columns(otherRs, otherMetaData, "net_revenue", "period_start", "order_count");
    when(otherRs.getBigDecimal(1)).thenReturn(MONEY);
    when(otherRs.getObject(2, LocalDate.class)).thenReturn(DAY);
    when(otherRs.getLong(3)).thenReturn(7L);

    assertThat(mapper.mapRow(rs, 0)).isEqualTo(new Sample(DAY, 5, MONEY));
    assertThat(mapper.mapRow(otherRs, 0)).isEqualTo(new Sample(DAY, 7, MONEY));
  }

  @Test
  @DisplayName(
      "Instant đọc qua OffsetDateTime — không thử getObject(Instant) (pgjdbc ném 22023 cho mỗi giá"
          + " trị); NULL → null")
  void readsInstantThroughOffsetDateTime() throws SQLException {
    RecordRowMapper<Moment> mapper = RecordRowMapper.of(Moment.class);
    columns(rs, metaData, "last_order_at");
    when(rs.getObject(1, OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2025-04-01T00:15+07:00"), (OffsetDateTime) null);

    assertThat(mapper.mapRow(rs, 0)).isEqualTo(new Moment(Instant.parse("2025-03-31T17:15:00Z")));
    assertThat(mapper.mapRow(rs, 1)).isEqualTo(new Moment(null));
    verify(rs, never()).getObject(1, Instant.class);
  }

  @Test
  @DisplayName("NULL vào component kiểu long → lỗi, không thành 0 (như SimplePropertyRowMapper)")
  void nullIntoPrimitiveFails() throws SQLException {
    RecordRowMapper<Sample> mapper = RecordRowMapper.of(Sample.class);
    columns(rs, metaData, "period_start", "order_count", "net_revenue");
    when(rs.getObject(1, LocalDate.class)).thenReturn(DAY);
    when(rs.getLong(2)).thenReturn(0L);
    when(rs.wasNull()).thenReturn(true);

    assertThatThrownBy(() -> mapper.mapRow(rs, 0)).isInstanceOf(ConversionFailedException.class);
  }

  @Test
  @DisplayName(
      "component không có cột nào khớp → SQLException 42703 ngay ở row đầu, nêu cả hai tên")
  void missingColumnFailsOnTheFirstRow() throws SQLException {
    RecordRowMapper<Sample> mapper = RecordRowMapper.of(Sample.class);
    columns(rs, metaData, "period_start", "order_count", "revenue");

    assertThatThrownBy(() -> mapper.mapRow(rs, 0))
        .isInstanceOfSatisfying(
            SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42703"))
        .hasMessageContaining("netRevenue")
        .hasMessageContaining("net_revenue");
  }
}
