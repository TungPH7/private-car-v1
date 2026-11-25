-- =====================================================================
-- V20__create_auth_tables.sql
-- Đăng nhập của API (PCAR-4): tài khoản, vai trò, quyền, refresh token.
--   1) app.roles, app.permissions, app.role_permissions — dữ liệu tham chiếu, khóa tự nhiên
--      (code), seed ở cuối file. Ứng dụng đọc bằng JDBC, không map thành entity.
--   2) app.users — tài khoản đăng nhập, entity kế thừa BaseEntity (UUIDv7 + 5 cột audit).
--      KHÁC app.customers (V6): customer là đối tượng nghiệp vụ của đơn hàng, user là người
--      đăng nhập vào web/admin.
--   3) app.refresh_tokens — chỉ lưu SHA-256 (hex) của refresh token, không lưu token. Token
--      dùng một lần: đổi token thì dòng cũ có revoked_at.
--
-- Tiền điều kiện: V1 (schema app, citext ở public), V2 (app.gen_uuid_v7, app.set_updated_at).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Vai trò và quyền
-- ---------------------------------------------------------------------
CREATE TABLE app.roles (
    code            VARCHAR(32)     PRIMARY KEY,
    description     VARCHAR(255)    NOT NULL,

    CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z_]*$')
);

CREATE TABLE app.permissions (
    code            VARCHAR(64)     PRIMARY KEY,
    module          VARCHAR(32)     NOT NULL,
    http_method     VARCHAR(8)      NOT NULL,
    path            VARCHAR(255)    NOT NULL,
    description     VARCHAR(255)    NOT NULL,

    CONSTRAINT ck_permissions_code_format CHECK (code ~ '^[a-z][a-z-]*:[a-z][a-z-]*$'),
    CONSTRAINT ck_permissions_method      CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT ck_permissions_path_api    CHECK (path LIKE '/api/%')
);

CREATE TABLE app.role_permissions (
    role_code       VARCHAR(32)     NOT NULL REFERENCES app.roles (code) ON DELETE CASCADE,
    permission_code VARCHAR(64)     NOT NULL REFERENCES app.permissions (code) ON DELETE CASCADE,

    PRIMARY KEY (role_code, permission_code)
);

COMMENT ON TABLE  app.roles              IS 'Vai trò của tài khoản. Seed ở V20; code viết hoa (ADMIN, CUSTOMER).';
COMMENT ON TABLE  app.permissions        IS 'Quyền: code module:hành động (product:delete), cùng method và path của endpoint mà quyền mở.';
COMMENT ON COLUMN app.permissions.code   IS 'Tên authority trong claim scope của access token, và trong hasAuthority của SecurityConfig.';
COMMENT ON COLUMN app.permissions.path   IS 'Mẫu path của endpoint, như /api/products/{id}. Client (web, admin) so cùng method và path để hiện nút.';
COMMENT ON TABLE  app.role_permissions   IS 'Quyền của từng vai trò.';

-- ---------------------------------------------------------------------
-- 2. Tài khoản
-- ---------------------------------------------------------------------
CREATE TABLE app.users (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    email           public.CITEXT   NOT NULL,
    password_hash   VARCHAR(100)    NOT NULL,
    name            VARCHAR(120)    NOT NULL,
    phone_number    VARCHAR(32)     NULL,
    avatar_url      VARCHAR(2048)   NULL,
    role_code       VARCHAR(32)     NOT NULL REFERENCES app.roles (code),
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT users_email_unique        UNIQUE (email),
    CONSTRAINT ck_users_email_format     CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_users_name_non_blank   CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_users_password_bcrypt  CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$')
);

CREATE INDEX idx_users_role_code ON app.users (role_code);

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON app.users
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

COMMENT ON TABLE  app.users               IS 'Tài khoản đăng nhập vào web và admin. Email case-insensitive (citext).';
COMMENT ON COLUMN app.users.password_hash IS 'BCrypt ($2a$/$2b$/$2y$). Không bao giờ lưu mật khẩu thô.';
COMMENT ON COLUMN app.users.enabled       IS 'FALSE: không đăng nhập, không làm mới token được nữa.';

-- ---------------------------------------------------------------------
-- 3. Refresh token
-- ---------------------------------------------------------------------
CREATE TABLE app.refresh_tokens (
    id              UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    user_id         UUID            NOT NULL REFERENCES app.users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)     NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    revoked_at      TIMESTAMPTZ     NULL,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)     NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT refresh_tokens_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash_hex       CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

-- Thu hồi mọi token còn sống của một tài khoản (đổi mật khẩu, token bị dùng lại).
CREATE INDEX idx_refresh_tokens_user_active
    ON app.refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

CREATE TRIGGER trg_refresh_tokens_set_updated_at
    BEFORE UPDATE ON app.refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

COMMENT ON TABLE  app.refresh_tokens            IS 'Refresh token: chỉ lưu SHA-256 (hex). Dùng một lần; token đã thu hồi mà bị dùng lại thì mọi token của tài khoản bị thu hồi.';
COMMENT ON COLUMN app.refresh_tokens.token_hash IS 'SHA-256 hex của token. Token thô chỉ có ở client.';

-- ---------------------------------------------------------------------
-- 4. Seed
-- ---------------------------------------------------------------------
INSERT INTO app.roles (code, description) VALUES
    ('ADMIN',    'Quản trị: mọi quyền trên sản phẩm'),
    ('CUSTOMER', 'Khách hàng: không có quyền quản trị');

INSERT INTO app.permissions (code, module, http_method, path, description) VALUES
    ('product:create', 'product', 'POST',   '/api/products',      'Tạo sản phẩm'),
    ('product:update', 'product', 'PUT',    '/api/products/{id}', 'Sửa sản phẩm, đổi giá'),
    ('product:delete', 'product', 'DELETE', '/api/products/{id}', 'Xoá sản phẩm');

INSERT INTO app.role_permissions (role_code, permission_code) VALUES
    ('ADMIN', 'product:create'),
    ('ADMIN', 'product:update'),
    ('ADMIN', 'product:delete');

-- ---------------------------------------------------------------------
-- 5. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V20__create_auth_tables',
    'Created app.roles, app.permissions, app.role_permissions (seeded ADMIN with product:create, ' ||
    'product:update, product:delete; CUSTOMER with none), app.users (citext email UNIQUE, BCrypt ' ||
    'hash) and app.refresh_tokens (SHA-256 hex only, single use).'
);
