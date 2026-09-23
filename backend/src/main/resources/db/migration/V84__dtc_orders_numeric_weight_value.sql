-- V84 — dtc_orders.weight / unit_value become NUMERIC
-- V75 created them as VARCHAR(50); the DtcOrder entity now maps them as BigDecimal
-- (like freight_cost from V82). Hibernate cannot ALTER a text column to numeric on
-- its own ("cannot be cast automatically"), and a prod boot (ddl-auto=validate)
-- would refuse the mismatch — so the cast is done here, blank text becoming NULL.

ALTER TABLE dtc_orders
    ALTER COLUMN weight TYPE NUMERIC(13,2) USING NULLIF(TRIM(weight), '')::NUMERIC(13,2);

ALTER TABLE dtc_orders
    ALTER COLUMN unit_value TYPE NUMERIC(13,2) USING NULLIF(TRIM(unit_value), '')::NUMERIC(13,2);
