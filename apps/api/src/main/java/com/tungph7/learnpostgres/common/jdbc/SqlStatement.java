package com.tungph7.learnpostgres.common.jdbc;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Một câu SQL do code dựng, đủ để chạy lại y hệt ở chỗ khác: tham số đặt tên ({@code :q}) và những
 * tham số cấu hình của Postgres mà câu cần. EXPLAIN của admin (F08) giải thích chính câu tìm sản
 * phẩm app gửi đi, cùng ngưỡng pg_trgm — không phải một bản chép lại.
 *
 * <p>{@code sql} chỉ gồm hằng số trong code; giá trị từ client đi vào {@code params}, không bao giờ
 * nối vào chuỗi.
 *
 * @param settings tham số cấu hình đặt cho transaction trước khi chạy câu ({@link #applySettings})
 */
public record SqlStatement(String sql, Map<String, Object> params, Map<String, String> settings) {

  public SqlStatement {
    params = Map.copyOf(params);
    settings = Map.copyOf(settings);
  }

  /**
   * Đặt {@code settings} cho transaction đang chạy — {@code set_config(name, value, true)}, như
   * {@code SET LOCAL}: hết transaction là hết, không dính sang request khác dùng lại connection.
   * Gọi ngay trước khi chạy {@code sql}.
   *
   * @throws IllegalStateException có setting mà không có transaction — mỗi lệnh khi đó chạy trên
   *     một connection riêng, setting mất trước khi câu chạy
   */
  public void applySettings(JdbcClient jdbc) {
    if (settings.isEmpty()) {
      return;
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Câu cần " + settings + " — đặt bằng set_config(..., true), phải chạy trong transaction");
    }
    settings.forEach(
        (name, value) ->
            jdbc.sql("SELECT set_config(:name, :value, true)")
                .param("name", name)
                .param("value", value)
                .query(String.class)
                .single());
  }
}
