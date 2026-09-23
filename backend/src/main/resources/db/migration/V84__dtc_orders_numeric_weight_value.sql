-- V84 — dtc_orders.weight / unit_value become NUMERIC
-- V75 created them as VARCHAR(50); the DtcOrder entity now maps them as BigDecimal
-- (like freight_cost from V82). Hibernate cannot ALTER a text column to numeric on
-- its own ("cannot be cast automatically"), and a prod boot (ddl-auto=validate)
-- would refuse the mismatch — so the cast is done here, blank text becoming NULL.
--
-- Fresh-DB safe: guarded by information_schema. On a fresh Postgres, Hibernate
-- (ddl-auto=update) creates dtc_orders from the entity mapping BEFORE Flyway
-- runs V2+, so weight/unit_value are already NUMERIC and this is a no-op —
-- otherwise TRIM(weight) fails with "function btrim(numeric) does not exist".

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'dtc_orders'
           AND column_name = 'weight'
           AND data_type = 'character varying'
    ) THEN
        ALTER TABLE dtc_orders
            ALTER COLUMN weight TYPE NUMERIC(13,2) USING NULLIF(TRIM(weight), '')::NUMERIC(13,2);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'dtc_orders'
           AND column_name = 'unit_value'
           AND data_type = 'character varying'
    ) THEN
        ALTER TABLE dtc_orders
            ALTER COLUMN unit_value TYPE NUMERIC(13,2) USING NULLIF(TRIM(unit_value), '')::NUMERIC(13,2);
    END IF;
END $$;
