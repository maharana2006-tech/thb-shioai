-- Orders section showed a D2C/B2B chip only on orders persisted after the
-- classifier shipped (V35/V36); everything older — and anything inserted
-- outside the manual path (WMS pull) — carried NULL and rendered blank.
-- Backfill with the same rule the classifier applies when no explicit
-- channel or residential flag is available: a distinct second party name
-- (company / attention line) means a business consignee → B2B, else D2C.
-- Idempotent: only touches NULLs.
UPDATE label_batch
   SET order_channel = CASE
        WHEN NULLIF(BTRIM(ship_attn), '') IS NOT NULL
         AND NULLIF(BTRIM(ship_name), '') IS NOT NULL
         AND LOWER(BTRIM(ship_attn)) <> LOWER(BTRIM(ship_name)) THEN 'B2B'
        ELSE 'D2C'
       END
 WHERE order_channel IS NULL;
