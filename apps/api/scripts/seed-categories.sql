-- Tạo 100 root category, mỗi root có 10 child, mỗi child có 10 grandchild
-- → 100 + 1000 + 10000 = 11100 categories
DO $$
DECLARE
    i INT; j INT; k INT;
    root_id UUID;
    child_id UUID;
BEGIN
    FOR i IN 1..100 LOOP
        INSERT INTO app.categories (id, name, slug)
        VALUES (gen_random_uuid(), 'Root_' || i, 'root-' || i)
        RETURNING id INTO root_id;

        FOR j IN 1..10 LOOP
            INSERT INTO app.categories (id, parent_id, name, slug)
            VALUES (gen_random_uuid(), root_id, 'C_' || i || '_' || j, 'c-' || i || '-' || j)
            RETURNING id INTO child_id;

            FOR k IN 1..10 LOOP
                INSERT INTO app.categories (id, parent_id, name, slug)
                VALUES (gen_random_uuid(), child_id, 'G_' || i || '_' || j || '_' || k, 'g-' || i || '-' || j || '-' || k);
            END LOOP;
        END LOOP;
    END LOOP;
END $$;

-- Verify total
SELECT COUNT(*) FROM app.categories;
-- Expected: 11100 (cộng với data có sẵn nếu có)
