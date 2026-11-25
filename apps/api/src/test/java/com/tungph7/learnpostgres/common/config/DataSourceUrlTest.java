package com.tungph7.learnpostgres.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guard cho JDBC URL ở `application.yml`.
 *
 * <p>Vì sao cần test riêng: mọi test DB đều chạy qua Testcontainers `@ServiceConnection`, URL do
 * container sinh ra và KHÔNG có `currentSchema` → `search_path` mặc định đã chứa `public`. Nghĩa là
 * test suite hoàn toàn mù với lỗi cấu hình này, chỉ app thật mới hỏng.
 *
 * <p>Lỗi cụ thể từng gặp: `currentSchema=app` → `search_path = app` → operator `citext = citext`
 * (extension nằm ở schema `public`) không nhìn thấy được → Postgres hạ xuống `text = text` → so
 * sánh email/slug thành case-SENSITIVE và mất index, im lặng không báo lỗi. Type thì qualify được
 * (`public.citext`), operator thì không.
 */
class DataSourceUrlTest {

  private static final Pattern CURRENT_SCHEMA = Pattern.compile("currentSchema=([^&\"]+)");

  @Test
  @DisplayName("currentSchema phải chứa 'public' để thấy operator của citext")
  void currentSchemaIncludesPublic() throws IOException {
    Matcher matcher = CURRENT_SCHEMA.matcher(readApplicationYml());

    assertThat(matcher.find()).as("application.yml phải khai báo currentSchema").isTrue();

    String[] schemas = matcher.group(1).split(",");
    assertThat(schemas)
        .as("thiếu 'public' thì citext im lặng thành case-sensitive — xem F02 QnA Q21")
        .contains("public");
    assertThat(schemas[0]).as("'app' phải đứng đầu để resolve tên bảng").isEqualTo("app");
  }

  @Test
  @DisplayName("stringtype=unspecified vẫn còn — CitextType dựa vào nó để bind text plain")
  void stringtypeUnspecifiedKept() throws IOException {
    assertThat(readApplicationYml()).contains("stringtype=unspecified");
  }

  private static String readApplicationYml() throws IOException {
    try (InputStream in =
        DataSourceUrlTest.class.getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml phải nằm trên classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
