package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.dto.UspsCommodity;
import com.multiship.backend.service.carriers.usps.dto.UspsCustomsForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates our carrier-neutral {@link IntlShipmentBlockDTO} +
 * {@link CustomsCommodityDTO} lines into the USPS v3
 * {@link UspsCustomsForm} shape the international-labels endpoint
 * consumes.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Map our {@code reasonForExport} vocabulary (SALE / GIFT / SAMPLE /
 *       RETURN / REPAIR / DOCUMENTS) to USPS's contentType vocabulary
 *       (MERCHANDISE / GIFT / …).</li>
 *   <li>Normalise HS-6 tariff codes — strip whitespace + dashes, keep
 *       digits only, first 6 minimum. Left-pad guard so a "0851" doesn't
 *       masquerade as 6-digit compliant.</li>
 *   <li>Copy per-commodity fields (description, qty, weight, value,
 *       countryOfOrigin, unitOfMeasure) verbatim — no coercion.</li>
 *   <li>Omit optional fields when blank, rather than emit empty strings
 *       (USPS rejects some empty strings but accepts absent keys).</li>
 *   <li>Log WARN when it encounters an unknown enum value and fall back
 *       to a safe default (MERCHANDISE for contentType, NONE for
 *       restriction, RETURN for nonDeliveryOption). Never throws — the
 *       connector-side boundary guard is the enforcement point.</li>
 * </ul>
 *
 * <p>Package-private components sometimes prefer a static helper, but
 * this one is a Spring {@link Component} so it can be injected + spied
 * in unit tests and future extensions (per-locale contentType maps, etc.)
 * don't force a global rewrite.
 *
 * <p><b>REGULATORY_REFERENCE.</b> HS-6 normalisation is a defence-in-depth
 * layer for the 2025-09-01 USPS International HS-6 Tariff Mandate. See
 * {@link UspsCommodity} for the primary reference; the connector's
 * boundary guard raises the actionable {@code IllegalArgumentException}
 * when a commodity's normalised HS code is shorter than 6 digits.
 */
@Slf4j
@Component
public class UspsCustomsFormBuilder {

    /** USPS content-type default when our reason-for-export is null or unmapped. */
    static final String DEFAULT_CONTENT_TYPE = "MERCHANDISE";

    /** USPS restriction default when our block doesn't set one. */
    static final String DEFAULT_RESTRICTION = "NONE";

    /** USPS non-delivery default — RETURN is safer than ABANDON. */
    static final String DEFAULT_NON_DELIVERY = "RETURN";

    /**
     * Build a {@link UspsCustomsForm} from a request that carries an
     * {@link IntlShipmentBlockDTO}. When {@code request.intl} is null
     * this returns null — the connector's boundary guard makes that a
     * hard error before ever reaching the builder, so returning null
     * here just documents the "no block, no form" invariant.
     */
    public UspsCustomsForm build(ShipmentRequestDTO request) {
        if (request == null || request.getIntl() == null) return null;
        IntlShipmentBlockDTO intl = request.getIntl();
        UspsCustomsForm.UspsCustomsFormBuilder out = UspsCustomsForm.builder()
                .contentType(mapContentType(intl.getReasonForExport()))
                .restriction(DEFAULT_RESTRICTION)
                .nonDeliveryOption(DEFAULT_NON_DELIVERY)
                .commodities(mapCommodities(intl));

        String contentComments = firstCommodityDescription(intl);
        if (StringUtils.hasText(contentComments)) {
            out.contentComments(contentComments);
        }
        // certificateNumber / licenseNumber / invoiceNumber are optional
        // pass-throughs. IntlShipmentBlockDTO doesn't carry them yet — a
        // follow-up PR can wire them via new fields. For now leave nulls
        // so the map serializer omits them entirely.
        return out.build();
    }

    /**
     * Map our reason-for-export vocabulary to USPS contentType. Unknown
     * values → {@link #DEFAULT_CONTENT_TYPE} with a WARN log so the
     * mapping drift is visible in prod logs before it drives a support
     * ticket.
     */
    static String mapContentType(String reasonForExport) {
        if (!StringUtils.hasText(reasonForExport)) return DEFAULT_CONTENT_TYPE;
        String v = reasonForExport.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "SALE":
            case "MERCHANDISE":
                return "MERCHANDISE";
            case "GIFT":
                return "GIFT";
            case "SAMPLE":
                return "SAMPLE";
            case "RETURN":
            case "RETURNED_GOODS":
                return "RETURNED_GOODS";
            case "DOCUMENTS":
                return "DOCUMENTS";
            case "REPAIR":
            case "HUMANITARIAN":
                return "HUMANITARIAN";
            case "OTHER":
                return "OTHER";
            default:
                log.warn("USPS customs form: unknown reasonForExport '{}' — defaulting to {}.",
                        reasonForExport, DEFAULT_CONTENT_TYPE);
                return DEFAULT_CONTENT_TYPE;
        }
    }

    /**
     * Map our restriction vocabulary (if we ever surface one on
     * {@link IntlShipmentBlockDTO}) to USPS's enum. Currently no such
     * field exists on our DTO, so this always returns
     * {@link #DEFAULT_RESTRICTION}. Kept as a distinct method so a
     * future field addition doesn't need a full rewrite.
     */
    static String mapRestriction(String restriction) {
        if (!StringUtils.hasText(restriction)) return DEFAULT_RESTRICTION;
        String v = restriction.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "NONE":
                return "NONE";
            case "QUARANTINE":
                return "QUARANTINE";
            case "SANITARY_PHYTOSANITARY_INSPECTION":
            case "SPS":
                return "SANITARY_PHYTOSANITARY_INSPECTION";
            case "OTHER":
                return "OTHER";
            default:
                log.warn("USPS customs form: unknown restriction '{}' — defaulting to {}.",
                        restriction, DEFAULT_RESTRICTION);
                return DEFAULT_RESTRICTION;
        }
    }

    /**
     * Map our non-delivery vocabulary to USPS's enum. Defaults to
     * {@link #DEFAULT_NON_DELIVERY} (RETURN — safer than ABANDON).
     */
    static String mapNonDelivery(String nonDelivery) {
        if (!StringUtils.hasText(nonDelivery)) return DEFAULT_NON_DELIVERY;
        String v = nonDelivery.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "RETURN":
            case "RETURN_TO_SENDER":
                return "RETURN";
            case "ABANDON":
                return "ABANDON";
            case "REDIRECT":
                return "REDIRECT";
            default:
                log.warn("USPS customs form: unknown nonDeliveryOption '{}' — defaulting to {}.",
                        nonDelivery, DEFAULT_NON_DELIVERY);
                return DEFAULT_NON_DELIVERY;
        }
    }

    /**
     * Normalise an HS tariff number for USPS. Strips whitespace, dashes
     * and dots; returns just the leading digits. Callers (the connector
     * boundary guard) enforce the 6-digit minimum + all-numeric rule.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "6109.10.00"} → {@code "6109100"}, first 6 = {@code "610910"}</li>
     *   <li>{@code "6109-10"} → {@code "610910"}</li>
     *   <li>{@code "  6109 10  "} → {@code "610910"}</li>
     *   <li>{@code "AB123"} → {@code "123"} (alpha stripped — guard then rejects)</li>
     * </ul>
     */
    public static String normaliseHs(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private List<UspsCommodity> mapCommodities(IntlShipmentBlockDTO intl) {
        List<UspsCommodity> out = new ArrayList<>();
        if (intl.getCommodities() == null) return out;
        for (CustomsCommodityDTO c : intl.getCommodities()) {
            if (c == null) continue;
            UspsCommodity.UspsCommodityBuilder b = UspsCommodity.builder();
            if (StringUtils.hasText(c.getDescription())) {
                b.description(c.getDescription());
            }
            if (c.getQuantity() != null) {
                b.quantity(c.getQuantity());
            }
            if (c.getUnitWeight() != null) {
                b.weight(c.getUnitWeight());
            }
            if (c.getUnitValue() != null) {
                b.value(c.getUnitValue());
            }
            String hs = normaliseHs(c.getHsCode());
            if (StringUtils.hasText(hs)) {
                b.hsTariffNumber(hs);
            }
            if (StringUtils.hasText(c.getCountryOfOrigin())) {
                b.countryOfOrigin(c.getCountryOfOrigin().trim().toUpperCase(Locale.ROOT));
            }
            // unitOfMeasure isn't currently on CustomsCommodityDTO — leave
            // null so serialisation omits the field. Follow-up PR can add
            // it as a new column on OrderCustomsItem.
            out.add(b.build());
        }
        return out;
    }

    private static String firstCommodityDescription(IntlShipmentBlockDTO intl) {
        if (intl.getCommodities() == null || intl.getCommodities().isEmpty()) return null;
        for (CustomsCommodityDTO c : intl.getCommodities()) {
            if (c != null && StringUtils.hasText(c.getDescription())) return c.getDescription();
        }
        return null;
    }
}
