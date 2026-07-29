package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Dangerous goods declaration for a shipment. Sprint 26 introduces this
 * block; only UPS wire emission lands in this sprint — FedEx / DHL /
 * USPS follow in Sprint 27.
 *
 * <p>Regulation sets map to how the shipment is being transported:
 * <ul>
 *   <li>IATA — International Air Transport Association DGR. Air freight.</li>
 *   <li>ADR — Accord européen relatif au transport international des
 *       marchandises Dangereuses par Route. EU / UK road.</li>
 *   <li>DOT — US Department of Transportation 49 CFR. US road / ground.</li>
 * </ul>
 * The block is optional on {@link ShipmentRequestDTO}; connectors that
 * see a non-null block emit the carrier-specific hazmat wire format on
 * top of the normal shipment payload.
 *
 * <p>Emergency contact + signatory are IATA/ADR requirements — the
 * declaration is legally invalid without a 24/7 reachable contact and a
 * named signatory. We validate their presence up-front so carriers
 * don't reject the shipment for a preventable reason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DangerousGoodsBlockDTO {

    /** IATA | ADR | DOT — which regulation set governs this shipment. */
    private String regulationSet;

    /**
     * Whether the driver / handler must be able to reach the parcel in
     * transit. ACCESSIBLE for goods that need monitoring (some batteries,
     * cryogenics); INACCESSIBLE for everything else. FedEx exposes this
     * directly; UPS and DHL infer from the class + packing group.
     */
    private String accessibility;

    /**
     * True when the shipment MUST go on a cargo aircraft (no passenger
     * aircraft). IATA Class 1 explosives + some Class 6.1 toxins. FedEx
     * wire format takes the boolean; UPS declines the shipment.
     */
    private Boolean cargoAircraftOnly;

    /** 24/7 emergency contact name — required by IATA/ADR. */
    private String emergencyContactName;

    /** 24/7 emergency contact phone (E.164 or local). */
    private String emergencyContactPhone;

    /**
     * Emergency response information provider — a service like CHEMTREC
     * (US) or NCEC (UK) that responds to emergencies referencing this
     * shipment. Contract number goes here. Optional.
     */
    private String emergencyResponseContract;

    /** Legal signatory who accepts responsibility for the declaration. */
    private String signatoryName;

    /** Signatory's title / role. */
    private String signatoryTitle;

    /** One or more dangerous commodities. Empty list is invalid — the
     *  validator rejects it. */
    private List<DangerousCommodityDTO> commodities;

    /** True when {@link #commodities} is non-empty AND all required
     *  fields for wire emission are populated. Connectors check this
     *  before emitting the block. */
    public boolean isReadyForCarrier() {
        return commodities != null && !commodities.isEmpty()
                && regulationSet != null && !regulationSet.isBlank()
                && emergencyContactName != null && !emergencyContactName.isBlank()
                && emergencyContactPhone != null && !emergencyContactPhone.isBlank()
                && signatoryName != null && !signatoryName.isBlank();
    }
}
