package com.tungph7.learnpostgres.common.domain;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Hibernate UserType cho cột Postgres `citext`.
 *
 * <p>Vì sao cần: - `@JdbcTypeCode(SqlTypes.OTHER)` mặc định route qua `ObjectJdbcType` → bind
 * `setObject(idx, value)` không có type → pgjdbc dispatch String qua đường binary (bytea) trong tổ
 * hợp Hibernate 6 + pgjdbc → DB lưu hex string thay vì plain text (xem QnA-2.md Q1/Q2). - UserType
 * này: 1) `getSqlType() = Types.OTHER` → Hibernate `ddl-auto: validate` match được metadata citext
 * (cũng là OTHER) → app start OK. 2) `nullSafeSet` dùng `setString(...)` tường minh → pgjdbc gửi
 * text; với `stringtype=unspecified` ở JDBC URL, gửi với Oid.UNSPECIFIED → server tự cast về citext
 * → text giữ nguyên ("pg17-uar"). 3) `nullSafeGet` dùng `getString(...)` → đọc citext về String
 * thuần.
 */
public class CitextType implements UserType<String> {

  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<String> returnedClass() {
    return String.class;
  }

  @Override
  public boolean equals(String x, String y) {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(String x) {
    return Objects.hashCode(x);
  }

  @Override
  public String nullSafeGet(
      ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
      throws SQLException {
    return rs.getString(position);
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, String value, int index, SharedSessionContractImplementor session)
      throws SQLException {
    if (value == null) {
      st.setNull(index, Types.OTHER);
    } else {
      st.setString(index, value);
    }
  }

  @Override
  public String deepCopy(String value) {
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public java.io.Serializable disassemble(String value) {
    return value;
  }

  @Override
  public String assemble(java.io.Serializable cached, Object owner) {
    return (String) cached;
  }
}
