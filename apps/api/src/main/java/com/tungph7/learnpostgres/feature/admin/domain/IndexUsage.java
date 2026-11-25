package com.tungph7.learnpostgres.feature.admin.domain;

/**
 * Phân loại một index theo số lần được quét ({@code idx_scan}) kể từ lần reset thống kê gần nhất.
 * Chỉ là gợi ý: số lần quét không chia theo thời gian, và index giữ ràng buộc UNIQUE / EXCLUDE thì
 * không bỏ được dù chưa ai đọc.
 */
public enum IndexUsage {
  /** Chưa được quét lần nào — ứng viên để bỏ, nếu không giữ ràng buộc nào. */
  UNUSED,
  /** Dưới 100 lần. */
  RARELY_USED,
  /** Từ 100 lần. */
  ACTIVE
}
