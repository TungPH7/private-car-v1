package com.tungph7.learnpostgres.feature.search.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.tungph7.learnpostgres.common.dto.PagedResponse;
import com.tungph7.learnpostgres.common.dto.PagedResponse.PageMetadata;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;

/**
 * Một trang kết quả tìm sản phẩm — cùng hình dạng {@link PagedResponse} ({@code content} + {@code
 * page}), thêm mức so tên đã cho ra trang đó.
 *
 * @param matchMode {@link MatchMode#STRICT}: kết quả ở ngưỡng chuẩn. {@link MatchMode#RELAXED}:
 *     không sản phẩm nào khớp ở ngưỡng chuẩn, trang này là kết quả gần đúng ở ngưỡng nới — client
 *     nên nói rõ điều đó ("Không tìm thấy kết quả khớp, xem kết quả gần đúng"). {@code null}: tìm
 *     không có từ khoá, không so tên
 */
public record ProductSearchPageResponse(
    List<ProductSearchResponse> content, PageMetadata page, MatchMode matchMode) {

  public static ProductSearchPageResponse of(
      Page<ProductSearchResponse> page, MatchMode matchMode) {
    PagedResponse<ProductSearchResponse> paged = PagedResponse.from(page);
    return new ProductSearchPageResponse(paged.content(), paged.page(), matchMode);
  }
}
