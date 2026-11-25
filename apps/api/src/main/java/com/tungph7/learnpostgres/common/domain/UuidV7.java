package com.tungph7.learnpostgres.common.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 generator (RFC 9562 §5.7).
 *
 * <p>Layout: bytes 0..5 = unix_ts_ms (48-bit big-endian) byte 6 = (ver=0111) << 4 | random nibble
 * byte 8 = (var=10) << 6 | random 6-bit bytes còn lại = random
 *
 * <p>Mirror function `app.gen_uuid_v7()` ở DB (V2__app_helpers.sql) — cùng RFC, UUID sinh từ Java
 * hay DB đều hợp lệ và time-ordered.
 *
 * <p>Trade-off: KHÔNG monotonic-within-ms (nếu sinh nhiều UUID trong cùng 1 ms, thứ tự trong ms đó
 * là random). Với app traffic ~1k INSERT/s, va chạm cùng ms vẫn giữ được ordering ở granularity ms
 * — đủ cho B-tree friendly.
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {
    // utility class — không instantiate
  }

  public static UUID generate() {
    long timestampMs = System.currentTimeMillis();
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);

    // 6 byte đầu = unix_ts_ms (big-endian)
    bytes[0] = (byte) (timestampMs >>> 40);
    bytes[1] = (byte) (timestampMs >>> 32);
    bytes[2] = (byte) (timestampMs >>> 24);
    bytes[3] = (byte) (timestampMs >>> 16);
    bytes[4] = (byte) (timestampMs >>> 8);
    bytes[5] = (byte) timestampMs;

    // Byte 6: 4 bit cao = version 7 (0111), 4 bit thấp giữ random
    bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);

    // Byte 8: 2 bit cao = variant '10' (RFC 4122), 6 bit thấp giữ random
    bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

    return toUuid(bytes);
  }

  private static UUID toUuid(byte[] bytes) {
    long msb = 0L;
    long lsb = 0L;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xFFL);
    }
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (bytes[i] & 0xFFL);
    }
    return new UUID(msb, lsb);
  }
}
