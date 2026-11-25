package com.tungph7.learnpostgres.feature.payment.dto;

/**
 * Một lần gọi idempotent vừa tạo mới, hay trả lại thứ đã tạo từ trước với cùng idempotency key.
 * Controller (bài 04) trả 201 cho {@link #NEW}, 200 cho {@link #EXISTING}.
 *
 * <p>Enum chứ không phải {@code String} như {@code status}: {@code status} chép một enum của domain
 * (có thể thêm giá trị), còn tập này do chính API định nghĩa và đóng — enum cho compiler kiểm thay
 * vì so chuỗi. JSON vẫn là {@code "NEW"} / {@code "EXISTING"}.
 */
public enum ResultKind {
  NEW,
  EXISTING
}
