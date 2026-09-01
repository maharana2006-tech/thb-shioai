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
    /** PR #543 — order source (`MANUAL | BULK | WMS | API | ERP`). Drives
     *  the PO field on the JSX label facsimile. Null on legacy rows —
     *  FE facsimile falls through to orderNo bare. */
    private String source;
    /** PR #543 — external order id from a WMS pull. Used as the PO value
     *  when `source === 'WMS'`. Null for non-WMS orders. */
    private String wmsExternalId;
    /** Per-shipment importer/broker override (JSON), or null to use the client profile. */
    private String importerBrokerOverride;
    /** Total number of packages in this shipment; null / 1 for single-box orders. */
    private Integer packageCount;
    /** Per-box rows (tracking, weight, dims). Ordered by sequenceNumber. Empty on legacy orders. */
    private List<LabelPackageDTO> packages;
    private List<OrderLineDTO> orderLines;
}
