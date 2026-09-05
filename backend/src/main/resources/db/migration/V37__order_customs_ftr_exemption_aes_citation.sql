-- US Foreign Trade Regulations (FTR) §30.37 exemption or Automated Export
-- System (AES) ITN — one of the two is legally required on a US-origin
-- export ≥ $2,500 USD (per Schedule B code) to a non-Canada destination.
-- Prior to this migration we sent no exportDetail block, and FedEx's Ship
-- API auto-applied "NO EEI 30.37(a)" — invalid at ≥ $2,500 → carrier reject
-- with SHIPMENTVALIDATION.EEIEDIT.ERROR.
--
-- Both columns are nullable and short — the operator picks ONE:
--   ftr_exemption   Wire code: NO_EEI_30_37_a | NO_EEI_30_37_h | NO_EEI_30_36
--                   Rendered on the label as "NO EEI 30.37(a)" etc; on FedEx
--                   as foreignTradeStatisticsRegulations.filingCitation.
--   aes_citation    Free-form ITN filed with US Census, typically starting
--                   with "X" or "AES". FedEx expects it via
--                   customsClearanceDetail.exportDetail.exportComplianceStatement.
--
-- Fresh-DB safe: guarded by to_regclass. Idempotent: ADD COLUMN IF NOT
-- EXISTS handles re-runs. See docs/flyway-fresh-db-guard-pattern.md.
DO $$
BEGIN
    IF to_regclass('public.order_customs') IS NULL THEN
        RAISE NOTICE 'V37 skipped — order_customs missing (fresh DB before first sync)';
        RETURN;
    END IF;

    ALTER TABLE order_customs
        ADD COLUMN IF NOT EXISTS ftr_exemption VARCHAR(32),
        ADD COLUMN IF NOT EXISTS aes_citation VARCHAR(64);

    RAISE NOTICE 'V37 added order_customs.ftr_exemption + aes_citation';
END $$;
