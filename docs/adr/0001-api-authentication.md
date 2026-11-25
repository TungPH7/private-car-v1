# ADR-0001: Đăng nhập của api bằng JWT HS256 tự phát, refresh token dùng một lần

> **Epic:** none · **Jira:** [PCAR-4](https://phunghuutung12b4.atlassian.net/browse/PCAR-4)

## Status

Accepted

## Date

2026-11-26

## Context

`web` và `admin` đến từ template, viết cho một backend NestJS có đăng nhập JWT: `web` giải mã access token để đọc `userId`, `roleName`, `exp`, và cả hai ẩn hay hiện chức năng theo quyền `{ module, method, path }` của hồ sơ. `api` Java chưa có đăng nhập: mọi `/api/**` mở, chỉ có basic auth của Caddy trên VPS chắn phía trước. Muốn hai app gọi `api` thật, và mở Production mà không có basic auth, `api` cần tài khoản, vai trò, quyền và token.

Thêm hai ràng buộc:

- Quy ước API-012 của `apps/api`: endpoint cần đăng nhập nhận `Authorization: Bearer`, kiểm bằng `oauth2ResourceServer` của Spring Security.
- Trên Development và Staging, Caddy đòi basic auth trên mọi host, nên trình duyệt đã dùng header `Authorization` cho `Basic ...`. Trình duyệt không gửi được hai giá trị trong một header: `admin` (SPA) và Swagger UI không gửi được `Bearer` ở đó.

## Decision

`api` tự phát token: `POST /api/auth/tokens` (grant `password` hoặc `refresh_token`) trả access token JWT HS256 (15 phút, claim `sub`, `userId`, `roleName`, `scope`) và refresh token ngẫu nhiên 256 bit (7 ngày, dùng một lần, DB chỉ lưu SHA-256). Token đọc từ `Authorization: Bearer`, hoặc từ `X-Access-Token` khi `Authorization` không chở Bearer. Quyền là authority trong `scope`; `SecurityConfig` kiểm theo method và path.

Chi tiết:

- **Thư viện:** `spring-security-oauth2-resource-server` và `spring-security-oauth2-jose` 7.0.9 (Nimbus), không dùng starter của Spring Boot. Cả ba bị dep-check báo vì mới 5 ngày; security đề xuất duyệt hai thư viện, ở đúng bản Spring Security mà build đã dùng (PCAR-4, comment 10739).
- **Khóa ký:** `APP_AUTH_JWT_SECRET`, ít nhất 32 byte. Thiếu thì profile `prod` không khởi động; các profile khác sinh khóa ngẫu nhiên cho mỗi lần chạy, nên access token cũ hết dùng được sau khi khởi động lại, còn refresh token vẫn dùng được.
- **Tài khoản đầu tiên:** `APP_AUTH_BOOTSTRAP_ADMIN_EMAIL` và `APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD` (ít nhất 12 ký tự) tạo một tài khoản `ADMIN` lúc khởi động, chỉ khi email đó chưa có.
- **Vai trò, quyền:** bảng tham chiếu của V20, khóa tự nhiên (`ADMIN`, `CUSTOMER`; `product:create`, `product:update`, `product:delete`), mỗi quyền mang method và path của endpoint mà nó mở, để client so khớp.
- **Chống dò:** sai email và sai mật khẩu ra cùng một 401 `AUTH_INVALID_CREDENTIALS`, và cùng một lần băm BCrypt.
- **Refresh token bị dùng lại:** mọi token còn sống của tài khoản bị thu hồi (phát hiện token bị lộ, OAuth 2.0 Security BCP). Đổi mật khẩu cũng thu hồi hết.

## Alternatives Considered

### Một máy chủ định danh riêng (Keycloak, Spring Authorization Server)

- Pros: chuẩn OAuth 2.0 và OIDC đầy đủ, khóa bất đối xứng, trang đăng nhập có sẵn.
- Cons: thêm một dịch vụ để chạy, sao lưu và cập nhật trên VPS 4 GB; `web` và `admin` phải đổi sang luồng redirect.
- Rejected: quá nặng so với nhu cầu hiện tại; có thể đổi sau mà client chỉ đổi nơi lấy token.

### Access token không phải JWT (chuỗi ngẫu nhiên tra DB mỗi request)

- Pros: thu hồi ngay; không cần thư viện JWT.
- Cons: `web` đọc claim của token để biết ai đăng nhập mà không gọi API; mỗi request thêm một truy vấn; lệch quy ước API-012.
- Rejected.

### Ký JWT bằng mã tự viết trên `javax.crypto`

- Pros: không thêm thư viện.
- Cons: tự viết phần kiểm token (thuật toán, chữ ký, thời hạn) là chỗ dễ sai nhất về bảo mật.
- Rejected: dùng Nimbus qua Spring Security.

### Chỉ nhận `Authorization: Bearer`

- Pros: đúng một cách gửi, đúng API-012.
- Cons: trên Development và Staging, `admin` và Swagger UI không gọi được endpoint cần đăng nhập, vì basic auth của Caddy chiếm header đó.
- Rejected: thêm `X-Access-Token`; `web` (server gọi server, qua mạng nội bộ không có Caddy) vẫn dùng `Authorization: Bearer`.

## Consequences

- Trước khi Production bỏ basic auth: đặt `APP_AUTH_JWT_SECRET` (và tài khoản quản trị đầu tiên) trong file secrets của môi trường, khai các biến đó trong `deploy.yaml`; đóng `/api/v1/**` bằng quyền (còn mở như trước PCAR-4); thêm giới hạn số lần đăng nhập sai.
- Trên Development và Staging, khi chưa có `APP_AUTH_JWT_SECRET`, mỗi lần deploy làm access token cũ hết hạn: client làm mới bằng refresh token.
- Quyền mới là một dòng trong một migration mới, cùng một quy tắc trong `SecurityConfig`; client hiện chức năng theo method và path của quyền.
- Hai tab cùng làm mới bằng một refresh token cùng lúc: một tab nhận 409 (khóa lạc quan), lần thử lại của nó bị coi là dùng lại token, và phiên phải đăng nhập lại. Client nên làm mới một lần cho mọi request đang chờ.
