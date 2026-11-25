package com.tungph7.learnpostgres.feature.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

class SigningKeysTest {

  /** 32 byte, dựng lúc chạy: không phải khóa thật, và không trông như một khóa trong mã nguồn. */
  private static final String SECRET = "k".repeat(32);

  @Test
  void a_secret_of_32_bytes_is_the_key() {
    SecretKey key = SigningKeys.of(SECRET, true);

    assertThat(key.getEncoded()).isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  void a_secret_shorter_than_32_bytes_is_refused() {
    assertThatThrownBy(() -> SigningKeys.of("too-short", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  void no_secret_in_prod_is_refused() {
    assertThatThrownBy(() -> SigningKeys.of("  ", true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("required in the prod profile");
  }

  @Test
  void no_secret_outside_prod_gives_a_random_key_each_time() {
    SecretKey first = SigningKeys.of(null, false);
    SecretKey second = SigningKeys.of("", false);

    assertThat(first.getEncoded()).hasSize(SigningKeys.MIN_BYTES);
    assertThat(first.getEncoded()).isNotEqualTo(second.getEncoded());
  }
}
