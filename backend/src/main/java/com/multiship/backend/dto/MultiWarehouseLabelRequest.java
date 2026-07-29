package com.multiship.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sprint 47 — request for the split-shipment endpoint. Carries the shared
 * shipment header (client, recipient, carrier hint, packaging …) once,
 * plus a list of {@link LineItem}s each tagged with a {@code warehouseCode}.
 *
 * <p>The service groups lines by {@code warehouseCode} and generates one
 * child label per group. When every line names the same warehouse the
 * split degenerates to a single label — still routed through this
 * endpoint for a uniform response shape.
 */
@Data
public class MultiWarehouseLabelRequest {

    /** Owning client — required (this flow is always tenant-scoped). */
    private String clientCode;

    /** Optional parent Order this split was generated from. Recorded on
     *  the ShipmentGroup for traceability. */
    private Integer orderNo;

    /** Recipient (shared across every child shipment). */
    private ManualShipmentRequest.Address recipient;

    /** Carrier code hint (optional — each child shipment may resolve
     *  differently if routing rules fire). */
    private String carrierCode;

    /** Service id hint (optional — same routing-rule caveat). */
    private Long serviceId;

    /** Bill-to account. */
    private Long accountId;
    private String accountNumber;

    /** Shared package preset (optional — override per-line via
     *  {@link LineItem#getPackagePresetId()}). */
    private Long packagePresetId;

    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    private String dimUnit;

    /** Fallback per-shipment weight when a line doesn't carry one. */
    private BigDecimal weight;
    private String weightUnit;

    /** Optional shipment-wide declared value split evenly across children
     *  when the caller doesn't set it per line. */
    private BigDecimal declaredValue;
    private String currency;

    private String goodsDescription;
    private String reference;

    /** Where the shipment came from (MANUAL / WMS / API / ERP). Stored on
     *  each child for reporting. */
    private String source;

    /** International-only fields — replicated on every child. */
    private String incoterms;
    private String reasonForExport;
    private java.util.Map<String, Object> importer;
    private java.util.Map<String, Object> broker;
    private DangerousGoodsBlockDTO dangerousGoods;

    /** Signature + insurance carry through to every child. */
    private String signatureOption;
    private BigDecimal insuredValue;
    private String insuredValueCurrency;

    /** Lines to split. Empty / null = validation error at the service. */
    private List<LineItem> lines;

    @Data
    public static class LineItem {
        /** Warehouse this line ships FROM. Required — this is the split key. */
        private String warehouseCode;
        private String itemNo;
        private String description;
        private Integer quantity;
        private BigDecimal unitValue;
        private BigDecimal weight;
        private String weightUnit;
        private String hsCode;
        private String countryOfOrigin;
        /** Optional per-line packaging override. */
        private Long packagePresetId;
    }
}
