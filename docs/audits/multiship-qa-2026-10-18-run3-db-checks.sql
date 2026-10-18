-- multiship QA run 3 (2026-10-18, retest) — read-only addendum to the run-2 pack
-- PostgreSQL app DB. SELECT only. Table/column names are inferred from the API
-- (see section A0 of the run-2 pack for schema discovery); adjust and paste results back.

-- ============================================================
-- B1. Everything created in run 3 (cleanup list)
-- ============================================================
SELECT order_no, status, source, ref_order_number, created_at
FROM orders
WHERE customer_code = 'MKL246'
  AND (ref_order_number LIKE 'QA-1018c%' OR order_no BETWEEN 934329 AND 934343)
ORDER BY order_no;

-- ============================================================
-- B2. R2-BUG-001 — External API voids without a carrier call (934335, 934336, 934337)
--     vs the internal void of 934339 (FedEx cancel, carrier log 2722)
-- ============================================================
SELECT o.order_no, o.status AS order_status, l.status AS label_status, l.tracking_number, l.voided_at
FROM orders o LEFT JOIN labels l ON l.order_no = o.order_no
WHERE o.order_no IN (934335, 934336, 934337, 934339, 934340)
ORDER BY o.order_no;

SELECT id, order_no, carrier, url, status_code, created_at
FROM carrier_api_log
WHERE id BETWEEN 2717 AND 2740
ORDER BY id;                                   -- 2720/2721 (bulk ship) should carry 934338/934339 but have NULL (R2-BUG-007)

-- ============================================================
-- B3. R2-BUG-004 — per-box declared value (934329 UPS, 934330 FedEx: entered 50/150, carrier got 100/100)
-- ============================================================
SELECT p.order_no, p.sequence_number, p.weight, p.declared_value, o.declared_value AS order_declared_value
FROM order_packages p JOIN orders o ON o.order_no = p.order_no
WHERE p.order_no IN (934329, 934330)
ORDER BY p.order_no, p.sequence_number;

-- ============================================================
-- B4. R2-BUG-005 — duplicates (same reference, two labels)
-- ============================================================
SELECT ref_order_number, COUNT(*) AS n, STRING_AGG(order_no::text, ',' ORDER BY order_no) AS orders
FROM orders
WHERE customer_code = 'MKL246' AND ref_order_number IN ('QA-1018c-D001', 'QA-1018c-EXT1')
GROUP BY ref_order_number;

-- ============================================================
-- B5. R2-BUG-008 — required custom field not enforced (934340 saved with no custom fields)
-- ============================================================
SELECT order_no, custom_fields FROM orders WHERE order_no = 934340;
SELECT * FROM custom_field_definitions WHERE field_key = 'qa_1018c_dept';   -- expect none (deleted)

-- ============================================================
-- B6. R2-BUG-012 — buckets vs list (34,004 vs 34,014)
-- ============================================================
SELECT o.status AS order_status, l.status AS label_status, COUNT(*) AS rows
FROM orders o LEFT JOIN labels l ON l.order_no = o.order_no
GROUP BY o.status, l.status ORDER BY 1, 2;

-- ============================================================
-- B7. Role tests — the invited operator and what it touched
-- ============================================================
SELECT id, username, email, role, client_code, active, deactivated_at, deactivation_reason
FROM users WHERE username IN ('qa1018cop', 'smoke');                       -- qa1018cop expected Deactivated
SELECT * FROM user_invites WHERE email IN ('qa-1018c-op@example.com', 'verify-dup@example.com');
SELECT order_no, status, created_by FROM orders WHERE order_no IN (934343, 934340);  -- 934343 bought by qa1018cop, 934340 voided by qa1018cop

-- ============================================================
-- B8. R3-BUG-001 — what permission the USER role lacks (if roles are data-driven)
-- ============================================================
SELECT * FROM roles;                                                       -- or role_permissions
SELECT * FROM role_permissions WHERE role = 'USER' ORDER BY 1;

-- ============================================================
-- B9. API key issued and revoked during the run
-- ============================================================
SELECT id, name, client_code, environment, scopes, active, revoked_at, last_used_at
FROM api_keys WHERE name IN ('QA-1018b-key', 'QA-1018c-key');             -- both revoked
