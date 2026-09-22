package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTC Order — synced from Oracle TB_SHIPX_DTC_UVW / TB_SHIPX_DTC_UVW_TEST.
 * Stores pending Direct-to-Consumer orders in PostgreSQL for processing.
 *
 * Maps all columns from the Oracle view for complete data synchronization.
 */
@Entity
@Table(name = "dtc_orders", indexes = {
    @Index(name = "idx_batch_id", columnList = "batch_id"),
    @Index(name = "idx_tote_number", columnList = "tote_number"),
    @Index(name = "idx_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_order_no", columnList = "order_no"),
    @Index(name = "idx_cust_no", columnList = "cust_no"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
public class DtcOrder {

    // ═══════════════════════════ Primary Keys ═══════════════════════════
    @Id
    @Column(name = "batch_id")
    private BigDecimal batchId;

    // ═══════════════════════════ Order Information ═══════════════════════════
    @Column(name = "order_no")
    private BigDecimal orderNo;

    @Column(name = "order_suffix")
    private BigDecimal orderSuffix;

    @Column(name = "order_status")
    private String orderStatus;

    // ═══════════════════════════ Customer Information ═══════════════════════════
    @Column(name = "cust_no")
    private String custNo;

    @Column(name = "cust_po")
    private String custPo;

    @Column(name = "tenant_id")
    private String tenantId;

    // ═══════════════════════════ Shipping Method & Terms ═══════════════════════════
    @Column(name = "shipvia_code")
    private String shipViaCode;

    @Column(name = "ship_via")
    private String shipVia;

    @Column(name = "terms_code")
    private String termsCode;

    // ═══════════════════════════ Ship-to Address ═══════════════════════════
    @Column(name = "ship_name")
    private String shipName;

    @Column(name = "ship_attn")
    private String shipAttn;

    @Column(name = "ship_addr1")
    private String shipAddr1;

    @Column(name = "ship_addr2")
    private String shipAddr2;

    @Column(name = "ship_addr3")
    private String shipAddr3;

    @Column(name = "shipto_city")
    private String shipToCity;

    @Column(name = "shipto_state")
    private String shipToState;

    @Column(name = "shipto_zip")
    private String shipToZip;

    @Column(name = "shipto_country_code")
    private String shipToCountryCode;

    @Column(name = "country_name")
    private String countryName;

    // ═══════════════════════════ Contact Information ═══════════════════════════
    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    // ═══════════════════════════ Package & Shipment Details ═══════════════════════════
    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "unit_value")
    private BigDecimal unitValue;

    @Column(name = "price")
    private String price;

    @Column(name = "freight_cost")
    private BigDecimal freightCost;

    @Column(name = "goods_desc")
    private String goodsDesc;

    @Column(name = "intl_yn")
    private String intlYn;

    // ═══════════════════════════ Warehouse & Logistics ═══════════════════════════
    @Column(name = "tote_number")
    private String toteNumber;

    @Column(name = "location")
    private String location;

    @Column(name = "track")
    private String track;

    @Column(name = "third_party_account")
    private String thirdPartyAccount;

    // ═══════════════════════════ Fulfillment & Shipping Dates ═══════════════════════════
    @Column(name = "ship_date")
    private String shipDate;

    @Column(name = "ff_schema_substr")
    private String ffSchemaSubstr;

    // ═══════════════════════════ Audit Fields ═══════════════════════════
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
