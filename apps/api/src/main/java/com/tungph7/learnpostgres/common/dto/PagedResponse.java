package com.tungph7.learnpostgres.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Một trang kết quả — hình dạng JSON chung cho endpoint phân trang (quy ước API-007, {@code
 * docs/coding-conventions/v1/06-api-design.md}).
 *
 * <pre>
 * { "content": [ ... ], "page": { "number": 0, "size": 20, "totalElements": 42, "totalPages": 3 } }
 * </pre>
 *
 * <p>Không trả thẳng {@link Page}: JSON của {@code PageImpl} là chi tiết cài đặt của Spring Data
 * ({@code pageable}, {@code sort}, {@code first}, ...), và chính Spring Data cảnh báo "no guarantee
 * about the stability of the resulting JSON structure". Record này chỉ lộ 4 con số client cần.
 *
 * @param <T> kiểu DTO của từng phần tử — map entity → DTO xong mới bọc
 */
public record PagedResponse<T>(List<T> content, PageMetadata page) {

  /**
   * @param number số trang, tính từ 0 — cùng quy ước với tham số {@code page} của request
   * @param size kích thước trang đã yêu cầu, không phải số phần tử của trang này
   */
  public record PageMetadata(int number, int size, long totalElements, int totalPages) {}

  public static <T> PagedResponse<T> from(Page<T> page) {
    return new PagedResponse<>(
        page.getContent(),
        new PageMetadata(
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
  }
}
