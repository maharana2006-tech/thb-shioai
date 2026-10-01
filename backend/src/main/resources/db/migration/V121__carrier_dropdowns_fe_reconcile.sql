-- V121 — fill the gaps between V120 seeds and NewShipmentPage's live
-- hardcoded options. The FE picker swap (second half of V120 wire)
-- would silently drop EPL / JPG / several FEDEX pickup types otherwise.
--
-- Idempotent — safe to re-run.

DO $$
BEGIN
    -- UPS label format missing EPL (legacy Eltron/Zebra). FE line 3449.
    INSERT INTO carrier_label_format (carrier, code, label, is_stock_type, sort_order) VALUES
        ('UPS',  'EPL', 'EPL (Eltron/legacy Zebra)', TRUE, 60),
        ('USPS', 'JPG', 'JPG (raster)',              FALSE, 40)
    ON CONFLICT (carrier, code) DO NOTHING;

    -- FedEx pickup types: FE offers the richer set that FedEx's REST API
    -- actually accepts (not just the four from V120). Keep V120's as-is;
    -- add the ones the FE renders today.
    INSERT INTO carrier_pickup_type (carrier, code, label, sort_order) VALUES
        ('FEDEX', 'USE_SCHEDULED_PICKUP',    'Use scheduled pickup',      5),
        ('FEDEX', 'REQUEST_COURIER',         'Request courier',           25),
        ('FEDEX', 'DROP_BOX',                'Drop box',                  35),
        ('FEDEX', 'BUSINESS_SERVICE_CENTER', 'Business service center',   37),
        ('FEDEX', 'STATION',                 'Station',                   38)
    ON CONFLICT (carrier, code) DO NOTHING;
END $$;
