package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentRequestDTO {

    @NotBlank
    private String carrierCode;

    private String accountNumber;

    @NotBlank
    private String serviceType;

    @NotBlank
    private String packageType;

    @NotNull
    @Positive
    private BigDecimal weight;

    @Positive
    private BigDecimal length;

    @Positive
    private BigDecimal width;

    @Positive
    private BigDecimal height;

    @NotBlank
    private String shipperName;

    @NotBlank
    private String shipperPhone;

    @NotBlank
    private String shipperAddressLine1;

    private String shipperAddressLine2;

    @NotBlank
    private String shipperCity;

    @NotBlank
    private String shipperState;

    @NotBlank
    private String shipperPostalCode;

    @NotBlank
    private String shipperCountryCode;

    @NotBlank
    private String recipientName;

    @NotBlank
    private String recipientPhone;

    @NotBlank
    private String recipientAddressLine1;

    private String recipientAddressLine2;

    /**
     * Third recipient address line — needed for JP / CN / IN and other
     * countries where the postal format routinely spans three street lines
     * (building, apartment, district). Optional; null on the vast majority
     * of shipments. Carriers that only accept two lines (SWSIM Address1 /
     * Address2) will concatenate lines 2 + 3 with a space.
     */
    private String recipientAddressLine3;

    @NotBlank
    private String recipientCity;

    @NotBlank
    private String recipientState;

    @NotBlank
    private String recipientPostalCode;

    @NotBlank
    private String recipientCountryCode;

    private String referenceNumber;
    private String specialInstructions;
    private BigDecimal declaredValue;

    /**
     * ISO-4217 currency for {@link #declaredValue}. Also the default currency
     * when {@link #intl} has commodities without their own currency. Nullable
     * for backwards compat — callers that don't set it get USD downstream,
     * mirroring the pre-existing FedEx hardcoded default. New callers should
     * always populate this (Sprint 1 populator does, from OrderCustoms.currency).
     */
    private String declaredValueCurrency;

    /**
     * Unit for {@link #weight}. LB | KG. Null means "assume LB" so legacy
     * callers still work. Connectors that speak the unit natively (UPS,
     * FedEx) pass it as-is; USPS via SWSIM must convert to ounces.
     */
    private String weightUnit;

    /**
     * Unit for {@link #length}, {@link #width}, {@link #height}. IN | CM.
     * Null means "assume IN". Same conversion rules as weightUnit.
     */
    private String dimUnit;

    /**
     * International customs / importer / broker / duties block. Null on
     * domestic shipments. When non-null, connectors are expected to add the
     * customs declaration to their carrier payload; Sprint 1 populates it but
     * connectors haven't wired it up yet (see feature/intl-sprint-N branches).
     */
    private IntlShipmentBlockDTO intl;

    /**
     * True when the recipient address is a residence (not a business).
     * Affects UPS ({@code Address.ResidentialAddressIndicator}) and FedEx
     * ({@code contact.residential}) — both carriers charge a residential
     * delivery surcharge on international shipments. Sending the flag
     * upfront eliminates the back-bill; not sending defaults to commercial
     * rating and lets the carrier reclassify at delivery.
     *
     * <p>Null = "unknown, use carrier default" (both UPS and FedEx default to
     * commercial when the flag is absent).
     */
    private Boolean recipientResidential;

    /**
     * ISO country dial code without the plus, e.g. {@code "1"} for US,
     * {@code "44"} for GB, {@code "91"} for IN. When present, connectors
     * prepend it to {@link #recipientPhone} before sending to the carrier.
     * Null keeps the legacy behaviour (phone sent unchanged).
     */
    private String recipientPhoneCountryCode;

    /**
     * True when this is a RETURN label — the recipient (customer) is
     * shipping the parcel back to the shipper (retailer / 3PL). Each
     * carrier has its own wire-format switch:
     * <ul>
     *   <li>UPS: {@code ShipmentServiceOptions.LabelDelivery.EMailMessage}
     *       + {@code ShipmentCharge.Type=01/BillReceiver} isn't required;
     *       we set {@code ReturnService.Code} on the shipment.</li>
     *   <li>FedEx: {@code requestedShipment.pickupType=USE_SCHEDULED_PICKUP}
     *       + {@code returnedShipmentDetail.returnType=PRINT_RETURN_LABEL}.</li>
     *   <li>USPS/Stamps SWSIM: {@code CreateIndicium.IsReturnLabel=true}
     *       on the Rate block.</li>
     *   <li>DHL Express: {@code accounts[].typeCode=receiver} +
     *       {@code outputImageProperties.imageOptions[].typeCode=label}
     *       (DHL treats it as any other label when the sender/receiver
     *       are swapped; the flag primarily controls billing).</li>
     * </ul>
     * Null is treated as false — existing callers keep printing normal
     * outbound labels.
     */
    private Boolean isReturn;

    /**
     * Optional dangerous goods declaration. When present + ready for
     * carrier (see {@link DangerousGoodsBlockDTO#isReadyForCarrier}),
     * connectors emit the carrier-specific hazmat wire format on top of
     * the normal payload. Domestic-non-hazmat shipments leave this null.
     *
     * <p>Sprint 26 wires this into the UPS connector; FedEx / DHL / USPS
     * land in Sprint 27.
     */
    private DangerousGoodsBlockDTO dangerousGoods;

    /**
     * Multi-package shipment — one entry per physical box. When populated
     * connectors iterate this list to build their carrier-specific
     * packages array; when null / empty the connectors fall back to the
     * top-level {@code weight}, {@code length}, {@code width},
     * {@code height}, {@code weightUnit}, {@code dimUnit}, {@code packageType},
     * {@code declaredValue} fields — see {@link #effectivePackages()} for
     * the canonicalisation.
     */
    private java.util.List<PackageDetailDTO> packages;

    /**
     * Signature required at delivery — carriers charge extra for a
     * signed proof-of-delivery. Sprint 35.
     * <ul>
     *   <li>{@code null} — carrier default (usually no signature required
     *       on domestic ground, indirect signature on air).</li>
     *   <li>{@code NONE} — explicitly waive signature (may not be allowed
     *       on high-value shipments).</li>
     *   <li>{@code INDIRECT} — anyone at the address can sign; parcel can
     *       be left with a neighbour or on the porch depending on carrier
     *       + location.</li>
     *   <li>{@code DIRECT} — only someone at the delivery address may sign.</li>
     *   <li>{@code ADULT} — 21+ ID required. Higher fee. Some jurisdictions
     *       mandate ADULT signature for alcohol or firearms.</li>
     * </ul>
     * Every carrier that supports signature levels normalises to this
     * enum in the connector; carriers that don't support one of the
     * values downgrade transparently (e.g. USPS domestic ADULT →
     * SignatureConfirmation).
     */
    private String signatureOption;

    /**
     * Insured value for the shipment — dollars beyond the free tier the
     * carrier will refund if the parcel is lost or damaged. Sprint 35.
     * <ul>
     *   <li>{@code null} — no explicit insurance requested; carriers still
     *       provide their free tier ($100 UPS / FedEx / USPS Priority
     *       Ground; DHL varies by product).</li>
     *   <li>&gt; 0 — insured for this amount. UPS + FedEx charge based
     *       on declared value beyond the free tier; USPS + DHL treat this
     *       as a separate insurance rider.</li>
     * </ul>
     * Distinct from {@link #declaredValue} (which is the customs value)
     * — a domestic shipment can have insurance without any customs
     * declaration.
     */
    private java.math.BigDecimal insuredValue;

    /**
     * ISO-4217 for {@link #insuredValue}. Null defaults to
     * {@link #declaredValueCurrency}; if that's also null, defaults to
     * USD at the connector.
     */
    private String insuredValueCurrency;

    /**
     * F6-E — IANA timezone ID of the shipper's warehouse (e.g.
     * {@code "America/New_York"}, {@code "Europe/London"},
     * {@code "Asia/Kolkata"}). Populated by
     * {@link com.multiship.backend.service.ShipmentDefaultsResolver} from the
     * request/client chain; consumed by every connector when it stamps a
     * ship/invoice date on the outgoing envelope. Pre-F6-E each connector
     * used {@code ZoneOffset.UTC} which produced dates a day off for shippers
     * printing labels early morning in APAC / late night on the US west coast.
     *
     * <p>Null (unset) → connectors fall back to UTC — the pre-F6-E behavior —
     * so this field is safe to leave blank on legacy callers.
     */
    private String shipperTimezone;

    /**
     * FDX-H2 — FedEx pickupType (drives which driver fleet picks up the
     * label). Populated by
     * {@link com.multiship.backend.service.ShipmentDefaultsResolver} from
     * the precedence chain (request → account → hardcoded
     * {@code USE_SCHEDULED_PICKUP} default). Only FedEx maps this to the
     * wire; UPS / DHL / USPS ignore the field.
     *
     * <p>Values: {@code REGULAR_PICKUP} | {@code REQUEST_COURIER} |
     * {@code DROP_BOX} | {@code BUSINESS_SERVICE_CENTER} | {@code STATION}
     * | {@code USE_SCHEDULED_PICKUP}. Return labels bypass this and always
     * emit {@code CONTACT_FEDEX_TO_SCHEDULE} — the connector's return-
     * label override wins.
     *
     * <p>Null (unset) → connectors fall back to {@code USE_SCHEDULED_PICKUP}
     * so legacy callers see no behavior change.
     */
    private String pickupType;

    /**
     * Return the packages list connectors should iterate. Guarantees a
     * non-empty list so per-connector code doesn't have to fork on
     * null/empty:
     * <ul>
     *   <li>Non-empty {@link #packages} → returned as-is.</li>
     *   <li>Null or empty → synthesised single-package list mirroring
     *       the top-level fields. Sequence number = 1.</li>
     * </ul>
     */
    public java.util.List<PackageDetailDTO> effectivePackages() {
        if (packages != null && !packages.isEmpty()) return packages;
        PackageDetailDTO synthetic = PackageDetailDTO.builder()
                .sequenceNumber(1)
                .packageType(packageType)
                .weight(weight)
                .weightUnit(weightUnit)
                .length(length).width(width).height(height)
                .dimUnit(dimUnit)
                .declaredValue(declaredValue)
                .description(specialInstructions)
                .reference(referenceNumber)
                .build();
        return java.util.List.of(synthetic);
    }
}
