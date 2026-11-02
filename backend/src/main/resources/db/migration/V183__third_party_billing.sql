-- Shipping charges billed to a third party's carrier account (UPS BillThirdParty /
-- FedEx paymentType THIRD_PARTY). SENDER keeps today's behaviour: the shipper account pays.
ALTER TABLE label_batch
    ADD COLUMN IF NOT EXISTS bill_to                 VARCHAR(16) NOT NULL DEFAULT 'SENDER',
    ADD COLUMN IF NOT EXISTS third_party_account     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS third_party_postal_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS third_party_country     VARCHAR(2);

-- Bulk import rows: payer postal code + country next to the existing bill_to / account_number.
ALTER TABLE import_batch_row
    ADD COLUMN IF NOT EXISTS third_party_postal_code TEXT,
    ADD COLUMN IF NOT EXISTS third_party_country     TEXT;
