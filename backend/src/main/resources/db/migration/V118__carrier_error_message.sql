-- §4 extraction — carrier_error_message (Jobs H-1).
--
-- CarrierErrorMessages.humanize has six hardcoded pattern→sentence
-- mappings that turn raw carrier rejection payloads into one operator-
-- facing sentence. ShipX had the equivalent (SPX_tblCreateShipmentAPIErrors)
-- as a 537-row DB table that ops could tune without a redeploy. V118
-- adds the shioai analogue seeded with the six current patterns; the
-- Java utility keeps them as a bootstrap default so a DB outage never
-- degrades to raw payloads reaching the operator UI.
--
-- Pattern matches an UPPERCASED version of the raw message (the Java
-- helper does raw.toUpperCase() before applying any pattern), so seeds
-- are stored uppercase too. Ops editing a row should keep that
-- convention or match_regex becomes case-fragile.

DO $$
BEGIN
    IF to_regclass('public.carrier_error_message') IS NULL THEN
        CREATE TABLE carrier_error_message (
            id            BIGSERIAL PRIMARY KEY,
            -- NULL = any carrier. Otherwise canonical code (FEDEX / UPS / etc.).
            carrier       VARCHAR(32),
            -- Space-separated OR tokens. Matcher checks "any token in uppercased raw".
            match_any_of  TEXT NOT NULL,
            humanized     TEXT NOT NULL,
            sort_order    INTEGER NOT NULL DEFAULT 0,
            created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_carrier_error_message_sort ON carrier_error_message (sort_order);
    END IF;

    -- Six current patterns, same ordering as CarrierErrorMessages.humanize.
    INSERT INTO carrier_error_message (carrier, match_any_of, humanized, sort_order) VALUES
        (NULL, 'NOTSERVED|NOT SERVED|DESTINATION.COUNTRY|ORIGIN.COUNTRY',
               '{carrier} doesn''t serve this lane on the selected service.', 10),
        (NULL, 'PHONENUMBER|PHONE NUMBER|PHONE.',
               '{carrier} needs a valid recipient phone number for this shipment.', 20),
        (NULL, 'NOT A REGISTERED|NOT AUTHORIZED|NOT AUTHORISED|UNAUTHORIZED',
               '{carrier} rejected the billing account. The account isn''t authorised for this carrier — check Settings → Carriers.', 30),
        (NULL, 'POSTAL|ZIP',
               '{carrier} rejected the postal code for this address.', 40),
        (NULL, 'CUSTOMS|COMMODITY|TOTALCUSTOMSVALUE',
               '{carrier} rejected the customs details for this international shipment.', 50)
    ON CONFLICT DO NOTHING;
END $$;
