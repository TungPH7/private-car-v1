package com.tungph7.learnpostgres.feature.search.domain;

/**
 * Mức chịu lỗi gõ khi so từ khoá với tên sản phẩm — ngưỡng {@code word_similarity} của pg_trgm
 * (tham số {@code pg_trgm.word_similarity_threshold}, dùng cho cặp toán tử {@code <%} / {@code
 * %>}).
 *
 * <p>Số đo trên 100.000 sản phẩm ({@code F08__search-performance/02-domain-and-repository.md}): hạ
 * ngưỡng chung xuống 0,5 thì câu nhiều từ ra nhiều gấp 3–14 lần (khớp từng phần) và chậm hơn — nên
 * ngưỡng nới chỉ dùng khi ngưỡng chuẩn không ra kết quả nào.
 */
public enum MatchMode {

  /** 0,6 — mặc định của pg_trgm. Bắt lỗi gõ nhẹ: "tai nge" ra "Tai nghe" (0,75). */
  STRICT(0.6),

  /**
   * 0,4 — chỉ khi {@link #STRICT} không ra gì. Bắt lỗi thiếu / sai một chữ trong từ ngắn: "iphne"
   * (0,50), "ipone" (0,44) ra "iPhone"; câu nhiều từ ra kết quả gần đúng theo loại hàng.
   */
  RELAXED(0.4);

  private final double threshold;

  MatchMode(double threshold) {
    this.threshold = threshold;
  }

  /** Giá trị đặt vào {@code pg_trgm.word_similarity_threshold}. */
  public double threshold() {
    return threshold;
  }
}
