package com.multiship.backend.service.carriers.usps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * USPS v3 customs-form envelope attached to an international label
 * request.
 *
 * <p>Wire shape mirrors the {@code customsForm} block USPS documents
 * on {@code POST /international-labels/v3/label}. Field names match
 * USPS's vocabulary so the connector serialises the object straight
 * onto the wire.
 *
 * <p>Built from our carrier-neutral {@link com.multiship.backend.dto.IntlShipmentBlockDTO}
 * by {@link com.multiship.backend.service.carriers.usps.UspsCustomsFormBuilder} —
 * that helper is the only place USPS's enum vocabulary (MERCHANDISE /
 * GIFT / …) is resolved from our internal enum (SALE / GIFT / …).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsCustomsForm {

    /**
     * Content type. One of MERCHANDISE | GIFT | DOCUMENTS |
     * RETURNED_GOODS | SAMPLE | HUMANITARIAN | OTHER. Required by USPS.
     */
    private String contentType;

    /**
     * Free-form description of the shipment's contents. Optional. USPS
     * echoes this onto CN22/CN23.
     */
    private String contentComments;

    /**
     * Restriction on the contents. USPS enum: NONE | QUARANTINE |
     * SANITARY_PHYTOSANITARY_INSPECTION | OTHER. Defaults to NONE when
     * our block doesn't specify.
     */
    private String restriction;

    /**
     * What USPS should do if the parcel can't be delivered. USPS enum:
     * RETURN | ABANDON | REDIRECT. Defaults to RETURN (safer than
     * ABANDON).
     */
    private String nonDeliveryOption;

    /**
     * Optional export certificate number (some corridors require one).
     */
    private String certificateNumber;

    /**
     * Optional export licence number.
     */
    private String licenseNumber;

    /**
     * Optional commercial invoice reference.
     */
    private String invoiceNumber;

    /**
     * PR-F3 — external invoice reference used by the {@link
     * com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy#INVOICE_REFERENCE}
     * strategy. When the shipment has more commodities than physically fit
     * on the customs form (USPS' ~30-line CN23/PS-2976-A ceiling), the
     * form's {@link #commodities} list is collapsed to a single summary
     * line that points at this invoice number, and the operator
     * physically attaches the full itemized commercial invoice to the
     * parcel. Null under the {@link
     * com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy#SPLIT}
     * strategy (default) — every commodity is accounted for on separate
     * customs forms.
     *
     * <p><b>REGULATORY_REFERENCE.</b> The "see attached invoice"
     * convention is documented in USPS Publication 52 §12.4 (Hazardous,
     * Restricted, and Perishable Mail) and the eVS integrator guide.
     * Also see WCO Kyoto Convention Specific Annex J, Chapter 2 on
     * simplified customs declarations backed by a commercial invoice.
     */
    private String invoiceReference;

    /**
     * Per-commodity lines. Never null — an empty shipment is still a
     * legal customs form (USPS returns 400 for empty, but the builder
     * itself doesn't guard against that; the boundary check in the
     * connector does).
     */
    @Builder.Default
    private List<UspsCommodity> commodities = new ArrayList<>();
}
