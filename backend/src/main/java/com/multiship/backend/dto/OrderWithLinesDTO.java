package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderWithLinesDTO {
    private Integer orderNo;
    private Integer orderSuffix;
    /** Sprint 48 B10 — customer-facing prefix for manual orders (e.g. "MAN900001"). */
    private String displayOrderNo;
    private String orderStatus;
    private String custNo;
    private String shipName;
    private String shipAttn;
    private String shipAddr1;
    private String phone;
    private String shiptoCity;
    private String shiptoState;
    private String shiptoZip;
    private String shiptoCountryCd;
    private String shipviaCd;
    private String tenantId;
    private BigDecimal weight;
    /** LB | KG — unit the weight was entered in; null on legacy rows. */
    private String weightUnit;
    /** Declared / customs value entered for the shipment. */
    private BigDecimal declaredValue;
    private String goodsDesc;
    private LocalDate createdDate;
    // Ship-FROM (origin) captured on manual shipments — null on ERP/WMS orders.
    private String shipFromName;
    private String shipFromCompany;
    private String shipFromAddr1;
    private String shipFromAddr2;
    private String shipFromCity;
    private String shipFromState;
    private String shipFromZip;
    private String shipFromCountryCd;
    private String shipFromPhone;
    /** True when the ship-from was resolved from the client warehouse / platform
     *  default rather than a sender entered on the order (bulk/ERP orders). */
    private Boolean shipFromResolved;
    /** 'Y'|'N' — cross-border classification recorded at label time. */
    private String intlYn;
    /** 'Y' when this is a reverse/return label. */
    private String isReturn;
    /** Per-shipment importer/broker override (JSON), or null to use the client profile. */
    private String importerBrokerOverride;
    /** Total number of packages in this shipment; null / 1 for single-box orders. */
    private Integer packageCount;
    /** Per-box rows (tracking, weight, dims). Ordered by sequenceNumber. Empty on legacy orders. */
    private List<LabelPackageDTO> packages;
    private List<OrderLineDTO> orderLines;
}
