-- Generic export declaration reference — one free-form column that any
-- origin country's export regime can populate: US AES ITN, CA B13A ref,
-- GB CDS declaration, EU MRN, AU EDN, JP declaration id, IN SB number.
--
-- Complements the US-specific ftr_exemption / aes_citation columns added
-- in V37. The FedEx audit found that we auto-blocked $2,500+ US exports
-- via §30.37(a); this column lets non-US origins carry an equivalent
-- statement without adding a per-country column each.
--
-- ShipmentValidationService emits a WARNING (not error) when a high-value
-- international shipment leaves none of {ftr_exemption, aes_citation,
-- export_declaration_reference} populated. Warning-level intentionally —
-- some corridors (intra-EU, US→CA under §30.36) legitimately have no
-- reference, so we advise but don't block. Per-corridor hard rules land
-- in a follow-up PR.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF NOT
-- EXISTS handles re-runs. See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.order_customs') IS NULL THEN
        RAISE NOTICE 'V38 skipped — order_customs missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE order_customs
        ADD COLUMN IF NOT EXISTS export_declaration_reference VARCHAR(96);

    RAISE NOTICE 'V38 added order_customs.export_declaration_reference';
END $$;
