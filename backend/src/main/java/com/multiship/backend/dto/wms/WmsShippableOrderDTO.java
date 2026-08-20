package com.multiship.backend.dto.wms;

import lombok.Data;

/**
 * One "shippable" order as returned by the external WMS's shippable-orders
 * endpoint. This is the wire shape we expect back from the WMS; the field
 * names can be re-mapped once the real WMS payload is known.
 *
 * <p>A shippable order = the WMS considers it ready to ship (picked/packed).
 * We import it as a PENDING {@link com.multiship.backend.model.Order} with
 * {@code source = WMS}; the operator (or auto-flow) then generates the label.
 */
@Data
public class WmsShippableOrderDTO {

    /** Stable id of the order in the WMS — used for idempotent re-pulls. */
    private String externalId;

    /** Client/customer code this order belongs to (maps to Order.custNo). */
    private String clientCode;

    // ── recipient / ship-to ────────────────────────────────────────────────
    private String recipientName;
    private String recipientCompany;
    private String recipientPhone;
    private String recipientEmail;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String countryCode;

    // ── shipment hints (optional; the operator can still change them) ───────
    private String carrierCode;
    private String serviceType;
    private Double weight;
    private String weightUnit;

    /** WMS/customer reference (PO #, order #) for cross-referencing. */
    private String reference;
}
