-- ============================================================
-- PR3 — Invented NDS DDL for the NDS Shipment prefill IT.
-- Loaded once, on Oracle container startup, via
-- withInitScript("nds-oracle-init.sql") in NdsShipmentLookupOracleIT.
--
-- WARNING: This schema is inferred from the task spec (PR #735)
-- and NOT guaranteed to match the real NDS ERP schema. The point
-- of PR3 is to prove the SQL runs against a real Oracle instance
-- + the row-mapping shape matches typed records — not to certify
-- production compatibility. Adjust column names / types when the
-- real DDL is available.
--
-- Everything lives in the container's default APP schema (the
-- container's created user), so no CREATE USER dance. Bind params
-- referenced by the repository still work — they're by-name, not
-- schema-scoped.
-- ============================================================

-- ─── TB_SHIP_CONTAINER (PRODUCTION-login, .X owner lookup) ───
CREATE TABLE TB_SHIP_CONTAINER (
  CONTAINER_ID VARCHAR2(40) PRIMARY KEY,
  TENANT_ID    VARCHAR2(50) NOT NULL,
  ORDER_NO     VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX VARCHAR2(10) NOT NULL
);

-- ─── OEHEAD (CLIENT-login, order header) ─────────────────────
CREATE TABLE OEHEAD (
  ORDER_NO           VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX       VARCHAR2(10) NOT NULL,
  SHIP_TO_NAME       VARCHAR2(200),
  SHIP_TO_ATTN       VARCHAR2(200),
  SHIP_TO_ADDR1      VARCHAR2(200),
  SHIP_TO_ADDR2      VARCHAR2(200),
  SHIP_TO_ADDR3      VARCHAR2(200),
  SHIP_TO_CITY       VARCHAR2(100),
  SHIP_TO_STATE      VARCHAR2(50),
  SHIP_TO_POSTAL     VARCHAR2(20),
  SHIP_TO_COUNTRY_CD VARCHAR2(2),
  SHIP_TO_PHONE      VARCHAR2(40),
  SHIP_TO_EMAIL      VARCHAR2(200),
  SHIPVIA_CD         VARCHAR2(20),
  SHIPPED_FLAG       VARCHAR2(1),
  HOLD_FLAG          VARCHAR2(1),
  CUST_PO            VARCHAR2(80),
  DEPARTMENT         VARCHAR2(40),
  INCOTERMS          VARCHAR2(10),
  CURRENCY_CD        VARCHAR2(3),
  CONSTRAINT PK_OEHEAD PRIMARY KEY (ORDER_NO, ORDER_SUFFIX)
);

-- ─── OE_SHIP_CONTAINER (CLIENT-login, per-order packages) ────
-- Column names confirmed against the real NDS Oracle schema (see
-- NdsDebugController) — GROSS_WT / LENGTH / WIDTH / HEIGHT / CONTAINER_TYPE,
-- not the BILLABLE_WEIGHT_LB / *_IN / PACKAGE_TYPE_CD names PR3 invented.
CREATE TABLE OE_SHIP_CONTAINER (
  CONTAINER_ID   VARCHAR2(40) PRIMARY KEY,
  ORDER_NO       VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX   VARCHAR2(10) NOT NULL,
  GROSS_WT       NUMBER(14, 5),
  LENGTH         NUMBER(3, 0),
  WIDTH          NUMBER(3, 0),
  HEIGHT         NUMBER(3, 0),
  CONTAINER_TYPE VARCHAR2(10)
);

-- ─── SHIPVIA (CLIENT-login, ship-method description) ─────────
CREATE TABLE SHIPVIA (
  SHIPVIA_CD   VARCHAR2(3) PRIMARY KEY,
  SHIPVIA_DESC VARCHAR2(30)
);

-- ─── OE_SEND_TO (CLIENT-login, notify emails) ────────────────
CREATE TABLE OE_SEND_TO (
  ORDER_NO     VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX VARCHAR2(10) NOT NULL,
  SEND_TO      VARCHAR2(50),
  SEND_TYPE    VARCHAR2(1),
  CONSTRAINT PK_OE_SEND_TO PRIMARY KEY (ORDER_NO, ORDER_SUFFIX, SEND_TO)
);

-- ─── OE_INTL_ITEMS (CLIENT-login, CI line items) ─────────────
CREATE TABLE OE_INTL_ITEMS (
  ORDER_NO         VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX     VARCHAR2(10) NOT NULL,
  LINE_NO          NUMBER(6)     NOT NULL,
  DESCRIPTION      VARCHAR2(400),
  QUANTITY         NUMBER(10),
  UNIT_VALUE       NUMBER(15, 4),
  CURRENCY_CD      VARCHAR2(3),
  HS_CODE          VARCHAR2(20),
  COUNTRY_OF_ORIGIN VARCHAR2(2),
  UNIT_WEIGHT_LB   NUMBER(10, 3),
  CONSTRAINT PK_OE_INTL_ITEMS PRIMARY KEY (ORDER_NO, ORDER_SUFFIX, LINE_NO)
);

-- ─── TB_BILLABLE_CONTAINERS (PRODUCTION-login, .Y batch owner + contents) ─
CREATE TABLE TB_BILLABLE_CONTAINERS (
  BATCH_ID            VARCHAR2(40) NOT NULL,
  CONTAINER_ID        VARCHAR2(40) NOT NULL,
  ORDER_NO            VARCHAR2(40) NOT NULL,
  ORDER_SUFFIX        VARCHAR2(10) NOT NULL,
  FF_SCHEMA           VARCHAR2(50),
  PRIMARY_CLIENT_CODE VARCHAR2(50),
  CONSTRAINT PK_TB_BILL_CTN PRIMARY KEY (BATCH_ID, CONTAINER_ID)
);
