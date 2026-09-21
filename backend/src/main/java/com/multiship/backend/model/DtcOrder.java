package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTC Order — synced from Oracle TB_SHIPX_DTC_UVW.
 * Stores pending Direct-to-Consumer orders in PostgreSQL for processing.
 */
@Entity
@Table(name = "dtc_orders", indexes = {
    @Index(name = "idx_batch_id", columnList = "batch_id"),
    @Index(name = "idx_tote_number", columnList = "tote_number"),
    @Index(name = "idx_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_order_no", columnList = "order_no"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
public class DtcOrder {

    @Id
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "tote_number")
    private Long toteNumber;

    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "order_suffix")
    private Integer orderSuffix;

    @Column(name = "shipvia_code")
    private String shipViaCode;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "cust_no")
    private String custNo;

    // Ship-to Address
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

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    // Package Info
    @Column(name = "weight")
    private String weight;

    @Column(name = "unit_value")
    private String unitValue;

    @Column(name = "price")
    private String price;

    // Shipping Details
    @Column(name = "third_party_account")
    private String thirdPartyAccount;

    @Column(name = "intl_yn")
    private String intlYn;

    @Column(name = "location")
    private String location;

    // Additional DTC Fields
    @Column(name = "cust_po")
    private String custPo;

    @Column(name = "goods_desc")
    private String goodsDesc;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
