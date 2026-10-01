-- §4 extraction — carrier_label_format + carrier_pickup_type +
-- carrier_clearance_option. Manual F-09 / F-10.
--
-- Three per-carrier dropdown vocabularies that today live as hardcoded
-- arrays in NewShipmentPage.tsx + customsOptions.ts. Moving them to DB
-- rows means "add a new label format" / "add a new pickup type" is one
-- SQL insert, not a FE deploy. The FE wizard picker swap is a follow-up
-- — this migration provides the backend infra + admin read-only surface.
--
-- Seed data mirrors the current hardcoded options so a swap is a 1:1
-- behaviour-preserving replacement.

DO $$
BEGIN
    -- ===== carrier_label_format ======================================
    IF to_regclass('public.carrier_label_format') IS NULL THEN
        CREATE TABLE carrier_label_format (
            carrier       VARCHAR(32) NOT NULL,
            code          VARCHAR(64) NOT NULL,
            label         VARCHAR(120) NOT NULL,
            -- Stock labels are pre-cut 4x6 / fanfold; plain labels are
            -- A4/Letter sheets printed on standard laser. FE picker shows
            -- stock + plain in separate groups.
            is_stock_type BOOLEAN NOT NULL DEFAULT FALSE,
            sort_order    INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (carrier, code)
        );
        CREATE INDEX IF NOT EXISTS ix_carrier_label_format_carrier
            ON carrier_label_format (carrier);
    END IF;
    INSERT INTO carrier_label_format (carrier, code, label, is_stock_type, sort_order) VALUES
        -- FedEx
        ('FEDEX', 'COMMON2D',       'Common 2D (stock)',       TRUE,  10),
        ('FEDEX', 'LABEL_DATA_ONLY','Label data only',         FALSE, 20),
        ('FEDEX', 'PDF',            'PDF (plain)',             FALSE, 30),
        ('FEDEX', 'PNG',            'PNG (plain)',             FALSE, 40),
        ('FEDEX', 'ZPLII',          'ZPL (thermal)',           TRUE,  50),
        -- UPS
        ('UPS',   'GIF',            'GIF (stock)',             TRUE,  10),
        ('UPS',   'PDF',            'PDF (plain)',             FALSE, 20),
        ('UPS',   'PNG',            'PNG (plain)',             FALSE, 30),
        ('UPS',   'ZPL',            'ZPL (thermal)',           TRUE,  40),
        -- USPS
        ('USPS',  'PDF',            'PDF (plain)',             FALSE, 10),
        ('USPS',  'ZPL',            'ZPL (thermal)',           TRUE,  20),
        ('USPS',  'PNG',            'PNG (plain)',             FALSE, 30),
        -- DHL
        ('DHL',   'PDF',            'PDF (plain)',             FALSE, 10),
        ('DHL',   'ZPL',            'ZPL (thermal)',           TRUE,  20)
    ON CONFLICT (carrier, code) DO UPDATE
        SET label = EXCLUDED.label,
            is_stock_type = EXCLUDED.is_stock_type,
            sort_order = EXCLUDED.sort_order;

    -- ===== carrier_pickup_type =======================================
    IF to_regclass('public.carrier_pickup_type') IS NULL THEN
        CREATE TABLE carrier_pickup_type (
            carrier    VARCHAR(32) NOT NULL,
            code       VARCHAR(64) NOT NULL,
            label      VARCHAR(120) NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (carrier, code)
        );
        CREATE INDEX IF NOT EXISTS ix_carrier_pickup_type_carrier
            ON carrier_pickup_type (carrier);
    END IF;
    INSERT INTO carrier_pickup_type (carrier, code, label, sort_order) VALUES
        -- FedEx
        ('FEDEX', 'REGULAR_PICKUP',               'Regular pickup',           10),
        ('FEDEX', 'ONE_TIME_PICKUP',              'One-time pickup',          20),
        ('FEDEX', 'DROPOFF_AT_FEDEX_LOCATION',    'Drop off at FedEx',        30),
        ('FEDEX', 'CONTACT_FEDEX_TO_SCHEDULE',    'Contact FedEx to schedule',40),
        -- UPS
        ('UPS',   '01',                           '01 — Daily pickup',        10),
        ('UPS',   '03',                           '03 — Customer counter',    20),
        ('UPS',   '06',                           '06 — One-time pickup',     30),
        -- USPS
        ('USPS',  'PICKUP',                       'Carrier pickup',           10),
        ('USPS',  'HOLD_FOR_PICKUP',              'Hold for pickup',          20),
        -- DHL
        ('DHL',   'REGULAR_PICKUP',               'Regular pickup',           10),
        ('DHL',   'REQUEST_COURIER',              'Request courier',          20)
    ON CONFLICT (carrier, code) DO UPDATE
        SET label = EXCLUDED.label,
            sort_order = EXCLUDED.sort_order;

    -- ===== carrier_clearance_option (mirrors customsOptions.ts) =======
    IF to_regclass('public.carrier_clearance_option') IS NULL THEN
        CREATE TABLE carrier_clearance_option (
            carrier    VARCHAR(32) NOT NULL,
            code       VARCHAR(64) NOT NULL,
            label      VARCHAR(120) NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (carrier, code)
        );
        CREATE INDEX IF NOT EXISTS ix_carrier_clearance_option_carrier
            ON carrier_clearance_option (carrier);
    END IF;
    INSERT INTO carrier_clearance_option (carrier, code, label, sort_order) VALUES
        -- UPS
        ('UPS',   'SENDER',       'Sender pays',       10),
        ('UPS',   'RECEIVER',     'Receiver pays',     20),
        ('UPS',   'THIRD_PARTY',  'Third party',       30),
        -- FedEx (RECIPIENT not RECEIVER — carrier API spelling)
        ('FEDEX', 'SENDER',       'Sender pays',       10),
        ('FEDEX', 'RECIPIENT',    'Recipient pays',    20),
        ('FEDEX', 'THIRD_PARTY',  'Third party',       30),
        -- USPS
        ('USPS',  'DDU',          'DDU — Duties on Delivery', 10),
        ('USPS',  'DDP',          'DDP — Duties Paid',        20),
        -- DHL (Incoterms-style)
        ('DHL',   'DAP',          'DAP — Delivered At Place (receiver pays duties)',    10),
        ('DHL',   'DDP',          'DDP — Delivered Duty Paid (sender pays duties)',     20),
        ('DHL',   'EXW',          'EXW — Ex Works (receiver arranges pickup)',          30)
    ON CONFLICT (carrier, code) DO UPDATE
        SET label = EXCLUDED.label,
            sort_order = EXCLUDED.sort_order;
END $$;
