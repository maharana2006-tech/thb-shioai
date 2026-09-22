package com.multiship.backend.model.oracle;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Oracle DTC View: TB_SHIPX_DTC_UVW / TB_SHIPX_DTC_UVW_TEST
 * Maps pending DTC orders from Oracle NDS.
 * Read-only entity — no writes to Oracle.
 *
 * View name is configurable via oracle.dtc.view-name property.
 */
@Entity
@Table(name = "TB_SHIPX_DTC_UVW_TEST")
@Data
@NoArgsConstructor
public class OracleDtcOrder {

    // ═══════════════════════════ Primary Keys ═══════════════════════════
    @Id
    @Column(name = "BATCH_ID")
    private BigDecimal batchId;

    // ═══════════════════════════ Order Information ═══════════════════════════
    @Column(name = "ORDER_NO")
    private BigDecimal orderNo;

    @Column(name = "ORDER_SUFFIX")
    private BigDecimal orderSuffix;

    @Column(name = "ORDER_STATUS")
    private String orderStatus;  // VARCHAR2(1)

    // ═══════════════════════════ Customer Information ═══════════════════════════
    @Column(name = "CUST_NO")
    private String custNo;  // VARCHAR2(10)

    @Column(name = "CUST_PO")
    private String custPo;  // VARCHAR2(30)

    @Column(name = "TENANT_ID")
    private String tenantId;  // VARCHAR2(30)

    // ═══════════════════════════ Shipping Method & Terms ═══════════════════════════
    @Column(name = "SHIPVIA_CD")
    private String shipViaCode;  // VARCHAR2(3)

    @Column(name = "SHIP_VIA")
    private String shipVia;  // VARCHAR2(3)

    @Column(name = "TERMS_CD")
    private String termsCode;  // VARCHAR2(3)

    // ═══════════════════════════ Ship-to Address ═══════════════════════════
    @Column(name = "SHIP_NAME")
    private String shipName;  // VARCHAR2(30)

    @Column(name = "SHIP_ATTN")
    private String shipAttn;  // VARCHAR2(30)

    @Column(name = "SHIP_ADDR1")
    private String shipAddr1;  // VARCHAR2(30)

    @Column(name = "SHIP_ADDR2")
    private String shipAddr2;  // VARCHAR2(30)

    @Column(name = "SHIP_ADDR3")
    private String shipAddr3;  // VARCHAR2(30)

    @Column(name = "SHIPTO_CITY")
    private String shipToCity;  // VARCHAR2(30)

    @Column(name = "SHIPTO_STATE")
    private String shipToState;  // VARCHAR2(4)

    @Column(name = "SHIPTO_ZIP")
    private String shipToZip;  // VARCHAR2(10)

    @Column(name = "SHIPTO_COUNTRY_CD")
    private String shipToCountryCode;  // VARCHAR2(3)

    @Column(name = "COUNTRY_NAME")
    private String countryName;  // VARCHAR2(30)

    // ═══════════════════════════ Contact Information ═══════════════════════════
    @Column(name = "PHONE")
    private String phone;  // VARCHAR2(30)

    @Column(name = "EMAIL")
    private String email;  // VARCHAR2(50)

    // ═══════════════════════════ Package & Shipment Details ═══════════════════════════
    @Column(name = "WEIGHT")
    private BigDecimal weight;  // NUMBER

    @Column(name = "UNIT_VALUE")
    private BigDecimal unitValue;  // NUMBER

    @Column(name = "PRICE")
    private String price;  // CHAR(3)

    @Column(name = "FREIGHT_COST")
    private BigDecimal freightCost;  // NUMBER(13,2)

    @Column(name = "GOODS_DESC")
    private String goodsDesc;  // CHAR(17)

    @Column(name = "INTL_YN")
    private String intlYn;  // CHAR(1) - Y/N flag

    // ═══════════════════════════ Warehouse & Logistics ═══════════════════════════
    @Column(name = "TOTE_NUMBER")
    private String toteNumber;  // VARCHAR2(20)

    @Column(name = "LOCATION")
    private String location;  // VARCHAR2(5)

    @Column(name = "TRACK")
    private String track;  // VARCHAR2(50)

    @Column(name = "THIRD_PARTY_ACC")
    private String thirdPartyAccount;  // VARCHAR2(30)

    // ═══════════════════════════ Fulfillment & Shipping Dates ═══════════════════════════
    @Column(name = "SHIP_DT")
    private String shipDate;  // VARCHAR2

    @Column(name = "FF_SCHEMA_SUBSTR")
    private String ffSchemaSubstr;  // CHAR(6)
}
