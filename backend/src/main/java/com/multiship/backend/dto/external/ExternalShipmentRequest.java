package com.multiship.backend.dto.external;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public "create shipment" request. The client is taken from the API key, never
 * the body. Carrier + service resolve from {@code shipMethod} (a ship-method
 * code) via the platform's ship-method rules; {@code carrierCode} overrides.
 */
@Data
public class ExternalShipmentRequest {

    /** The caller's own order/shipment reference (prints on the label). */
    private String reference;

    /**
     * The client to ship on behalf of. Required for a platform-wide (WMS) key;
     * for a client-bound key it may be omitted — if sent, it must match the key.
     */
    private String clientCode;

    /** Ship-method code that resolves to carrier + service (e.g. F77, P01). */
    private String shipMethod;

    /** Optional explicit carrier (UPS/FEDEX/USPS) — overrides shipMethod resolution. */
    private String carrierCode;

    /** Optional bill-to account override; defaults to the client's account for the carrier. */
    private String accountNumber;

    /** Ship-from. Optional — defaults to the client's configured ship-from, then the warehouse default. */
    private ExternalAddress shipFrom;

    /** Ship-to. Required. */
    private ExternalAddress shipTo;

    /** Parcel weight + dimensions. Weight required. */
    private ExternalParcel parcel;

    /** Commercial-invoice line items (required for cross-border shipments). */
    private List<ExternalItem> items;

    private BigDecimal declaredValue;
    private String currency;
    /** SALE | GIFT | SAMPLE | RETURN | REPAIR. */
    private String reasonForExport;
    /** DAP | DDP. */
    private String incoterms;

    private Boolean isReturn;
}
