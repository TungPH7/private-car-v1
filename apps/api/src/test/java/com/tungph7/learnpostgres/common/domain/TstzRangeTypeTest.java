package com.tungph7.learnpostgres.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test cho parser/serializer của {@link TstzRangeType}.
 *
 * <p>Đây là phần rủi ro nhất của mapping viết tay: literal do Postgres trả về có nhiều biến thể
 * (offset {@code +00} / {@code +05:30} / {@code +00:53:28}, có hoặc không có phần giây lẻ). Test ở
 * mức unit để chạy nhanh và bao được các biến thể khó dựng bằng DB thật; round-trip qua DB thật do
 * {@code CouponRepositoryDataJpaTest} lo.
 */
class TstzRangeTypeTest {

  @Nested
  @DisplayName("parse")
  class Parse {

    @ParameterizedTest(name = "offset dạng {0}")
    @CsvSource({
      // literal, instant UTC mong đợi của bound trái
      "'[\"2026-06-01 00:00:00+00\",\"2026-07-01 00:00:00+00\")', 2026-06-01T00:00Z",
      "'[\"2026-06-01 07:00:00+07\",\"2026-07-01 07:00:00+07\")', 2026-06-01T00:00Z",
      "'[\"2026-06-01 05:30:00+05:30\",\"2026-07-01 05:30:00+05:30\")', 2026-06-01T00:00Z",
      "'[\"2026-05-31 21:00:00-03\",\"2026-06-30 21:00:00-03\")', 2026-06-01T00:00Z",
    })
    void parsesEveryOffsetFormPostgresEmits(String literal, String expectedFromUtc) {
      TstzRange r = TstzRangeType.parse(literal);

      assertThat(r.from()).isEqualTo(OffsetDateTime.parse(expectedFromUtc));
      // Chuẩn hoá UTC: bất kể offset trong literal, field luôn về Z.
      assertThat(r.from().getOffset().getTotalSeconds()).isZero();
      assertThat(r.to().getOffset().getTotalSeconds()).isZero();
    }

    @Test
    @DisplayName("giữ được phần giây lẻ (microsecond)")
    void keepsFractionalSeconds() {
      TstzRange r =
          TstzRangeType.parse("[\"2026-06-01 00:00:00.123456+00\",\"2026-07-01 00:00:00+00\")");

      assertThat(r.from()).isEqualTo(OffsetDateTime.parse("2026-06-01T00:00:00.123456Z"));
    }

    @Test
    @DisplayName("cột NULL → null")
    void nullIsNull() {
      assertThat(TstzRangeType.parse(null)).isNull();
    }

    @ParameterizedTest(name = "từ chối: {0}")
    @ValueSource(
        strings = {
          "empty", // range rỗng
          "[\"2026-06-01 00:00:00+00\",\"2026-07-01 00:00:00+00\"]", // bound phải đóng
          "(\"2026-06-01 00:00:00+00\",\"2026-07-01 00:00:00+00\")", // bound trái mở
          "[\"2026-06-01 00:00:00+00\",)", // vô hạn bên phải
          "[,\"2026-07-01 00:00:00+00\")", // vô hạn bên trái
          "[\"2026-06-01 00:00:00+00\")", // thiếu dấu phẩy
        })
    void rejectsRangesOutsideTheSupportedShape(String literal) {
      assertThatThrownBy(() -> TstzRangeType.parse(literal))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("bound không parse được → IllegalStateException kèm literal gốc")
    void rejectsUnparsableBound() {
      assertThatThrownBy(
              () -> TstzRangeType.parse("[\"khong-phai-ngay\",\"2026-07-01 00:00:00+00\")"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("khong-phai-ngay");
    }
  }

  @Nested
  @DisplayName("toLiteral")
  class ToLiteral {

    @Test
    @DisplayName("sinh literal nửa-mở, offset đầy đủ")
    void producesHalfOpenLiteral() {
      TstzRange r =
          TstzRange.of(
              OffsetDateTime.parse("2026-06-01T07:00:00+07:00"),
              OffsetDateTime.parse("2026-07-01T00:00:00Z"));

      assertThat(TstzRangeType.toLiteral(r))
          .isEqualTo("[\"2026-06-01T00:00:00.000000+00:00\",\"2026-07-01T00:00:00.000000+00:00\")");
    }

    @Test
    @DisplayName("round-trip toLiteral -> parse giữ nguyên giá trị")
    void roundTrips() {
      TstzRange original =
          TstzRange.of(
              OffsetDateTime.parse("2026-06-01T00:00:00.123456Z"),
              OffsetDateTime.parse("2026-07-01T10:20:30Z"));

      // toLiteral dùng 'T', parse dùng dấu cách — đổi separator để mô phỏng đường về từ Postgres.
      String pgStyle = TstzRangeType.toLiteral(original).replace("T", " ");

      assertThat(TstzRangeType.parse(pgStyle)).isEqualTo(original);
    }
  }
}
