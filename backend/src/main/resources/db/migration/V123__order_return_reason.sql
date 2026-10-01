-- Returns F11 — add Order.return_reason (VARCHAR 32) for return
-- analytics. Operators pick one of five canonical codes when creating
-- a return label so management can roll up "what's coming back and
-- why" without parsing free text out of goods_description.
--
-- Values are enforced in the app layer (ReturnReason enum) rather than
-- a CHECK constraint so new reason codes don't need a migration — the
-- enum ships with the next deploy and legacy rows keep whatever text
-- they had. Column is nullable: outbound labels + legacy returns stay
-- NULL and that's the "unknown" bucket on the rollup.

DO $$
BEGIN
    IF to_regclass('public.label_batch') IS NOT NULL THEN
        ALTER TABLE label_batch
            ADD COLUMN IF NOT EXISTS return_reason VARCHAR(32);
    END IF;
END $$;
