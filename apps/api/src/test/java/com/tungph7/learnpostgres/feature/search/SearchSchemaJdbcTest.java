package com.tungph7.learnpostgres.feature.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

/**
 * Những gì V17 / V18 bảo đảm cho tìm sản phẩm (F08) — chốt trên Postgres thật trước khi bài 02 viết
 * câu tìm dựa vào chúng: hàm {@code app.search_normalize} và index {@code idx_products_name_trgm}.
 * Các test ở đây chỉ có nghĩa khi DB test cùng locale với DB dev — {@code DatabaseSetupJdbcTest}.
 *
 * <p>Mọi thứ chạy trong transaction của {@code @JdbcTest}, rollback cuối test — kể cả {@code SET
 * LOCAL enable_seqscan = off} và sản phẩm thêm vào. Bảng vài row thì planner luôn chọn Seq Scan;
 * tắt Seq Scan để hỏi "index có dùng được cho câu này không", không phải "planner có chọn nó
 * không".
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SearchSchemaJdbcTest extends AbstractContainerBaseTest {

  @Autowired JdbcClient jdbc;

  private String normalize(String input) {
    return jdbc.sql("SELECT app.search_normalize(?)").param(input).query(String.class).single();
  }

  private List<String> plan(String sql, Object param) {
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    return jdbc.sql("EXPLAIN (COSTS OFF) " + sql).param(param).query(String.class).list();
  }

  private void product(String name, String slug) {
    jdbc.sql("INSERT INTO app.products (name, slug, price) VALUES (?, ?, 100000)")
        .params(name, slug)
        .update();
  }

  @Test
  @DisplayName("search_normalize bỏ dấu tiếng Việt (cả Đ) rồi hạ chữ thường; NULL vào → NULL ra")
  void searchNormalizeStripsAccentsThenLowersCase() {
    assertThat(normalize("Ốp lưng ĐEN Điện thoại Ữ Ỹ")).isEqualTo("op lung den dien thoai u y");
    assertThat(normalize("iPhone 15 Pro Max")).isEqualTo("iphone 15 pro max");
    assertThat(jdbc.sql("SELECT app.search_normalize(NULL) IS NULL").query(Boolean.class).single())
        .isTrue();
  }

  @Test
  @DisplayName("search_normalize IMMUTABLE + PARALLEL SAFE — điều kiện để nằm trong index")
  void searchNormalizeIsImmutableAndParallelSafe() {
    assertThat(
            jdbc.sql(
                    """
                    SELECT provolatile::text AS volatility, proparallel::text AS parallel
                    FROM pg_proc
                    WHERE oid = 'app.search_normalize(text)'::regprocedure
                    """)
                .query()
                .singleRow())
        .containsEntry("volatility", "i")
        .containsEntry("parallel", "s");
  }

  @Test
  @DisplayName(
      "V18: idx_products_name_trgm (GIN trigram trên search_normalize(name)) và"
          + " idx_customers_created_at tồn tại, hợp lệ")
  void v18IndexesAreValid() {
    List<Map<String, Object>> indexes =
        jdbc.sql(
                """
SELECT c.relname AS name, i.indisvalid AS valid, pg_get_indexdef(i.indexrelid) AS def
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
WHERE c.relname IN ('idx_products_name_trgm', 'idx_customers_created_at')
ORDER BY c.relname
""")
            .query()
            .listOfRows();

    assertThat(indexes)
        .extracting(r -> r.get("name"), r -> r.get("valid"))
        .containsExactly(
            tuple("idx_customers_created_at", true), tuple("idx_products_name_trgm", true));
    // pg_get_indexdef bỏ tên schema của object nằm trong search_path (app)
    assertThat((String) indexes.get(1).get("def"))
        .contains("USING gin (")
        .contains("search_normalize((name)::text) gin_trgm_ops)");
  }

  @Test
  @DisplayName(
      "Câu tìm viết đúng biểu thức search_normalize(name) dùng được index — LIKE và"
          + " word_similarity; ILIKE trên tên gốc thì không")
  void normalizedPredicatesUseTheTrigramIndex() {
    assertThat(
            plan(
                "SELECT id FROM app.products WHERE app.search_normalize(name) LIKE '%' ||"
                    + " app.search_normalize(?) || '%'",
                "Ốp lưng"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_products_name_trgm"));
    assertThat(
            plan(
                "SELECT id FROM app.products"
                    + " WHERE app.search_normalize(?) <% app.search_normalize(name)",
                "tai nge"))
        .anyMatch(line -> line.contains("Bitmap Index Scan on idx_products_name_trgm"));
    assertThat(plan("SELECT id FROM app.products WHERE name ILIKE '%' || ? || '%'", "Ốp lưng"))
        .noneMatch(line -> line.contains("idx_products_name_trgm"));
  }

  @Test
  @DisplayName(
      "Tìm không dấu ra tên có dấu, gõ sai một chữ vẫn ra (word_similarity) — trên dữ liệu thật")
  void accentInsensitiveAndTypoTolerantMatches() {
    product("Ốp lưng iPhone 15 Pro Max Silicon Đen", "search-schema-1");
    product("Tai nghe AirPods Pro 2 Trắng", "search-schema-2");
    product("Điện thoại Galaxy S24 Ultra 256GB", "search-schema-3");

    assertThat(
            jdbc.sql(
                    """
                    SELECT name FROM app.products
                    WHERE slug LIKE 'search-schema-%'
                      AND app.search_normalize(name) LIKE '%' || app.search_normalize(?) || '%'
                    """)
                .param("op lung")
                .query(String.class)
                .list())
        .containsExactly("Ốp lưng iPhone 15 Pro Max Silicon Đen");
    assertThat(
            jdbc.sql(
                    """
                    SELECT name FROM app.products
                    WHERE slug LIKE 'search-schema-%'
                      AND app.search_normalize(?) <% app.search_normalize(name)
                    """)
                .param("tai nge")
                .query(String.class)
                .list())
        .containsExactly("Tai nghe AirPods Pro 2 Trắng");
  }
}
