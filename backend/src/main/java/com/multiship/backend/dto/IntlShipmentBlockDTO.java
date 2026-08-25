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
@Builder
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

    // ===== Commodity lines =====
    // Sprint 52 — defense-in-depth cap at the worst-case documented
    // carrier ceiling (999). CarrierLimitService still enforces the
    // per-carrier cap; the DTO cap protects unauth'd carriers we haven't
    // scoped a row for and blocks pathological payloads at the servlet.
    @Size(max = 999, message = "commodities: at most 999 lines per shipment")
    @Builder.Default
    private List<CustomsCommodityDTO> commodities = new ArrayList<>();

    /** True when the block has enough data to build a valid customs declaration. */
    public boolean isReadyForCarrier() {
        return Boolean.TRUE.equals(international)
                && commodities != null && !commodities.isEmpty()
                && customsCurrency != null && !customsCurrency.isBlank()
                && incoterms != null && !incoterms.isBlank();
    }
}
