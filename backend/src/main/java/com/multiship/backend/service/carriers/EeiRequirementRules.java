package com.multiship.backend.service.carriers;

import java.util.Locale;
import java.util.Set;

/**
 * Pre-flight US Electronic Export Information (EEI) coherence check.
 *
 * <p>Motivation — Batch #6 post-mortem (2026-09-12): 14 orders in that
 * batch failed with FedEx {@code SHIPMENTVALIDATION.EEIEDIT.ERROR "The
 * FTR Exemption or AES Citation you provided is not valid for EEI"}.
 * All 14 shipped US → China. FedEx demands EEI on shipments to certain
 * strategic-country destinations regardless of the $2,500 monetary
 * threshold (BIS EAR + Census rules), so the FTR exemption / AES ITN
 * fields must be populated — but the bulk-import DTO doesn't carry them,
 * so every such row was doomed before it reached the carrier.
 *
 * <p>This check flags those rows at import-validate time so the operator
 * can either (a) create the affected orders through the manual
 * {@code /orders/new} flow that DOES surface the FTR/AES fields, or
 * (b) fix them from Data History before pressing Generate.
 *
 * <p>Countries currently in the "EEI required regardless of value" set
 * mirror the top of the US BIS commercial-export control list —
 * shipments to these are the most common EEI-mismatch failures we've
 * seen in the field. Extend by adding to
 * {@link #EEI_REQUIRED_DESTINATIONS} as new failures surface.
 */
public final class EeiRequirementRules {

    private EeiRequirementRules() {}

    /**
     * Destinations where US-outbound shipments require EEI regardless of
     * monetary value. Covers the strategic-country subset of the US BIS
     * EAR / Census EEI filing matrix.
     */
    private static final Set<String> EEI_REQUIRED_DESTINATIONS = Set.of(
            "CN", // China — Batch #6 evidence (14/14 EEI failures on CN)
            "RU", // Russia — BIS enforcement post-2022 sanctions
            "IR", // Iran — comprehensive sanctions
            "KP", // North Korea — comprehensive sanctions
            "CU", // Cuba — CACR
            "SY"  // Syria — comprehensive sanctions
    );

    /**
     * Returns an error message when the shipment lane requires EEI
     * filing but neither an FTR exemption code nor an AES ITN is
     * present. Returns null when EEI isn't required or when the
     * necessary fields are populated.
     *
     * @param originCountry   ISO-2 origin. Non-US → returns null (only
     *                        US-outbound triggers US EEI rules).
     * @param destCountry     ISO-2 destination. Null-safe.
     * @param ftrExemption    Operator-supplied §30.37 exemption code,
     *                        or null when absent. Any non-blank value
     *                        satisfies the rule at import-validate time
     *                        (the value's own validity is FedEx-side).
     * @param aesCitation     Operator-supplied AES ITN, or null when
     *                        absent. Non-blank satisfies the rule.
     */
    public static String check(String originCountry, String destCountry,
                               String ftrExemption, String aesCitation) {
        // Only US-outbound triggers US EEI rules; other origins have
        // their own export-declaration regimes handled elsewhere
        // (ExportDeclarationPolicyRegistry).
        String origin = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(origin) && !origin.isEmpty()) return null;

        if (destCountry == null) return null;
        String dest = destCountry.trim().toUpperCase(Locale.ROOT);
        if (dest.isEmpty()) return null;
        if (!EEI_REQUIRED_DESTINATIONS.contains(dest)) return null;

        boolean hasFtr = ftrExemption != null && !ftrExemption.trim().isEmpty();
        boolean hasAes = aesCitation != null && !aesCitation.trim().isEmpty();
        if (hasFtr || hasAes) return null;

        return "US exports to " + dest + " require an EEI filing "
                + "(FTR exemption code or AES ITN) regardless of shipment value. "
                + "Bulk import doesn't carry these fields — create this order "
                + "through the manual /orders/new form (which exposes FTR / AES), "
                + "or open it in Data History and fill FTR / AES before "
                + "generating. FedEx rejects with SHIPMENTVALIDATION.EEIEDIT.ERROR "
                + "otherwise.";
    }

    /** True when the destination is in the {@code EEI_REQUIRED_DESTINATIONS}
     *  set. Package-private for tests. */
    static boolean isEeiAlwaysRequiredDestination(String destCountry) {
        return destCountry != null
                && EEI_REQUIRED_DESTINATIONS.contains(destCountry.trim().toUpperCase(Locale.ROOT));
    }
}
