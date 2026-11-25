package com.tungph7.learnpostgres.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SqlStatement} ngoài transaction. Đường có transaction — {@code set_config} thật trên
 * Postgres — nằm trong {@code SearchQueryRepositoryJdbcTest} (STRICT / RELAXED đổi được kết quả).
 */
class SqlStatementTest {

  @Test
  @DisplayName(
      "Có setting mà không có transaction → IllegalStateException, không chạy câu nào — setting"
          + " sẽ mất trước khi câu chạy")
  void settingsNeedATransaction() {
    JdbcClient jdbc = mock(JdbcClient.class);
    SqlStatement statement =
        new SqlStatement("SELECT 1", Map.of(), Map.of("pg_trgm.word_similarity_threshold", "0.6"));

    assertThatThrownBy(() -> statement.applySettings(jdbc))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pg_trgm.word_similarity_threshold")
        .hasMessageContaining("transaction");
    verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("Không có setting → không chạy gì, không cần transaction")
  void noSettingsRunNothing() {
    JdbcClient jdbc = mock(JdbcClient.class);

    new SqlStatement("SELECT 1", Map.of(), Map.of()).applySettings(jdbc);

    verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("params / settings là bản sao không sửa được — đổi map gốc không đổi câu")
  void keepsImmutableCopies() {
    Map<String, Object> params = new HashMap<>(Map.of("q", "op lung"));
    SqlStatement statement = new SqlStatement("SELECT :q", params, Map.of());

    params.put("q", "khác");

    assertThat(statement.params()).containsExactly(Map.entry("q", "op lung"));
    assertThatThrownBy(() -> statement.params().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
