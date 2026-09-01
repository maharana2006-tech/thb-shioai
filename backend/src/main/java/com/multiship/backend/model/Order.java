package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "label_batch")
@Data
public class Order {

    @Id
    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "order_suffix")
    private Integer orderSuffix;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "cust_no")
    private String custNo;

    @Column(name = "terms_cd")
    private String termsCd;

    @Column(name = "shipvia_cd")
    private String shipviaCd;

    @Column(name = "ship_addr1")
    private String shipAddr1;

    @Column(name = "shipto_city")
    private String shiptoCity;

    @Column(name = "shipto_state")
    private String shiptoState;

    @Column(name = "shipto_zip")
    private String shiptoZip;

    @Column(name = "shipto_country_cd")
    private String shiptoCountryCd;

    @Column(name = "ship_attn")
    private String shipAttn;

    @Column(name = "ship_name")
    private String shipName;

    @Column(name = "country_name")
    private String countryName;

    // ----- Ship-FROM (origin) — persisted for manual shipments so the label
    // document + commercial invoice render the sender the operator actually
    // entered, instead of falling back to the platform/tenant warehouse (which
    // would flip a domestic shipment international when the two differ). Null on
    // ERP/WMS orders, which ship from the client's own warehouse. -----
    @Column(name = "ship_from_name")
    private String shipFromName;

    @Column(name = "ship_from_company")
    private String shipFromCompany;

    @Column(name = "ship_from_addr1")
    private String shipFromAddr1;

    @Column(name = "ship_from_addr2")
    private String shipFromAddr2;

    @Column(name = "ship_from_city")
    private String shipFromCity;

    @Column(name = "ship_from_state")
    private String shipFromState;

    @Column(name = "ship_from_zip")
    private String shipFromZip;

    @Column(name = "ship_from_country_cd")
    private String shipFromCountryCd;

    @Column(name = "ship_from_phone")
    private String shipFromPhone;

    /** LB | KG — unit the operator entered the weight in. Null on legacy rows
     *  (readers fall back to the tenant/global default). */
    @Column(name = "weight_unit", length = 4)
    private String weightUnit;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "intl_yn")
    private String intlYn;

    @Column(name = "freight_cost")
    private BigDecimal freightCost;

    @Column(name = "ff_schema_substr")
    private String ffSchemaSubstr;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "location")
    private String location;

    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "ship_via")
    private String shipVia;

    @Column(name = "track")
    private String track;

    @Column(name = "tote_number")
    private Integer toteNumber;

    @Column(name = "goods_desc")
    private String goodsDesc;

    @Column(name = "unit_value")
    private Integer unitValue;

    @Column(name = "batch_id")
    private Integer batchId;

    @Column(name = "phone")
    private String phone;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Column(name = "is_processed")
    private Boolean isProcessed;

    @Column(name = "is_error")
    private Boolean isError;

    @Column(name = "is_label_generated")
    private Boolean isLabelGenerated;

    @Column(name = "api_status")
    private Integer apiStatus;

    @Column(name = "is_manual")
    private String isManual;

    /** Where the order came from: MANUAL | WMS | API | ERP. Null on legacy rows (derived at read time). */
    @Column(name = "order_source", length = 20)
    private String source;

    /** 'Y' when this is a reverse/return label (customer ships back). */
    @Column(name = "is_return")
    private String isReturn;

    /** External order id from the source WMS (source = WMS). Used to make the
     *  WMS pull idempotent — an order already pulled is skipped on re-pull.
     *  Null for non-WMS orders. */
    @Column(name = "wms_external_id", length = 100)
    private String wmsExternalId;

    /** Per-shipment importer/broker override (JSON) — used INSTEAD of the client's saved
     *  profile for this one label, without changing the profile. Null = use the profile. */
    @Column(name = "importer_broker_override", columnDefinition = "text")
    private String importerBrokerOverride;

    /**
     * Total number of packages (boxes) in this shipment. 1 for a single-
     * package order; N for multi-package. Persisted from
     * {@code ShipmentRequestDTO.packages.size()} at label-generation time
     * so downstream reads (label endpoint, exports, dashboards) can render
     * "PKG N OF M" without recomputing.
     */
    @Column(name = "package_count")
    private Integer packageCount;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<OrderLine> orderLines;

    /**
     * Sprint 48 B10 — optimistic locking. Concurrent updates to the same
     * order row fail-fast with {@code OptimisticLockException} instead of
     * silently overwriting each other. Hibernate auto-increments this on
     * every save and adds it to the UPDATE's WHERE clause. Nullable so
     * existing rows on first upgrade start at NULL (Hibernate treats null
     * as "no prior version" and inserts version=0 on first save).
     */
    @jakarta.persistence.Version
    @jakarta.persistence.Column(name = "version", nullable = true)
    private Long version;
}
