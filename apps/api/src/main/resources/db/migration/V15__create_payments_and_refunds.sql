-- =====================================================================
-- V15__create_payments_and_refunds.sql
-- Tạo app.payments (thanh toán của order) + app.payment_refunds (hoàn tiền).
--
-- Đặc trưng (KEY của F06):
--   - Idempotency: UNIQUE (idempotency_key) + INSERT ... ON CONFLICT DO NOTHING
--     → client retry cùng key không tạo payment / refund thứ hai.
--   - Mỗi order tối đa 1 payment chưa FAILED (partial UNIQUE) → chặn thu tiền 2 lần.
--   - payment_reference UNIQUE theo gateway → 1 giao dịch gateway chỉ ghi nhận 1 lần.
--   - JSONB gateway_response + GIN jsonb_path_ops cho truy vấn containment (@>).
--   - 11 + 9 CHECK: miền giá trị + timestamp ↔ status + "đã thu tiền ⇒ có mã giao dịch".
--   - Trigger AFTER INSERT/UPDATE trên payment_refunds: chỉ refund payment đã thu tiền,
--     tổng refund đang giữ tiền (PENDING + COMPLETED) ≤ payment.amount. Trigger tự khóa
--     row payment → đúng cả khi nhiều refund chạy chồng nhau, kể cả khi caller quên khóa.
--
-- Tiền điều kiện: V2 (app.gen_uuid_v7, app.set_updated_at), V12 (app.orders).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Bảng payments
-- ---------------------------------------------------------------------
CREATE TABLE app.payments (
    id                UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    order_id          UUID            NOT NULL,

    -- Idempotency + đối soát
    idempotency_key   VARCHAR(80)     NOT NULL,                 -- client gửi qua header Idempotency-Key
    payment_reference VARCHAR(80)     NULL,                     -- mã giao dịch do gateway cấp

    -- Tiền (NUMERIC như orders.total — không float, không MONEY)
    amount            NUMERIC(12,2)   NOT NULL,
    currency          CHAR(3)         NOT NULL DEFAULT 'VND',

    -- Gateway
    gateway           VARCHAR(32)     NOT NULL,
    gateway_response  JSONB           NULL,                     -- payload gốc của gateway

    -- State machine
    status            VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
    failure_reason    TEXT            NULL,
    authorized_at     TIMESTAMPTZ     NULL,
    captured_at       TIMESTAMPTZ     NULL,
    failed_at         TIMESTAMPTZ     NULL,

    -- Audit (BaseEntity)
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)     NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)     NOT NULL DEFAULT 'system',
    version           BIGINT          NOT NULL DEFAULT 0,

    -- ===== FK + UNIQUE =====
    CONSTRAINT payments_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES app.orders(id) ON DELETE RESTRICT,

    -- Chìa khoá idempotency.
    CONSTRAINT payments_idempotency_key_unique
        UNIQUE (idempotency_key),

    -- Mã giao dịch chỉ unique TRONG một gateway (VNPAY, MOMO đều cấp mã dạng số, có thể
    -- trùng nhau) → unique theo cặp. payment_reference đứng đầu: lookup chỉ theo
    -- reference vẫn seek được index. NULL không đụng nhau (payment chưa có mã).
    CONSTRAINT uq_payments_reference_gateway
        UNIQUE (payment_reference, gateway),

    -- ===================== CHECK constraints (11) =====================
    -- Miền giá trị
    CONSTRAINT ck_payments_status
        CHECK (status IN ('PENDING','AUTHORIZED','CAPTURED','FAILED',
                          'REFUNDED','PARTIALLY_REFUNDED')),
    CONSTRAINT ck_payments_gateway
        CHECK (gateway IN ('STRIPE','VNPAY','MOMO','COD','BANK_TRANSFER')),
    CONSTRAINT ck_payments_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_payments_currency_iso
        CHECK (currency ~ '^[A-Z]{3}$'),
    -- Key rỗng → mọi request gửi key rỗng dồn vào CÙNG một payment.
    CONSTRAINT ck_payments_idempotency_key_non_blank
        CHECK (length(btrim(idempotency_key)) > 0),
    -- NULL vẫn qua (CHECK coi NULL là pass) — chỉ chặn chuỗi rỗng, thứ lách được
    -- ck_payments_captured_has_ref vì '' IS NOT NULL.
    CONSTRAINT ck_payments_reference_non_blank
        CHECK (length(btrim(payment_reference)) > 0),
    -- Payload gateway luôn là JSON object. Chặn payload kiểu khác — vd chuỗi JSON bị
    -- serialize thêm một lần ('"{...}"'): vẫn là jsonb hợp lệ nhưng @> không bao giờ khớp.
    CONSTRAINT ck_payments_gateway_response_object
        CHECK (jsonb_typeof(gateway_response) = 'object'),

    -- Status ↔ dữ liệu đi kèm. Ba dạng ràng buộc, chọn theo đúng nghĩa từng cột:
    --   ⇒  (A thì phải B): đã thu tiền thì phải có mã giao dịch. Chiều ngược lại KHÔNG
    --      ép: gateway có thể cấp mã ngay lúc tạo (Stripe) hoặc cho cả giao dịch thất
    --      bại (VNPAY) — vẫn nên lưu để đối soát.
    CONSTRAINT ck_payments_captured_has_ref
        CHECK (status NOT IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED')
               OR payment_reference IS NOT NULL),
    --   ⇔  (so sánh = giữa 2 điều kiện): có mốc ⇔ đang ở nhóm status đó. Đã thu tiền thì
    --      chỉ đi tiếp sang 2 trạng thái refund nên captured_at giữ mãi; FAILED là terminal.
    CONSTRAINT ck_payments_captured_at_with_status
        CHECK ((status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED'))
               = (captured_at IS NOT NULL)),
    CONSTRAINT ck_payments_failed_at_with_status
        CHECK ((status = 'FAILED') = (failed_at IS NOT NULL)),
    --   CASE (luật riêng từng status): PENDING chưa authorize; AUTHORIZED phải có mốc;
    --      các status sau tuỳ đường đi (PENDING → CAPTURED thẳng thì không có mốc này).
    CONSTRAINT ck_payments_authorized_at_with_status
        CHECK (CASE status
                   WHEN 'PENDING'    THEN authorized_at IS NULL
                   WHEN 'AUTHORIZED' THEN authorized_at IS NOT NULL
                   ELSE TRUE
               END)
);

-- ---------------------------------------------------------------------
-- 2. Index cho payments
-- ---------------------------------------------------------------------
-- 2 UNIQUE ở trên đã tự tạo btree. FK order_id cần index riêng (Postgres không tự tạo).

-- List payment của order (kể cả FAILED) + FK check khi xóa order.
CREATE INDEX idx_payments_order_id
    ON app.payments (order_id);

-- Mỗi order tối đa 1 payment chưa FAILED: payment trước còn PENDING / AUTHORIZED hoặc
-- đã thu tiền thì không tạo được payment thứ hai → không thu tiền 2 lần cho 1 order.
-- FAILED nằm ngoài predicate → khách trả lại được sau một lần thất bại.
-- LƯU Ý: ON CONFLICT (idempotency_key) chỉ nuốt xung đột của payments_idempotency_key_unique.
-- Vi phạm index này vẫn là lỗi 23505 — kể cả khi 2 request giống hệt nhau chạy chồng
-- nhau. INSERT idempotent nên dùng ON CONFLICT DO NOTHING không chỉ định cột.
CREATE UNIQUE INDEX uq_payments_order_non_failed
    ON app.payments (order_id)
    WHERE status <> 'FAILED';

-- Job dò payment treo: WHERE status IN ('PENDING','AUTHORIZED') AND created_at < :cutoff
-- ORDER BY created_at LIMIT n. Key là created_at → range scan đã đúng thứ tự, dừng sau n
-- row, không phải Sort. Predicate chỉ giữ payment còn chờ capture — nhỏ so với toàn bảng.
CREATE INDEX idx_payments_capturable_created_at
    ON app.payments (created_at)
    WHERE status IN ('PENDING','AUTHORIZED');

-- Containment @> trên payload gateway (debug, đối soát). jsonb_path_ops chỉ phục vụ @>,
-- @?, @@ (không có ?, ?|, ?&) nhưng nhỏ hơn jsonb_ops: mỗi entry là hash của cả đường
-- dẫn + giá trị, không index riêng từng key. Tên index giữ cố định — F08 kiểm theo tên.
CREATE INDEX idx_payments_gateway_response_gin
    ON app.payments USING gin (gateway_response jsonb_path_ops);

CREATE TRIGGER trg_payments_set_updated_at
    BEFORE UPDATE ON app.payments
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 3. Bảng payment_refunds
-- ---------------------------------------------------------------------
CREATE TABLE app.payment_refunds (
    id                UUID            PRIMARY KEY DEFAULT app.gen_uuid_v7(),

    payment_id        UUID            NOT NULL,

    -- Idempotency + đối soát
    idempotency_key   VARCHAR(80)     NOT NULL,
    refund_reference  VARCHAR(80)     NULL,                     -- mã refund do gateway cấp

    amount            NUMERIC(12,2)   NOT NULL,
    reason            VARCHAR(255)    NOT NULL,
    gateway_response  JSONB           NULL,
    status            VARCHAR(16)     NOT NULL DEFAULT 'PENDING',

    requested_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ     NULL,
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),   -- status đổi được → cần mốc sửa cuối
    actor             VARCHAR(64)     NOT NULL,

    -- ===== FK + UNIQUE =====
    CONSTRAINT payment_refunds_payment_id_fkey
        FOREIGN KEY (payment_id) REFERENCES app.payments(id) ON DELETE RESTRICT,
    CONSTRAINT payment_refunds_idempotency_key_unique
        UNIQUE (idempotency_key),
    -- Bảng này không có cột gateway → không unique theo cặp như payments được. Chọn unique
    -- toàn cục (chặt hơn): mã trùng giữa 2 gateway làm confirm fail ồn ào (23505), thay
    -- vì một refund bị ghi nhận 2 lần âm thầm.
    CONSTRAINT payment_refunds_reference_unique
        UNIQUE (refund_reference),

    -- ===================== CHECK constraints (9) =====================
    CONSTRAINT ck_refunds_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ck_refunds_status
        CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    CONSTRAINT ck_refunds_idempotency_key_non_blank
        CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_refunds_reference_non_blank
        CHECK (length(btrim(refund_reference)) > 0),
    CONSTRAINT ck_refunds_reason_non_blank
        CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_refunds_actor_non_blank
        CHECK (length(btrim(actor)) > 0),
    CONSTRAINT ck_refunds_gateway_response_object
        CHECK (jsonb_typeof(gateway_response) = 'object'),
    CONSTRAINT ck_refunds_completed_has_ref
        CHECK (status <> 'COMPLETED' OR refund_reference IS NOT NULL),
    CONSTRAINT ck_refunds_completed_at_with_status
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- List refund của payment + SUM trong trigger + FK check khi xóa payment.
CREATE INDEX idx_refunds_payment_id
    ON app.payment_refunds (payment_id);

CREATE TRIGGER trg_payment_refunds_set_updated_at
    BEFORE UPDATE ON app.payment_refunds
    FOR EACH ROW
    EXECUTE FUNCTION app.set_updated_at();

-- ---------------------------------------------------------------------
-- 4. Trigger: refund phải khớp payment (rule giữa 2 bảng — CHECK không làm được)
-- ---------------------------------------------------------------------
-- Vì sao AFTER, không phải BEFORE:
--   INSERT ... ON CONFLICT DO NOTHING vẫn chạy trigger BEFORE INSERT cho row sắp bị bỏ
--   qua. Retry cùng key của refund 200/300 → trigger BEFORE thấy 200 (bản gốc) + 200
--   (bản retry) > 300 → lỗi, thay vì idempotent trả 0 row. Trigger AFTER chỉ chạy cho
--   row thật sự được ghi.
--
-- Vì sao trigger tự khóa row payment:
--   Không khóa, 2 refund chạy chồng nhau đều SUM trước khi bên kia commit → cả hai lọt
--   (180 + 180 > 300). Khóa row payment xếp hàng các refund của CÙNG payment: bên sau
--   chờ bên trước commit rồi mới SUM.
--
-- Vì sao FOR NO KEY UPDATE, không phải FOR UPDATE:
--   Trigger RI của FK payment_id chạy TRƯỚC trigger này (tên RI_... < trg_...) và giữ
--   FOR KEY SHARE trên row payment. FOR UPDATE xung đột với KEY SHARE: 2 refund cùng qua
--   bước FK rồi chờ nhau → deadlock (40P01). FOR NO KEY UPDATE không xung đột với KEY
--   SHARE nhưng xung đột với chính nó → vẫn xếp hàng, không deadlock.
--
-- Vì sao chặn REPEATABLE READ:
--   Hàm VOLATILE (mặc định): dưới READ COMMITTED, mỗi câu SELECT trong hàm lấy snapshot
--   MỚI → SUM chạy sau khi chờ khóa thấy refund vừa commit. REPEATABLE READ giữ snapshot
--   từ câu đầu tiên của transaction → SUM không thấy → 2 refund vẫn cùng lọt. SERIALIZABLE
--   an toàn (SSI hủy một bên với 40001), nên chỉ REPEATABLE READ bị từ chối.
--   Mã lỗi invalid_transaction_state (25000), KHÔNG dùng feature_not_supported (0A000):
--   HikariCP coi 0A000 là connection hỏng → loại connection khỏi pool, rollback sau đó
--   báo "Connection is closed" và che mất lỗi gốc.
CREATE OR REPLACE FUNCTION app.check_refund_against_payment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_payment_amount NUMERIC(12,2);
    v_payment_status VARCHAR(24);
    v_refund_total   NUMERIC;   -- không giới hạn precision: tổng vượt trần NUMERIC(12,2)
                                -- vẫn phải ra lỗi nghiệp vụ, không phải 22003 overflow
BEGIN
    IF current_setting('transaction_isolation') = 'repeatable read' THEN
        RAISE EXCEPTION 'app.check_refund_against_payment() cannot run under REPEATABLE READ: '
                        'its snapshot misses refunds committed while waiting for the payment lock. '
                        'Use READ COMMITTED or SERIALIZABLE.'
            USING ERRCODE = 'invalid_transaction_state';
    END IF;

    -- FK đã kiểm xong (trigger RI chạy trước) → payment chắc chắn tồn tại.
    SELECT amount, status
      INTO v_payment_amount, v_payment_status
      FROM app.payments
     WHERE id = NEW.payment_id
       FOR NO KEY UPDATE;

    -- Refund mới (hoặc chuyển sang payment khác) chỉ hợp lệ khi payment đã thu tiền.
    IF (TG_OP = 'INSERT' OR NEW.payment_id <> OLD.payment_id)
       AND v_payment_status NOT IN ('CAPTURED', 'PARTIALLY_REFUNDED') THEN
        RAISE EXCEPTION 'PAY_INVALID_STATE: payment % is %, only CAPTURED or PARTIALLY_REFUNDED can be refunded',
            NEW.payment_id, v_payment_status
            USING ERRCODE = 'check_violation';
    END IF;

    -- AFTER → SUM đã gồm chính row này (và mọi row khác cùng câu lệnh).
    -- FAILED không giữ tiền nên không tính.
    SELECT COALESCE(SUM(amount), 0)
      INTO v_refund_total
      FROM app.payment_refunds
     WHERE payment_id = NEW.payment_id
       AND status IN ('PENDING', 'COMPLETED');

    IF v_refund_total > v_payment_amount THEN
        RAISE EXCEPTION 'PAY_REFUND_EXCEEDS_PAYMENT: refunds % > payment amount % (payment %)',
            v_refund_total, v_payment_amount, NEW.payment_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;   -- AFTER trigger: giá trị trả về bị bỏ qua
END;
$$;

-- WHEN: refund FAILED không giữ tiền → khỏi khóa payment, khỏi SUM.
-- UPDATE OF: chỉ chạy khi câu UPDATE đụng tới cột ảnh hưởng tổng tiền.
CREATE TRIGGER trg_payment_refunds_check_payment
    AFTER INSERT OR UPDATE OF amount, status, payment_id ON app.payment_refunds
    FOR EACH ROW
    WHEN (NEW.status <> 'FAILED')
    EXECUTE FUNCTION app.check_refund_against_payment();

-- ---------------------------------------------------------------------
-- 5. Comments
-- ---------------------------------------------------------------------
COMMENT ON TABLE  app.payments                   IS 'Thanh toán của order. Idempotent qua UNIQUE (idempotency_key) + INSERT ... ON CONFLICT DO NOTHING.';
COMMENT ON COLUMN app.payments.idempotency_key   IS 'Key client gửi (header Idempotency-Key). Retry cùng key → không tạo payment mới.';
COMMENT ON COLUMN app.payments.payment_reference IS 'Mã giao dịch do gateway cấp. UNIQUE theo (payment_reference, gateway). Bắt buộc khi đã thu tiền.';
COMMENT ON COLUMN app.payments.amount            IS 'Số tiền thu, lấy từ orders.total lúc tạo payment.';
COMMENT ON COLUMN app.payments.gateway_response  IS 'Payload gốc của gateway (JSON object). GIN jsonb_path_ops cho truy vấn @>.';
COMMENT ON COLUMN app.payments.status            IS 'PENDING | AUTHORIZED | CAPTURED | FAILED | PARTIALLY_REFUNDED | REFUNDED. Transition do app guard.';

COMMENT ON CONSTRAINT ck_payments_captured_has_ref ON app.payments IS
    'Đã thu tiền (CAPTURED / PARTIALLY_REFUNDED / REFUNDED) ⇒ có payment_reference. Một chiều: status khác vẫn được lưu mã.';
COMMENT ON CONSTRAINT ck_payments_captured_at_with_status ON app.payments IS
    'captured_at IS NOT NULL ⇔ status thuộc nhóm đã thu tiền.';
COMMENT ON INDEX app.uq_payments_order_non_failed IS
    'Mỗi order tối đa 1 payment chưa FAILED — chặn thu tiền 2 lần. ON CONFLICT (idempotency_key) không nuốt lỗi của index này.';

COMMENT ON TABLE  app.payment_refunds IS
    'Hoàn tiền (một phần / toàn bộ) của payment. Tổng PENDING + COMPLETED ≤ payment.amount (trigger trg_payment_refunds_check_payment).';
COMMENT ON COLUMN app.payment_refunds.status IS
    'PENDING (đã yêu cầu, đang giữ tiền) | COMPLETED (gateway xác nhận) | FAILED (không giữ tiền).';
COMMENT ON FUNCTION app.check_refund_against_payment() IS
    'Trigger AFTER INSERT/UPDATE trên payment_refunds: payment phải đã thu tiền; tổng refund PENDING + COMPLETED ≤ payment.amount. '
    'Tự khóa row payment FOR NO KEY UPDATE (xếp hàng, không deadlock với FK). Từ chối REPEATABLE READ.';
COMMENT ON TRIGGER trg_payment_refunds_check_payment ON app.payment_refunds IS
    'AFTER (không phải BEFORE) để INSERT ... ON CONFLICT DO NOTHING của retry không bị kiểm như refund mới. Bỏ qua row FAILED.';

-- ---------------------------------------------------------------------
-- 6. Log
-- ---------------------------------------------------------------------
INSERT INTO app._app_migration_log (migration_name, notes)
VALUES (
    'V15__create_payments_and_refunds',
    'Created app.payments (11 CHECK, UNIQUE idempotency_key + (payment_reference, gateway), ' ||
    'partial UNIQUE 1 non-failed payment per order, JSONB + GIN jsonb_path_ops, partial index capturable) ' ||
    'and app.payment_refunds (9 CHECK). AFTER trigger: refund only captured payments, ' ||
    'SUM(PENDING + COMPLETED) <= payment.amount, locks payment FOR NO KEY UPDATE, rejects REPEATABLE READ.'
);
