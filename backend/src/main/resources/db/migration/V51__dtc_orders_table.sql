-- Create DTC Orders table for Oracle sync
-- Stores pending Direct-to-Consumer orders synced from Oracle TB_SHIPX_DTC_UVW

CREATE TABLE dtc_orders (
    batch_id BIGINT PRIMARY KEY,
    tote_number BIGINT NOT NULL,
    order_no INTEGER NOT NULL,
    order_suffix INTEGER,
    shipvia_code VARCHAR(50),
    tenant_id VARCHAR(50) NOT NULL,
    cust_no VARCHAR(50),

    -- Ship-to address
    ship_name VARCHAR(100),
    ship_attn VARCHAR(100),
    ship_addr1 VARCHAR(100),
    ship_addr2 VARCHAR(100),
    ship_addr3 VARCHAR(100),
    shipto_city VARCHAR(50),
    shipto_state VARCHAR(20),
    shipto_zip VARCHAR(20),
    shipto_country_code VARCHAR(10),

    -- Contact
    phone VARCHAR(50),
    email VARCHAR(100),

    -- Package info (stored as strings to preserve Oracle format)
    weight VARCHAR(50),
    unit_value VARCHAR(50),
    price VARCHAR(50),

    -- Shipping details
    third_party_account VARCHAR(100),
    intl_yn CHAR(1),
    location VARCHAR(50),

    -- Additional fields
    cust_po VARCHAR(100),
    goods_desc TEXT,

    -- Sync timestamp
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Create indexes for common queries
CREATE INDEX idx_batch_id ON dtc_orders(batch_id);
CREATE INDEX idx_tote_number ON dtc_orders(tote_number);
CREATE INDEX idx_tenant_id ON dtc_orders(tenant_id);
CREATE INDEX idx_order_no ON dtc_orders(order_no);
CREATE INDEX idx_created_at ON dtc_orders(created_at);

-- Unique constraint on batch_id (already primary key, but explicit for clarity)
ALTER TABLE dtc_orders ADD CONSTRAINT uk_batch_id UNIQUE (batch_id);
