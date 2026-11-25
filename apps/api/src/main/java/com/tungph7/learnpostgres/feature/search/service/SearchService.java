package com.tungph7.learnpostgres.feature.search.service;

import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchPageResponse;

/**
 * Tìm sản phẩm (F08) — chính sách quanh câu tìm của {@code SearchQueryRepository}: kiểm đầu vào,
 * chọn ngưỡng so tên, ranh giới transaction.
 *
 * <p>Có từ khoá thì so ở {@link MatchMode#STRICT} trước; chỉ khi không sản phẩm nào khớp mới tìm
 * lại ở {@link MatchMode#RELAXED}. Kết quả ghi mức đã cho ra nó ({@link
 * ProductSearchPageResponse#matchMode()}).
 *
 * <p>Đầu vào kiểm ở đây, sai → 400 {@code VALIDATION_FAILED}; repository không chặn gì.
 *
 * <ul>
 *   <li>Từ khoá (đã bỏ khoảng trắng hai đầu): {@link #MIN_QUERY_LENGTH} – {@link #MAX_QUERY_LENGTH}
 *       ký tự.
 *   <li>Giá: không âm, tối đa {@link #MAX_PRICE_INTEGER_DIGITS} chữ số phần nguyên và {@link
 *       #MAX_PRICE_FRACTION_DIGITS} chữ số lẻ như cột {@code numeric(12,2)}; {@code priceMin <=
 *       priceMax}.
 *   <li>Trang: có phân trang, tối đa {@link #MAX_PAGE_SIZE} sản phẩm.
 * </ul>
 */
public interface SearchService {

  /**
   * Từ khoá ngắn nhất. Một chữ chỉ có 2 trigram, khớp 1 là 0,5: ở {@link MatchMode#RELAXED} nó khớp
   * mọi tên có một từ bắt đầu bằng chữ đó — "a" ra 19.075 / 100.000 sản phẩm trên lab.
   */
  int MIN_QUERY_LENGTH = 2;

  /** Từ khoá dài nhất — đủ cho một tên sản phẩm đầy đủ. Đếm như {@code @Size} (đơn vị UTF-16). */
  int MAX_QUERY_LENGTH = 80;

  /** Số sản phẩm tối đa của một trang. */
  int MAX_PAGE_SIZE = 100;

  /**
   * Chữ số phần nguyên tối đa của {@code priceMin} / {@code priceMax} — như cột {@code price
   * numeric(12,2)}. Không có trần thì số cực lớn tới được pgjdbc, và từ {@code 1e131072} trở lên
   * pgjdbc gửi thành {@code 0}: "giá từ rất lớn" lặng lẽ thành "mọi giá".
   */
  int MAX_PRICE_INTEGER_DIGITS = 10;

  /**
   * Chữ số lẻ tối đa của {@code priceMin} / {@code priceMax}, đếm như client gửi ({@code 100.500}
   * là 3 chữ số lẻ). Không có trần thì {@code 1e-16384} trở xuống làm Postgres từ chối tham số
   * ({@code 22P03}), lỗi lớp 22 → 409.
   */
  int MAX_PRICE_FRACTION_DIGITS = 2;

  /**
   * Một trang kết quả và tổng số sản phẩm khớp, trong một transaction {@code READ ONLY}. Thứ tự
   * theo {@code criteria.sort()}; {@code sort} của {@code pageable} bị bỏ qua.
   *
   * <p>Ngưỡng nới chỉ được thử khi ngưỡng chuẩn không ra sản phẩm nào — trên mọi trang, không riêng
   * trang đang hỏi: trang nằm sau trang cuối của kết quả chuẩn vẫn là kết quả chuẩn (rỗng).
   */
  ProductSearchPageResponse searchProducts(ProductSearchCriteria criteria, Pageable pageable);

  /**
   * Câu trang mà {@link #searchProducts} gửi đi ở mức {@code mode} — SQL, tham số, ngưỡng — sau
   * cùng các bước kiểm đầu vào. Không chạm DB. Admin giải thích (EXPLAIN) đúng câu này.
   */
  SqlStatement pageStatement(ProductSearchCriteria criteria, MatchMode mode, Pageable pageable);
}
