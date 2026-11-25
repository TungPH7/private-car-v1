package com.tungph7.learnpostgres.feature.search.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.common.jdbc.RecordRowMapper;
import com.tungph7.learnpostgres.common.jdbc.SqlStatement;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.domain.SearchSort;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchResponse;

/**
 * Tìm sản phẩm (F08) — SQL viết tay qua {@link JdbcClient} trên {@code app.products}, kết quả map
 * vào {@link ProductSearchResponse} bằng {@link RecordRowMapper}.
 *
 * <p>Từ khoá so với hai thứ:
 *
 * <ul>
 *   <li><b>Tên</b>: {@code app.search_normalize(p.name) %> app.search_normalize(:q)} — {@code
 *       word_similarity(q, name)} của pg_trgm đạt ngưỡng, trên chuỗi đã bỏ dấu rồi hạ chữ thường
 *       (V17). Đúng biểu thức của {@code idx_products_name_trgm} (V18) nên dùng được index. {@code
 *       word_similarity} so từ khoá với <b>đoạn</b> giống nhất của tên: tên dài không làm loãng
 *       điểm như {@code similarity}, thứ tự từ không quan trọng, lỗi gõ nhẹ vẫn khớp. Ngưỡng theo
 *       {@link MatchMode}, đặt cho transaction trước khi chạy câu — vì vậy cần transaction.
 *   <li><b>SKU</b>: {@code p.sku = upper(:q)} — trùng cả mã. Không khớp theo tiền tố: trên bộ dữ
 *       liệu đo, tiền tố "LAB" trùng 95.000 / 100.000 SKU — gõ một từ ngắn là ra cả catalog.
 * </ul>
 *
 * <p>Đếm tổng: có từ khoá thì {@code count(*) OVER ()} ngay trong câu. Đằng nào câu cũng phải chấm
 * mọi sản phẩm khớp, và window giữ plan đi qua index trigram: thiếu nó, "mới nhất" + {@code LIMIT}
 * làm planner đi theo index {@code created_at} rồi tính {@code word_similarity} cho từng row — từ
 * khoá không khớp gì là quét đủ 100.000 sản phẩm (~0,5 s). Không có từ khoá thì đếm bằng câu riêng:
 * trang đi theo index và dừng sau {@code LIMIT} row, còn window bắt đọc mọi row (~60 ms so với ~10
 * ms).
 *
 * <p>Câu dựng từ các mảnh hằng số của class, chỉ điều kiện có giá trị mới có mặt — mỗi tổ hợp một
 * câu, một plan riêng. Câu "bắt mọi trường hợp" {@code (:x IS NULL OR ...)} cho plan generic không
 * dùng được index của điều kiện tùy chọn. Giá trị luôn đi qua tham số.
 *
 * <p><b>Có từ khoá thì luôn lập plan theo đúng từ khoá</b> — {@code plan_cache_mode =
 * force_custom_plan}, đặt cùng transaction với ngưỡng. pgjdbc chuyển một câu lặp lại sang prepared
 * statement của server từ lần thứ 5 trên một connection; từ đó Postgres được dùng plan generic, lập
 * một lần cho mọi từ khoá. Với câu tìm, plan đó ước lượng {@code %>} bằng hằng số mặc định (1%
 * bảng), và không tính sẵn {@code app.search_normalize(:q)} thành hằng mà gọi lại ở từng row. Khi
 * biểu thức của index có thống kê (V19), plan custom ước lượng sát hơn nên "đắt" hơn trên giấy, và
 * sau 5 lần custom Postgres chuyển hẳn sang plan generic — trên 100.000 sản phẩm, plan generic của
 * câu RELEVANCE chậm 1,4–1,8 lần plan custom; qua app, bản này nhanh hơn 1,2–1,6 lần với từ khoá
 * rộng ({@code F08__search-performance/06-verify-and-pr.md} §10). Lập plan lại mỗi lần tìm tốn dưới
 * 1 ms — với từ khoá hẹp (câu ~8 ms) là phần thấy được.
 */
@Repository
public class SearchQueryRepository {

  private static final String THRESHOLD_SETTING = "pg_trgm.word_similarity_threshold";

  private static final String PLAN_CACHE_SETTING = "plan_cache_mode";

  /** Có từ khoá: điểm giống + tổng số khớp (window) — tên cột khớp component của record. */
  private static final String SCORED_PAGE =
      """
      SELECT p.id, p.name, p.slug, p.sku, p.price, p.status, p.category_id, p.created_at,
             round((CASE WHEN p.sku = upper(:q) THEN 1
                         ELSE word_similarity(app.search_normalize(:q),
                                              app.search_normalize(p.name))
                    END)::numeric, 2) AS score,
             count(*) OVER () AS total_count
      FROM app.products p
      """;

  private static final String PLAIN_PAGE =
      """
      SELECT p.id, p.name, p.slug, p.sku, p.price, p.status, p.category_id, p.created_at,
             NULL::numeric AS score
      FROM app.products p
      """;

  private static final String COUNT =
      """
      SELECT count(*)
      FROM app.products p
      """;

  /**
   * {@code name %> q} cùng nghĩa với {@code q <% name}, nhưng là đúng dạng index dùng. Viết {@code
   * q <% name} thì planner đảo nó thành {@code %>} để dùng index, và khi điều kiện nằm trong OR với
   * SKU, không còn nhận ra bản đảo với bản gốc là một: mỗi row tính {@code word_similarity} hai lần
   * (Recheck Cond, rồi Filter) — câu chậm gần gấp đôi.
   */
  private static final String MATCH =
      "(app.search_normalize(p.name) %> app.search_normalize(:q) OR p.sku = upper(:q))";

  private final JdbcClient jdbc;

  public SearchQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Một trang kết quả và tổng số sản phẩm khớp. Thứ tự theo {@code criteria.sort()}; {@code sort}
   * của {@code pageable} bị bỏ qua.
   *
   * @param mode ngưỡng so tên — chỉ có tác dụng khi có từ khoá
   * @throws IllegalStateException có từ khoá mà không có transaction ({@link
   *     SqlStatement#applySettings})
   */
  public Page<ProductSearchResponse> searchProducts(
      ProductSearchCriteria criteria, MatchMode mode, Pageable pageable) {
    SqlStatement page = pageStatement(criteria, mode, pageable);
    page.applySettings(jdbc);
    RecordRowMapper<ProductSearchResponse> mapper = RecordRowMapper.of(ProductSearchResponse.class);

    if (criteria.q() == null) {
      List<ProductSearchResponse> content =
          jdbc.sql(page.sql()).params(page.params()).query(mapper).list();
      return PageableExecutionUtils.getPage(content, pageable, () -> count(criteria));
    }

    long[] total = {0};
    List<ProductSearchResponse> content =
        jdbc.sql(page.sql())
            .params(page.params())
            .query(
                (rs, rowNum) -> {
                  if (rowNum == 0) {
                    total[0] = rs.getLong("total_count");
                  }
                  return mapper.mapRow(rs, rowNum);
                })
            .list();
    // Trang rỗng nằm sau trang cuối: không có row nào mang tổng → đếm bằng câu riêng
    return PageableExecutionUtils.getPage(
        content, pageable, () -> content.isEmpty() ? count(criteria) : total[0]);
  }

  /**
   * Câu của một trang kết quả — đúng câu {@link #searchProducts} gửi đi, kèm ngưỡng pg_trgm và
   * {@code plan_cache_mode} khi có từ khoá. Admin EXPLAIN câu này (bài 03).
   */
  public SqlStatement pageStatement(
      ProductSearchCriteria criteria, MatchMode mode, Pageable pageable) {
    Filter filter = Filter.of(criteria);
    boolean hasQuery = criteria.q() != null;
    String sql =
        (hasQuery ? SCORED_PAGE : PLAIN_PAGE)
            + filter.where()
            + "ORDER BY "
            + orderBy(criteria.sort(), hasQuery)
            + "\nLIMIT :limit OFFSET :offset";

    Map<String, Object> params = new HashMap<>(filter.params());
    params.put("limit", pageable.getPageSize());
    params.put("offset", pageable.getOffset());
    Map<String, String> settings =
        hasQuery
            ? Map.of(
                THRESHOLD_SETTING,
                Double.toString(mode.threshold()),
                PLAN_CACHE_SETTING,
                "force_custom_plan")
            : Map.of();
    return new SqlStatement(sql, params, settings);
  }

  private long count(ProductSearchCriteria criteria) {
    Filter filter = Filter.of(criteria);
    return jdbc.sql(COUNT + filter.where()).params(filter.params()).query(Long.class).single();
  }

  /** Hòa nhau thì mới hơn trước, cuối cùng theo id — thứ tự xác định, phân trang ổn định. */
  private static String orderBy(SearchSort sort, boolean hasQuery) {
    return switch (sort) {
      case RELEVANCE ->
          hasQuery ? "score DESC, p.created_at DESC, p.id" : orderBy(SearchSort.NEWEST, false);
      case NEWEST -> "p.created_at DESC, p.id";
      case PRICE_ASC -> "p.price, p.created_at DESC, p.id";
      case PRICE_DESC -> "p.price DESC, p.created_at DESC, p.id";
    };
  }

  /** {@code WHERE} của những điều kiện có giá trị, và tham số của chúng. */
  private record Filter(String where, Map<String, Object> params) {

    static Filter of(ProductSearchCriteria criteria) {
      List<String> conditions = new ArrayList<>();
      Map<String, Object> params = new HashMap<>();
      if (criteria.q() != null) {
        conditions.add(MATCH);
        params.put("q", criteria.q());
      }
      if (criteria.categoryId() != null) {
        conditions.add("p.category_id = :categoryId");
        params.put("categoryId", criteria.categoryId());
      }
      if (criteria.priceMin() != null) {
        conditions.add("p.price >= :priceMin");
        params.put("priceMin", criteria.priceMin());
      }
      if (criteria.priceMax() != null) {
        conditions.add("p.price <= :priceMax");
        params.put("priceMax", criteria.priceMax());
      }
      if (criteria.status() != null) {
        conditions.add("p.status = :status");
        params.put("status", criteria.status().name());
      }
      String where =
          conditions.isEmpty() ? "" : "WHERE " + String.join("\n  AND ", conditions) + "\n";
      return new Filter(where, params);
    }
  }
}
