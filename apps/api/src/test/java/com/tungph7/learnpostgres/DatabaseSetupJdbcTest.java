package com.tungph7.learnpostgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DB của test ({@link AbstractContainerBaseTest}) giống DB dev ở những điểm F08 dựa vào. Lệch thì
 * test vẫn xanh trong khi DB dev chạy khác — ví dụ thật: hàm chuẩn hoá viết {@code
 * unaccent(lower(x))} cho đúng đáp số trên locale mặc định của image nhưng sai trên {@code
 * --locale=C} của DB dev ({@code F08__search-performance/01-migration.md}).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DatabaseSetupJdbcTest extends AbstractContainerBaseTest {

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName(
      "initdb --locale=C như DB dev (.env POSTGRES_INITDB_ARGS) — chữ có dấu không phải chữ cái"
          + " với LC_CTYPE = C")
  void testDatabaseUsesTheDevLocale() {
    assertThat(
            jdbc.sql(
                    """
                    SELECT datcollate, datctype FROM pg_database
                    WHERE datname = current_database()
                    """)
                .query()
                .singleRow())
        .containsEntry("datcollate", "C")
        .containsEntry("datctype", "C");
    assertThat(jdbc.sql("SELECT lower('ĐIỆN')").query(String.class).single()).isEqualTo("ĐiỆn");
  }

  @Test
  @DisplayName(
      "pg_stat_statements được nạp (shared_preload_libraries như docker-compose.yml) và ghi lại"
          + " câu vừa chạy, hằng số thay bằng tham số")
  void pgStatStatementsIsLoadedAndRecords() {
    jdbc.sql("SELECT 20 + 22").query(Integer.class).single();

    assertThat(
            jdbc.sql("SELECT count(*) FROM pg_stat_statements WHERE query = 'SELECT $1 + $2'")
                .query(Long.class)
                .single())
        .isPositive();
  }
}
