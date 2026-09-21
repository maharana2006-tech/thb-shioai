package com.multiship.backend.model.oracle;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Oracle DTC View: TB_SHIPX_DTC_UVW
 * Maps pending DTC orders from Oracle NDS.
 * Read-only entity — no writes to Oracle.
 */
@Entity
@Table(name = "TB_SHIPX_DTC_UVW_TEST")
@Data
@NoArgsConstructor
public class OracleDtcOrder {

    @Id
    @Column(name = "BATCH_ID")
    private Long batchId;

    @Column(name = "TOTE_NUMBER")
    private Long toteNumber;

    @Column(name = "ORDER_NO")
    private Integer orderNo;

    @Column(name = "ORDER_SUFFIX")
    private String orderSuffix;

    @Column(name = "SHIPVIA_CD")
    private String shipViaCode;

    @Column(name = "TENANT_ID")
    private String tenantId;

    @Column(name = "CUST_NO")
    private String custNo;

    // Ship-to Address
    @Column(name = "SHIP_NAME")
    private String shipName;

    @Column(name = "SHIP_ATTN")
    private String shipAttn;

    @Column(name = "SHIP_ADDR1")
    private String shipAddr1;

    @Column(name = "SHIP_ADDR2")
    private String shipAddr2;

    @Column(name = "SHIP_ADDR3")
    private String shipAddr3;

    @Column(name = "SHIPTO_CITY")
    private String shipToCity;

    @Column(name = "SHIPTO_STATE")
    private String shipToState;

    @Column(name = "SHIPTO_ZIP")
    private String shipToZip;

    @Column(name = "SHIPTO_COUNTRY_CD")
    private String shipToCountryCode;

    @Column(name = "PHONE")
    private String phone;

    @Column(name = "EMAIL")
    private String email;

    // Package Info
    @Column(name = "WEIGHT")
    private BigDecimal weight;

    @Column(name = "UNIT_VALUE")
    private BigDecimal unitValue;

    @Column(name = "PRICE")
    private BigDecimal price;

    // Shipping Details
    @Column(name = "THIRD_PARTY_ACC")
    private String thirdPartyAccount;

    @Column(name = "INTL_YN")
    private String intlYn;  // Y/N flag

    @Column(name = "LOCATION")
    private String location;

    // Audit
    @Column(name = "CREATED_DATE")
    private Date createdDate;

    @Column(name = "CREATED_BY")
    private String createdBy;

    @Column(name = "MODIFIED_DATE")
    private Date modifiedDate;

    // Additional DTC Fields
    @Column(name = "CUST_PO")
    private String custPo;

    @Column(name = "GOODS_DESC")
    private String goodsDesc;

    @Column(name = "CONTAINER_ID")
    private String containerId;

    @Column(name = "SHIP_METHOD")
    private String shipMethod;

    @Column(name = "ORDER_STATUS")
    private String orderStatus;
}
