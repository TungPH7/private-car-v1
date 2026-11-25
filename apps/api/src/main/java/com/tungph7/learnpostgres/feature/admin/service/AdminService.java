package com.tungph7.learnpostgres.feature.admin.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.tungph7.learnpostgres.feature.admin.dto.BenchmarkResponse;
import com.tungph7.learnpostgres.feature.admin.dto.ExplainResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexResponse;
import com.tungph7.learnpostgres.feature.admin.dto.IndexUsageResponse;
import com.tungph7.learnpostgres.feature.admin.dto.SlowQueryResponse;
import com.tungph7.learnpostgres.feature.admin.dto.TableStatsResponse;
import com.tungph7.learnpostgres.feature.search.domain.MatchMode;
import com.tungph7.learnpostgres.feature.search.dto.ProductSearchCriteria;

/**
 * Index, thống kê và bảo trì của schema {@code app} (F08): đọc catalog / bộ thống kê, ANALYZE,
 * VACUUM, và EXPLAIN / benchmark những câu được phép.
 *
 * <p><b>Câu được phép</b> = các method {@code ...ProductSearch} — câu tìm sản phẩm, câu duy nhất
 * của app do code dựng thành {@code SqlStatement}. Không nhận SQL của client. Thêm một câu = thêm
 * một method, và một {@code SqlStatement} của repository sở hữu câu đó.
 *
 * <p>Đầu vào kiểm ở đây, sai → 400 {@code VALIDATION_FAILED}. Bảng không có trong {@code app} → 404
 * {@code ADM_TABLE_NOT_FOUND}.
 */
public interface AdminService {

  /** {@code limit} lớn nhất của {@link #slowQueries}. */
  int MAX_SLOW_QUERIES = 100;

  /**
   * Độ dài tối đa của tên bảng, tính theo byte UTF-8 — giới hạn tên của Postgres ({@code
   * NAMEDATALEN - 1}). Dài hơn thì không thể là tên một bảng; và Postgres cắt chuỗi về 63 byte khi
   * so với cột kiểu {@code name}, nên "tên 63 byte + đuôi bất kỳ" sẽ khớp bảng tên 63 byte.
   */
  int MAX_TABLE_NAME_BYTES = 63;

  /**
   * ANALYZE / VACUUM chờ khóa của bảng tối đa chừng này giây ({@code lock_timeout}), quá thì bỏ →
   * 409 {@code ADM_TABLE_LOCKED}. Ngắn, vì {@code VACUUM FULL} đứng trong hàng đợi khóa thì mọi câu
   * đến sau trên bảng đó đứng chờ theo.
   */
  int LOCK_TIMEOUT_SECONDS = 2;

  /**
   * EXPLAIN ANALYZE chạy thật câu — quá chừng này giây thì Postgres hủy ({@code statement_timeout})
   * → 503 {@code ADM_QUERY_TIMEOUT}.
   */
  int EXPLAIN_TIMEOUT_SECONDS = 10;

  /** Số lần đo lớn nhất của một benchmark. */
  int MAX_BENCHMARK_ITERATIONS = 1000;

  /** Số lần chạy khởi động trước khi đo — không tính vào kết quả. */
  int BENCHMARK_WARM_UP = 10;

  /**
   * Thời gian tối đa của một benchmark, gồm cả khởi động. Hết giờ thì dừng ở số lần đã đo ({@link
   * BenchmarkResponse#iterations()}), như {@code ab -t}.
   */
  int BENCHMARK_TIME_LIMIT_SECONDS = 30;

  /** Mọi index của schema {@code app}, theo bảng rồi tên index. */
  List<IndexResponse> indexes();

  /** Mức dùng của mọi index — chưa được quét trước, rồi index lớn trước. */
  List<IndexUsageResponse> indexUsage();

  /**
   * {@code limit} câu tốn nhiều thời gian thực thi nhất (tổng) của app — 1 – {@link
   * #MAX_SLOW_QUERIES}.
   */
  List<SlowQueryResponse> slowQueries(int limit);

  /** Kích thước và thống kê của bảng / materialized view {@code tableName} trong {@code app}. */
  TableStatsResponse tableStats(String tableName);

  /**
   * {@code ANALYZE} bảng, trong transaction riêng (commit khi xong), rồi trả thống kê của bảng sau
   * khi chạy. Bảng bị khóa quá {@link #LOCK_TIMEOUT_SECONDS} giây → 409 {@code ADM_TABLE_LOCKED}.
   */
  TableStatsResponse analyze(String tableName);

  /**
   * {@code VACUUM} bảng — hoặc {@code VACUUM (FULL, ANALYZE)} khi {@code full} — ngoài mọi
   * transaction, rồi trả thống kê của bảng sau khi chạy. {@code full} viết lại cả bảng, trả chỗ
   * trống cho hệ điều hành, và khóa {@code ACCESS EXCLUSIVE} suốt lúc chạy: mọi câu đọc / ghi bảng
   * đó đứng chờ. Bảng bị khóa quá {@link #LOCK_TIMEOUT_SECONDS} giây → 409 {@code
   * ADM_TABLE_LOCKED}.
   */
  TableStatsResponse vacuum(String tableName, boolean full);

  /**
   * {@code EXPLAIN (ANALYZE, BUFFERS)} đúng câu trang mà tìm sản phẩm gửi đi ở mức {@code
   * matchMode}, trong transaction {@code READ ONLY}. Đầu vào kiểm như tìm thật. Chạy quá {@link
   * #EXPLAIN_TIMEOUT_SECONDS} giây → 503 {@code ADM_QUERY_TIMEOUT}.
   *
   * <p>Tìm thật chỉ chạy câu {@link MatchMode#RELAXED} khi câu {@link MatchMode#STRICT} không ra
   * gì; EXPLAIN chạy đúng mức được hỏi. Không có từ khoá thì {@code matchMode} không có tác dụng.
   */
  ExplainResponse explainProductSearch(
      ProductSearchCriteria criteria, MatchMode matchMode, Pageable pageable);

  /**
   * Gọi tìm sản phẩm {@code iterations} lần — trọn một lần tìm như một request (transaction riêng,
   * ngưỡng chuẩn rồi nới nếu cần), trừ HTTP — sau {@link #BENCHMARK_WARM_UP} lần khởi động; trả p50
   * / p95 / p99. Dừng sớm khi chạm {@link #BENCHMARK_TIME_LIMIT_SECONDS} giây.
   *
   * @param iterations 1 – {@link #MAX_BENCHMARK_ITERATIONS}
   */
  BenchmarkResponse benchmarkProductSearch(
      ProductSearchCriteria criteria, Pageable pageable, int iterations);
}
