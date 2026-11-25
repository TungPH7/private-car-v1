-- Seed 10k customer để đo EXPLAIN cho F02 (xem impl-details/F02__customer/06-verify-and-pr.md §3).
-- Chạy:  docker exec -i learn-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < scripts/seed-customers.sql
-- Dọn:   DELETE FROM app.customers WHERE email LIKE 'perf%@x.com';
--
-- Ghi chú:
--   - phone phải khớp CHECK ck_customers_phone_e164 (^\+[1-9][0-9]{6,14}$) → '+84' + 9 chữ số.
--   - created_at rải ngược quá khứ để ORDER BY created_at DESC có việc thật mà làm.
--   - status trộn 3 giá trị: i%100=0 → DELETED, i%10=0 → SUSPENDED, còn lại ACTIVE.
--     Thứ tự nhánh CASE quan trọng: DELETED phải xét trước, không thì mọi bội số của 100
--     (cũng là bội số của 10) rơi hết vào SUSPENDED.
INSERT INTO app.customers (id, email, phone, first_name, last_name, status, created_at)
SELECT
    gen_random_uuid(),
    'perf' || i || '@x.com',
    '+84' || LPAD((900000000 + i)::text, 9, '0'),
    'First_' || i,
    'Last_' || (i % 100),
    CASE WHEN i % 100 = 0 THEN 'DELETED'
         WHEN i % 10 = 0 THEN 'SUSPENDED'
         ELSE 'ACTIVE' END,
    now() - (i || ' minutes')::interval
FROM generate_series(1, 10000) AS i;

ANALYZE app.customers;
