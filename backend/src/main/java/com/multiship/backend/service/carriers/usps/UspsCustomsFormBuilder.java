package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO.CustomsSplitStrategy;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.dto.UspsCommodity;
import com.multiship.backend.service.carriers.usps.dto.UspsCustomsForm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *
 * <p><b>PR-F3 — INVOICE_REFERENCE strategy.</b> When the caller passes
 * {@link CustomsSplitStrategy#INVOICE_REFERENCE}, the builder collapses
 * the customs form's {@link UspsCustomsForm#getCommodities()} list to a
 * single summary line and stamps an invoice reference. Documented USPS
 * convention for shipments with more items than the printed form
 * physically fits — see {@link UspsCustomsForm#getInvoiceReference()}
 * javadoc for the regulatory citation (USPS Publication 52 §12.4).
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
     * USPS catch-all HS tariff number used as the summary line's HS on
     * the {@link CustomsSplitStrategy#INVOICE_REFERENCE} path when no
     * majority HS-6 emerges from the input commodities (e.g. all inputs
     * have blank HS codes). "999900" is the WCO-standard "other" bucket
     * USPS accepts as a last-resort declaration; keeps the summary line
     * numerically valid without misclassifying the goods.
     */
    static final String FALLBACK_HS_TARIFF = "999900";

    /**
     * Build a {@link UspsCustomsForm} from a request that carries an
     * {@link IntlShipmentBlockDTO}. When {@code request.intl} is null
     * this returns null — the connector's boundary guard makes that a
     * hard error before ever reaching the builder, so returning null
     * here just documents the "no block, no form" invariant.
     *
     * <p>Dispatches on {@link IntlShipmentBlockDTO#getCustomsSplitStrategy()}:
     * SPLIT (or null) → one commodity per line (existing behaviour);
     * INVOICE_REFERENCE → single summary line + invoice reference.
     */
    public UspsCustomsForm build(ShipmentRequestDTO request) {
        if (request == null || request.getIntl() == null) return null;
        CustomsSplitStrategy strategy = request.getIntl().getCustomsSplitStrategy();
        return build(request, strategy);
    }

    /**
     * Strategy-aware overload — lets callers force a strategy without
     * mutating the request DTO (used by
     * {@link com.multiship.backend.service.carriers.UspsDirectConnector} on
     * the intl branch when it wants to override the request's stored
     * strategy for a specific label call).
     */
    public UspsCustomsForm build(ShipmentRequestDTO request, CustomsSplitStrategy strategy) {
        if (request == null || request.getIntl() == null) return null;
        IntlShipmentBlockDTO intl = request.getIntl();
        if (strategy == CustomsSplitStrategy.INVOICE_REFERENCE) {
            return buildInvoiceReferenceForm(request, intl);
        }
        // Default / SPLIT — existing behaviour (one commodity per line).
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
     * INVOICE_REFERENCE mode — replaces the per-commodity list with a
     * single summary line + stamps invoiceReference + contentComments.
     * See {@link CustomsSplitStrategy#INVOICE_REFERENCE} for the
     * regulatory rationale.
     */
    private UspsCustomsForm buildInvoiceReferenceForm(ShipmentRequestDTO request,
                                                      IntlShipmentBlockDTO intl) {
        List<CustomsCommodityDTO> commodities = intl.getCommodities();
        int lineCount = commodities == null ? 0 : commodities.size();

        // Compute the summary aggregates.
        BigDecimal totalValue = totalDeclaredValue(commodities);
        int totalQuantity = totalQuantity(commodities);
        BigDecimal totalWeight = totalWeight(commodities);
        String majorityHs = pickMajorityHs(commodities);
        String majorityOrigin = pickMajorityOrigin(commodities);
        String currency = firstNonBlank(intl.getCustomsCurrency(), "");

        // Resolve the invoice reference — caller-supplied wins, else generate.
        String invoiceRef = firstNonBlank(
                intl.getCustomsInvoiceReference(),
                generateInvoiceRef(request));

        // Summary description: mirrors USPS Publication 52 §12.4 wording.
        String summaryDescription = String.format(
                "See attached invoice — %d line items, total %s %s",
                lineCount,
                totalValue == null ? "0.00" : totalValue.toPlainString(),
                currency);

        String contentComments = String.format(
                "See attached invoice for full itemization. Reference: %s.",
                invoiceRef);

        UspsCommodity summaryLine = UspsCommodity.builder()
                .description(summaryDescription)
                .quantity(totalQuantity > 0 ? totalQuantity : null)
                .weight(totalWeight != null && totalWeight.signum() > 0 ? totalWeight : null)
                .value(totalValue != null && totalValue.signum() > 0 ? totalValue : null)
                .hsTariffNumber(majorityHs)
                .countryOfOrigin(majorityOrigin)
                .build();

        List<UspsCommodity> singleLine = new ArrayList<>();
        singleLine.add(summaryLine);

        return UspsCustomsForm.builder()
                .contentType(mapContentType(intl.getReasonForExport()))
                .restriction(DEFAULT_RESTRICTION)
                .nonDeliveryOption(DEFAULT_NON_DELIVERY)
                .commodities(singleLine)
                .contentComments(contentComments)
                .invoiceReference(invoiceRef)
                .build();
    }

    /**
     * Generate a synthetic invoice reference for callers who didn't
     * supply one. Format: {@code USPS-<orderRef>-<epochMillis>}. Falls
     * back to "UNKNOWN" if the request has no reference number.
     */
    private static String generateInvoiceRef(ShipmentRequestDTO request) {
        String orderRef = request != null && StringUtils.hasText(request.getReferenceNumber())
                ? request.getReferenceNumber() : "UNKNOWN";
        return "USPS-" + orderRef + "-" + System.currentTimeMillis();
    }

    /** Sum of quantity × unitValue across all commodities (BigDecimal-safe). */
    static BigDecimal totalDeclaredValue(List<CustomsCommodityDTO> commodities) {
        if (commodities == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CustomsCommodityDTO c : commodities) {
            if (c == null || c.getUnitValue() == null) continue;
            BigDecimal qty = c.getQuantity() != null
                    ? BigDecimal.valueOf(c.getQuantity()) : BigDecimal.ONE;
            sum = sum.add(c.getUnitValue().multiply(qty));
        }
        return sum;
    }

    /** Sum of raw quantities (Integer-safe; null quantities skipped). */
    static int totalQuantity(List<CustomsCommodityDTO> commodities) {
        if (commodities == null) return 0;
        int total = 0;
        for (CustomsCommodityDTO c : commodities) {
            if (c != null && c.getQuantity() != null) total += c.getQuantity();
        }
        return total;
    }

    /**
     * Sum of unitWeight × quantity across all commodities. Preserves the
     * caller's weight unit — the block's {@code weightUnit} (LB/KG) is
     * meaningless here; USPS receives the number and units in a sibling
     * field on the parent shipment.
     */
    static BigDecimal totalWeight(List<CustomsCommodityDTO> commodities) {
        if (commodities == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CustomsCommodityDTO c : commodities) {
            if (c == null || c.getUnitWeight() == null) continue;
            BigDecimal qty = c.getQuantity() != null
                    ? BigDecimal.valueOf(c.getQuantity()) : BigDecimal.ONE;
            sum = sum.add(c.getUnitWeight().multiply(qty));
        }
        return sum;
    }

    /**
     * Pick the most common HS-6 across the input list. Ties broken by
     * insertion order (first-wins). Returns {@link #FALLBACK_HS_TARIFF}
     * when no input has a normalisable HS code.
     */
    static String pickMajorityHs(List<CustomsCommodityDTO> commodities) {
        if (commodities == null || commodities.isEmpty()) return FALLBACK_HS_TARIFF;
        Map<String, Integer> counts = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (CustomsCommodityDTO c : commodities) {
            if (c == null) continue;
            String hs = normaliseHs(c.getHsCode());
            if (!StringUtils.hasText(hs)) continue;
            String hs6 = hs.length() >= 6 ? hs.substring(0, 6) : hs;
            if (!counts.containsKey(hs6)) order.add(hs6);
            counts.merge(hs6, 1, Integer::sum);
        }
        if (counts.isEmpty()) return FALLBACK_HS_TARIFF;
        String bestKey = order.get(0);
        int bestCount = counts.get(bestKey);
        for (String key : order) {
            int cnt = counts.get(key);
            if (cnt > bestCount) {
                bestKey = key;
                bestCount = cnt;
            }
        }
        return bestKey;
    }

    /**
     * Pick the most common ISO alpha-2 country of origin across the
     * input list. Ties broken by insertion order (first-wins). Returns
     * null when no input has a country of origin.
     */
    static String pickMajorityOrigin(List<CustomsCommodityDTO> commodities) {
        if (commodities == null || commodities.isEmpty()) return null;
        Map<String, Integer> counts = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (CustomsCommodityDTO c : commodities) {
            if (c == null) continue;
            String coo = c.getCountryOfOrigin();
            if (!StringUtils.hasText(coo)) continue;
            String key = coo.trim().toUpperCase(Locale.ROOT);
            if (!counts.containsKey(key)) order.add(key);
            counts.merge(key, 1, Integer::sum);
        }
        if (counts.isEmpty()) return null;
        String bestKey = order.get(0);
        int bestCount = counts.get(bestKey);
        for (String key : order) {
            int cnt = counts.get(key);
            if (cnt > bestCount) {
                bestKey = key;
                bestCount = cnt;
            }
        }
        return bestKey;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return "";
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
