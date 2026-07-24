package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Result of creating a shipment via the public API. */
@Data
@Builder
public class ExternalShipmentResponse {
    private Long shipmentId;
    private String reference;
    private String clientCode;
    private String carrier;
    private String service;
    private String resolvedVia;
    private boolean international;

    private String trackingNumber;
    private String trackingUrl;
    private String labelUrl;
    private String labelPdf;
    private String labelDocumentUrl;
    /** Kept for back-compat — same value as {@link #billableAmount}. */
    private BigDecimal shippingCost;
    private LocalDateTime estimatedDelivery;
    private String status;

    /**
     * The unmodified rate the carrier billed for this shipment. Distinct
     * from {@link #billableAmount}, which is the client's rebill amount.
     */
    private BigDecimal carrierAmount;

    /** {@link #carrierAmount} + client markup, rounded per shipment. */
    private BigDecimal billableAmount;

    /** PERCENT | FLAT — the markup config effective at label time. */
    private String markupKind;

    /** Value used to compute {@link #billableAmount}; snapshotted for stability. */
    private BigDecimal markupValue;

    /** ISO-4217 of the rebill amount. */
    private String markupCurrency;

    /**
     * True when the shipment was created past the client's local cutoff.
     * The WMS should treat this as "picked up next business day" — the label
     * is still valid; only the physical dispatch shifts.
     */
    private Boolean dispatchNextBusinessDay;

    /** Warehouse the shipment ships from — null when the client has no attached warehouses and the legacy shipFrom was used. */
    private String warehouseCode;
}
