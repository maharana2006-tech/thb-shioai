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
    private String goodsDesc;
    private LocalDate createdDate;
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
