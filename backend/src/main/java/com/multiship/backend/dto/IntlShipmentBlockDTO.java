package com.multiship.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * International shipment metadata attached to a {@link ShipmentRequestDTO}.
 * Null on domestic shipments (carriers must skip customs sections entirely
 * when this is missing). Populated at label time by merging the order's
 * {@link com.multiship.backend.model.OrderCustoms} with the resolved
 * {@link com.multiship.backend.model.ClientCustomsProfile}: order values win,
 * profile values fill blanks, carrier defaults are the last resort.
 *
 * <p>Carrier translators (UPS Paperless Invoice, FedEx CustomsClearanceDetail,
 * SWSIM CustomsInfo) read directly off this block — no connector should have
 * to re-derive anything from Order + Customs entities.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IntlShipmentBlockDTO {

    /**
     * True iff the shipment crosses a customs boundary. Set once by the
     * populator so connectors never re-derive from country codes (which
     * would miss same-territory pairs like FR→DE inside the EU).
     */
    @Builder.Default
    private Boolean international = true;

    /** DDP | DAP | DDU. Delivered Duty Paid|At Place|Unpaid. */
    private String incoterms;

    /** SALE | GIFT | SAMPLE | RETURN | REPAIR | DOCUMENTS. */
    private String reasonForExport;

    /**
     * F6-C — who pays customs duties. Per-carrier vocabulary passed through
     * verbatim (F6-A locked decision: no normalization layer). Each connector
     * maps the value to its own envelope field:
     *   · UPS: BillShipper vs BillReceiver vs BillThirdParty
     *   · FedEx: paymentType SENDER / RECIPIENT / THIRD_PARTY / COLLECT
     *   · DHL: incoterms1 (DAP / DDP / EXW / …)
     *   · Stamps.com: encoded via ContentDeclaration when the service supports it
     * Populated from {@code CarrierAccountRef.clearanceOption} via
     * {@link ShipmentDefaultsResolver}. Null → connector applies its own
     * carrier default (typically sender-pays / DAP).
     */
    private String clearanceOption;

    /** ISO-4217 (USD, EUR, GBP, ...). Both customs values AND declared value use this. */
    private String customsCurrency;

    /** Sum of commodity line totals; recomputed here so connectors don't sum. */
    private BigDecimal customsTotalValue;

    /** KG | LB — the unit item weights are expressed in. Also the shipment weight unit. */
    private String weightUnit;

    /** IN | CM — the unit dimensions are expressed in. */
    private String dimUnit;

    // ===== Importer of Record =====
    /** BUSINESS | RECEIVER — matches ClientCustomsProfile.importerType semantics. */
    private String importerType;
    private String importerName;
    private String importerContact;
    private String importerCompany;
    private String importerAddressLine1;
    private String importerAddressLine2;
    private String importerCity;
    private String importerState;
    private String importerPostcode;
    private String importerCountry;
    private String importerPhone;
    // Tax identities (per-region)
    private String importerTaxId;
    private String importerTaxIdType;
    private String importerVat;
    private String importerEori;
    private String importerIoss;
    private String importerCompanyReg;
    private String importerIec;
    private String importerGstin;

    // ===== Customs broker =====
    private String brokerName;
    private String brokerCompany;
    private String brokerAddressLine1;
    private String brokerAddressLine2;
    private String brokerCity;
    private String brokerState;
    private String brokerPostcode;
    private String brokerCountry;
    private String brokerPhone;
    private String brokerId;
    private String brokerLicense;

    // ===== Duty payment =====
    /** SENDER | RECIPIENT | THIRD_PARTY. Who the carrier bills duties to. */
    private String dutyBillTo;
    /** Payer's carrier account when dutyBillTo != SENDER. */
    private String dutyAccount;

    // ===== US Export Electronic Export Information (EEI) =====
    /**
     * FTR §30.37 exemption wire code — mutually exclusive with
     * {@link #aesCitation}. One of:
     * <ul>
     *   <li>{@code NO_EEI_30_37_a} — value < $2,500 USD per Schedule B code
     *       (the historical FedEx auto-default; ONLY valid under $2,500).</li>
     *   <li>{@code NO_EEI_30_37_h} — tools of trade / returned goods.</li>
     *   <li>{@code NO_EEI_30_36} — shipments to Canada.</li>
     * </ul>
     * Rendered on the label as {@code "NO EEI 30.37(a)"} etc; wired to
     * FedEx as {@code customsClearanceDetail.exportDetail
     * .foreignTradeStatisticsRegulations.filingCitation}.
     */
    private String ftrExemption;

    /**
     * AES Internal Transaction Number filed with US Census — required (in
     * lieu of {@link #ftrExemption}) on US-origin exports ≥ $2,500 USD per
     * Schedule B code, to any destination other than Canada. Free-form; the
     * connector emits it verbatim to FedEx as {@code
     * customsClearanceDetail.exportDetail.exportComplianceStatement}.
     */
    private String aesCitation;

    /**
     * Generic export declaration reference — the non-US analogue of
     * {@link #aesCitation}. Populated when the origin's regulator issued a
     * reference (CA B13A / GB CDS / EU MRN / AU EDN / JP declaration ID /
     * IN SB number) that must accompany the shipment. Connectors emit it
     * in whichever free-form reference slot their wire format has.
     *
     * <p>Precedence for FedEx's {@code exportComplianceStatement} slot
     * (which the FedEx API accepts as a catch-all for both AES + non-AES
     * export references):
     * <ol>
     *   <li>{@link #aesCitation} (real US AES filing)</li>
     *   <li>{@link #ftrExemption} (mapped to statement text)</li>
     *   <li>{@link #exportDeclarationReference} (verbatim)</li>
     * </ol>
     * ShipmentValidationService emits a WARNING when a high-value intl
     * shipment leaves all three blank — advisory, doesn't block (some
     * corridors legitimately need no reference).
     */
    private String exportDeclarationReference;

    // ===== Commodity lines =====
    // Sprint 52 — defense-in-depth cap at the worst-case documented
    // carrier ceiling (999). CarrierLimitService still enforces the
    // per-carrier cap; the DTO cap protects unauth'd carriers we haven't
    // scoped a row for and blocks pathological payloads at the servlet.
    @Size(max = 999, message = "commodities: at most 999 lines per shipment")
    @Builder.Default
    private List<CustomsCommodityDTO> commodities = new ArrayList<>();

    /**
     * PR-F3 — operator-selected strategy for handling customs forms whose
     * commodity count exceeds a carrier's physical form line limit
     * (currently USPS's CN23/PS-2976-A ~30-line ceiling). Null → carrier
     * applies its own default (USPS_DIRECT defaults to
     * {@link CustomsSplitStrategy#SPLIT}).
     *
     * <p>Not persisted — this is a per-request operator choice made on the
     * /orders/new customs section. The connector reads it, dispatches
     * accordingly, and never writes it back.
     *
     * <p><b>REGULATORY_REFERENCE.</b> USPS customs-form line limits are
     * physical print constraints on CN22 (3-5), CN23 (~15-20), and
     * PS-2976-A (~30). Beyond ~30 lines, USPS Publication 52 §12.4 and
     * the eVS integrator guide document the "see attached invoice"
     * convention — the customs form carries a summary + invoice
     * reference and the operator physically attaches the full itemized
     * commercial invoice. See PR-F3 design notes in
     * {@code docs/usps-direct-integration.md} §PR-F3.
     */
    private CustomsSplitStrategy customsSplitStrategy;

    /**
     * PR-F3 — optional external commercial-invoice reference used when
     * {@link #customsSplitStrategy} is {@link CustomsSplitStrategy#INVOICE_REFERENCE}.
     * When null, the connector generates one on the fly (format:
     * {@code USPS-<orderRef>-<epochMillis>}). When set, the caller-supplied
     * value is used verbatim on both the customs form's summary line + the
     * {@link com.multiship.backend.service.carriers.usps.dto.UspsCustomsForm#getInvoiceReference()}
     * slot USPS surfaces on the printed form.
     */
    private String customsInvoiceReference;

    /**
     * Operator's chosen strategy for handling customs forms that would
     * physically overflow the carrier's printed form (USPS' ~30-line
     * CN23/PS-2976-A ceiling being the primary trigger).
     *
     * <p>See {@link IntlShipmentBlockDTO#getCustomsSplitStrategy()} for
     * regulatory context.
     */
    public enum CustomsSplitStrategy {
        /**
         * Chunk the shipment into ceil(N/cap) sub-parcels, each with
         * ≤cap commodities. Each sub-parcel gets its own label + customs
         * form. Every commodity is accounted for on paper. Costs more
         * (multiple labels, multiple postage charges) but paperwork
         * matches physical reality. Default under USPS_DIRECT.
         */
        SPLIT,

        /**
         * Keep as ONE shipment + ONE label + ONE customs form. The form's
         * commodity list is collapsed to a single summary line that points
         * at an external commercial invoice; the operator physically
         * attaches the full itemization to the parcel. Saves postage but
         * requires operator to hand-attach paperwork.
         */
        INVOICE_REFERENCE
    }

    /** True when the block has enough data to build a valid customs declaration. */
    public boolean isReadyForCarrier() {
        return Boolean.TRUE.equals(international)
                && commodities != null && !commodities.isEmpty()
                && customsCurrency != null && !customsCurrency.isBlank()
                && incoterms != null && !incoterms.isBlank();
    }
}
