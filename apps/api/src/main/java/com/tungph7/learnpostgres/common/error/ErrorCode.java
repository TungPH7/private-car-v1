package com.tungph7.learnpostgres.common.error;

import org.springframework.http.HttpStatus;

/**
 * Catalog mã lỗi của hệ thống — single source of truth.
 *
 * <p>Mỗi entry có: - `code` (machine-readable, đi vào response body field "code"). -
 * `defaultStatus` (HTTP status) — exception throw có thể override nhưng mặc định khớp. -
 * `defaultTitle` (human-readable, đi vào "title").
 *
 * <p>Convention naming: <DOMAIN>_<TÌNH HUỐNG>: - PRODUCT_NOT_FOUND (4xx) - PRODUCT_SLUG_DUPLICATED
 * (4xx) - VALIDATION_FAILED (4xx — generic cho 400) - INTERNAL_ERROR (5xx — fallback)
 *
 * <p>Khi thêm mới entry: tự hỏi "có ErrorCode nào hiện tại đủ không?" Tránh tạo entry quá
 * fine-grained — frontend dựa vào enum này để switch/case, càng nhiều entry càng khó maintain.
 */
public enum ErrorCode {

  // ==== 400 BAD REQUEST ============================================
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request body"),
  INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid request parameter"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),

  // ==== 404 NOT FOUND ==============================================
  PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

  // ==== 405 METHOD NOT ALLOWED =====================================
  METHOD_NOT_SUPPORTED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported"),

  // ==== 406 NOT ACCEPTABLE =========================================
  // Header Accept không nhận kiểu nào endpoint trả được (vd text/csv cho endpoint JSON).
  NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Requested media type is not available"),

  // ==== 409 CONFLICT ===============================================
  PRODUCT_SLUG_DUPLICATED(HttpStatus.CONFLICT, "Product slug already exists"),
  OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "Resource was modified by another transaction"),
  // Anh em pessimistic của OPTIMISTIC_LOCK_CONFLICT: deadlock (40P01) hoặc serialization failure
  // (40001). Khác DATA_INTEGRITY_VIOLATION ở chỗ RETRY ĐƯỢC — request không sai, chỉ va chạm.
  CONCURRENT_UPDATE_CONFLICT(HttpStatus.CONFLICT, "Concurrent update conflict, retry the request"),
  DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Data integrity violation"),

  // ==== 415 UNSUPPORTED MEDIA TYPE =================================
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),

  // ==== 500 INTERNAL SERVER ERROR ==================================
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error"),

  // ==== F01 Catalog ================================================
  CAT_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),
  CAT_PARENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Parent category not found"),
  CAT_SLUG_TAKEN(HttpStatus.CONFLICT, "Category slug already exists"),
  CAT_SELF_PARENT(HttpStatus.BAD_REQUEST, "Category cannot be its own parent"),
  CAT_CYCLE_DETECTED(HttpStatus.CONFLICT, "Moving creates a cycle in category tree"),
  CAT_HAS_ACTIVE_CHILDREN(HttpStatus.CONFLICT, "Cannot archive: category has active children"),
  CAT_HAS_REFERENCES(HttpStatus.CONFLICT, "Cannot delete: category has products or children"),
  CAT_INVALID_SORT_ORDER(HttpStatus.BAD_REQUEST, "Invalid sort_order (must be ≥ 0)"),

  // ==== F02 Customer & Address =====================================
  CUS_NOT_FOUND(HttpStatus.NOT_FOUND, "Customer not found"),
  CUS_EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),
  CUS_PHONE_TAKEN(HttpStatus.CONFLICT, "Phone already registered"),
  CUS_INVALID_STATUS(HttpStatus.BAD_REQUEST, "Invalid customer status transition"),

  ADR_NOT_FOUND(HttpStatus.NOT_FOUND, "Address not found"),
  ADR_FORBIDDEN(HttpStatus.FORBIDDEN, "Address does not belong to this customer"),

  // ==== F03 Inventory ==============================================
  INV_NOT_FOUND(HttpStatus.NOT_FOUND, "Inventory record not found"),
  INV_INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
  INV_NEGATIVE_QUANTITY(HttpStatus.BAD_REQUEST, "Quantity must be positive"),
  INV_RELEASE_INVALID(HttpStatus.CONFLICT, "Release amount exceeds reserved"),

  // ==== F04 Coupon =================================================
  // CPN_NOT_FOUND vs CPN_EXPIRED là hai tình huống khác nhau và client cần phân biệt:
  // "gõ sai mã" (nhập lại) khác "mã có thật nhưng chưa tới/đã qua hạn" (chờ hoặc bỏ).
  CPN_NOT_FOUND(HttpStatus.NOT_FOUND, "Coupon not found"),
  CPN_EXPIRED(HttpStatus.BAD_REQUEST, "Coupon expired or not yet valid"),

  // Tên theo đúng rule mà EXCLUDE constraint enforce: code KHÔNG unique, chỉ khoảng
  // thời gian là không được chồng. "CODE_TAKEN" sẽ mô tả sai một rule khác.
  CPN_PERIOD_OVERLAPS(HttpStatus.CONFLICT, "Coupon period overlaps an active campaign"),

  CPN_EXHAUSTED(HttpStatus.CONFLICT, "Coupon usage limit reached"),
  CPN_PER_CUSTOMER_LIMIT(HttpStatus.CONFLICT, "Per-customer usage limit reached"),
  CPN_MIN_ORDER_NOT_MET(HttpStatus.BAD_REQUEST, "Order amount below coupon minimum"),
  CPN_INVALID_VALUE(HttpStatus.BAD_REQUEST, "Coupon value not valid for type"),

  // ==== F05 Order ==================================================
  ORD_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),

  // Hai mã 409 của state machine, client xử lý khác nhau:
  // - INVALID_STATUS_TRANSITION: bước này sai với trạng thái hiện tại (vd hủy đơn đã giao) —
  //   gửi lại y nguyên vẫn sai.
  // - RACE_CONDITION: trạng thái vừa bị giao dịch khác đổi giữa lúc đọc và lúc ghi
  //   (UPDATE ... WHERE status = :from ra 0 row) — tải lại đơn rồi mới quyết định tiếp.
  ORD_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "Invalid status transition"),
  ORD_RACE_CONDITION(HttpStatus.CONFLICT, "Order was modified by another transaction"),

  ORD_CUSTOMER_INACTIVE(HttpStatus.FORBIDDEN, "Customer is not allowed to place orders"),
  ORD_ADDRESS_FORBIDDEN(HttpStatus.FORBIDDEN, "Shipping address does not belong to customer"),
  ORD_ADDRESS_INVALID(HttpStatus.BAD_REQUEST, "Shipping address invalid or deleted"),
  ORD_PRODUCT_INACTIVE(HttpStatus.BAD_REQUEST, "Product is not active"),
  ORD_EMPTY_ITEMS(HttpStatus.BAD_REQUEST, "Order must have at least 1 item"),

  // ==== F06 Payment ================================================
  PAY_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
  PAY_REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund not found"),

  // Hai mã 409 khi tạo payment, client xử lý khác nhau:
  // - ORDER_NOT_PAYABLE: order không còn chờ thanh toán (đã PAID / hủy / hết hạn, hoặc tổng 0) —
  //   không có gì để trả.
  // - ORDER_ALREADY_HAS_PAYMENT: order đã có một payment chưa FAILED (uq_payments_order_non_failed)
  //   — tiếp tục với payment đó, hoặc fail nó rồi tạo payment mới bằng key mới.
  PAY_ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "Order is not awaiting payment"),
  PAY_ORDER_ALREADY_HAS_PAYMENT(HttpStatus.CONFLICT, "Order already has a payment in progress"),

  // Cùng Idempotency-Key nhưng request khác (order / gateway / số tiền / lý do khác) → 422, theo
  // draft IETF httpapi-idempotency-key-header §2.7. Không phải 409: gửi lại y nguyên vẫn sai.
  PAY_IDEMPOTENCY_KEY_REUSED(
      HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key already used for a different request"),

  PAY_INVALID_STATE(HttpStatus.CONFLICT, "Payment is in invalid state for this operation"),
  PAY_REFERENCE_TAKEN(HttpStatus.CONFLICT, "Gateway reference already used"),
  PAY_REFUND_EXCEEDS_PAYMENT(HttpStatus.CONFLICT, "Refund total exceeds captured amount"),

  // ==== F07 Dashboard ==============================================
  // Materialized view doanh thu theo ngày đang WITH NO DATA — Postgres từ chối đọc (55000). Tạm
  // thời: job làm mới nạp lại ở lần chạy kế tiếp, gửi lại y nguyên sau đó là được. 503, không phải
  // 500 (không phải bug) hay 4xx (request không sai).
  DASH_REVENUE_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "Revenue data is not ready yet"),

  // ==== F08 Admin ==================================================
  // Bảng / materialized view không có trong schema app. Tên tra trong catalog, không nối vào SQL.
  ADM_TABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Table not found"),

  // ANALYZE / VACUUM chờ khóa của bảng quá lock_timeout (55P03) — transaction khác đang giữ bảng.
  // Không chờ tiếp: VACUUM FULL đứng trong hàng đợi khóa là chặn luôn mọi câu đọc / ghi đến sau.
  // 409 như mọi va chạm với transaction khác — thử lại khi bảng rảnh.
  ADM_TABLE_LOCKED(HttpStatus.CONFLICT, "Table is locked by another transaction"),

  // EXPLAIN ANALYZE bị Postgres hủy vì quá statement_timeout của admin (57014) — chạy, hoặc chờ
  // khóa
  // của bảng, quá lâu. Không phải bug (500) hay request sai (4xx): câu được phép, chỉ là quá lâu
  // với dữ liệu / plan / khóa hiện tại.
  ADM_QUERY_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "Query exceeded the time limit"),

  // ==== F09 Auth (PCAR-4) ===========================================
  // Email không có và mật khẩu sai trả CÙNG một mã: phân biệt hai trường hợp là cho người dò
  // biết email nào có tài khoản.
  AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
  // Refresh token không có, hết hạn, đã thu hồi, hoặc của tài khoản đã khóa: client đăng nhập lại.
  AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"),
  // Đổi mật khẩu: mật khẩu hiện tại gõ sai. 400, không phải 401: người gọi đã đăng nhập.
  AUTH_WRONG_PASSWORD(HttpStatus.BAD_REQUEST, "Current password is incorrect");

  private final HttpStatus defaultStatus;
  private final String defaultTitle;

  ErrorCode(HttpStatus defaultStatus, String defaultTitle) {
    this.defaultStatus = defaultStatus;
    this.defaultTitle = defaultTitle;
  }

  public HttpStatus defaultStatus() {
    return defaultStatus;
  }

  public String defaultTitle() {
    return defaultTitle;
  }

  /**
   * Trả `code` dạng String để serialize JSON. (Spring serialize enum bằng `name()` mặc định nhưng
   * đặt method riêng để code đọc dễ hơn ở chỗ build response.)
   */
  public String code() {
    return name();
  }
}
