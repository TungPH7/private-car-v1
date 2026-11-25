package com.tungph7.learnpostgres.common.security;

import java.security.Principal;

/**
 * "Ai đang thao tác" của một HTTP request — giá trị ghi vào các cột actor của dữ liệu nghiệp vụ
 * ({@code created_by}, {@code payment_refunds.actor}, ...).
 *
 * <p>Lấy từ {@link Principal} của request, KHÔNG nhận từ body hay header: client tự khai mình là ai
 * thì các cột đó mất giá trị kiểm toán. Spring MVC truyền {@code Principal = null} cho request chưa
 * xác thực; repo chưa có đăng nhập ({@code SecurityConfig} permitAll {@code /api/**}) nên hiện MỌI
 * request đều ra {@link #ANONYMOUS}. Khi thêm JWT, giá trị tự thành subject của token — không phải
 * sửa nơi gọi.
 *
 * <p>Cùng quy tắc với {@code OrderController.actorOf} của F05 — bản đầu, viết riêng trong
 * controller đó trước khi có controller thứ hai cần tới.
 */
public final class RequestActor {

  /** Actor khi request chưa xác thực. */
  public static final String ANONYMOUS = "anonymous";

  private RequestActor() {}

  /** Tên của người gọi đã xác thực; chưa xác thực thì {@link #ANONYMOUS}. */
  public static String of(Principal principal) {
    return principal == null ? ANONYMOUS : principal.getName();
  }
}
