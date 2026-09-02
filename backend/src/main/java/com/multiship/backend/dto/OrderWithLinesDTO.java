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
    /**
     * PR #548 (Sprint 52 follow-up) — one entry per {@code shipment_batch}
     * row, ordered by batchSeq. Populated for multi-package shipments;
     * empty for single-pkg / pre-Sprint-48 legacy orders. Callers can
     * grab the batch masters when they need to expose "master tracking
     * vs child tracking" semantics (industry-standard for MPS shipments —
     * FedEx returns a master + per-piece; UPS returns a
     * ShipmentIdentificationNumber + PackageResults; DHL similar).
     *
     * <p>Length {@code >= 2} means the shipment was split across
     * multiple carrier calls (over-cap); each entry has its own master.
     */
    private List<ShipmentBatchDTO> shipmentBatches;
    private List<OrderLineDTO> orderLines;
}
