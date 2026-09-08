package com.multiship.backend.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * US-territory country-code normalizer. FedEx and UPS treat US
 * territories (PR/VI/GU/AS/MP/UM) as separate countries for shipping,
 * not US states. When an operator enters {@code country=US} with
 * {@code state=PR}, wire payloads must send {@code countryCode=PR}
 * or the carrier 400s with "country not served" (FedEx) / 120xxx
 * routing errors (UPS).
 *
 * <p>Applied at connector-side wire emission — the operator's saved
 * address row keeps its US country + territory state so display /
 * exports don't drift. See docs/plans/us_territories.md (TBD).
 *
 * <p>Territories covered per user direction 2026-09-07:
 * <ul>
 *   <li>PR — Puerto Rico</li>
 *   <li>VI — US Virgin Islands</li>
 *   <li>GU — Guam</li>
 *   <li>AS — American Samoa</li>
 *   <li>MP — Northern Mariana Islands</li>
 *   <li>UM — US Minor Outlying Islands</li>
 * </ul>
 *
 * <p>Also exposes a per-territory service allowlist ({@link
 * #isServiceAllowedForTerritory}) — every territory is served by a
 * different subset of carrier services (VI accepts UPS Worldwide only;
 * PR accepts UPS domestic Air AND Worldwide; ground family serves none
 * of them). Mirrors {@code multiship-react/src/utils/usTerritoryServices.ts}
 * on the FE — keep the two in sync or the FE will show an operator a
 * service the backend rejects a hop later.
 */
public final class UsTerritoryNormalizer {

    /** ISO 3166-1 alpha-2 codes for the six US territories. Both codes
     *  double as valid ISO state codes for {@code country=US} — that
     *  ambiguity is the root of the connector-side confusion this
     *  helper resolves. */
    public static final Set<String> US_TERRITORY_CODES = Set.of(
            "PR", "VI", "GU", "AS", "MP", "UM");

    /** UPS service codes that deliver to Puerto Rico from a US-mainland
     *  origin. Includes domestic Air family (UPS bills PR at domestic
     *  rates but still accepts Worldwide codes for the same lane).
     *  Denied: 03 Ground, 11 Standard, 12 3 Day Select. */
    /** UPS service codes that deliver to Puerto Rico. Domestic Air only —
     *  UPS moves PR shipments on its domestic network. Worldwide family
     *  (07/08/54/65) is REJECTED by the UPS Rating API with 121100
     *  "service invalid for the shipment origin" for US → PR lanes,
     *  even though older docs suggested both were valid. Confirmed by
     *  operator 2026-09-08. */
    private static final Set<String> UPS_ALLOWED_PR = Set.of(
            "01", "02", "13", "14", "59"); // Domestic Air only

    /** UPS service codes that deliver to the other five US territories
     *  (VI/GU/AS/MP/UM). Only Worldwide-family — domestic Air is what
     *  triggers UPS error 121100 "service invalid for origin". */
    private static final Set<String> UPS_ALLOWED_INTL = Set.of(
            "07", "08", "54", "65");

    /** FedEx service codes that deliver to Puerto Rico. INTERNATIONAL
     *  family only. The carrier rule is asymmetric with UPS: UPS moves
     *  US → PR on its DOMESTIC network; FedEx treats PR as a true
     *  international destination. Operator confirmed 2026-09-08 that
     *  FedEx rejects every domestic service (Express Saver / 2Day /
     *  Overnight variants) for US → PR with "This service type is not
     *  available for the destination." */
    private static final Set<String> FEDEX_ALLOWED_PR = Set.of(
            "INTERNATIONAL_PRIORITY", "INTERNATIONAL_ECONOMY",
            "INTERNATIONAL_FIRST", "INTERNATIONAL_PRIORITY_EXPRESS");

    /** FedEx service codes for VI/GU/AS/MP/UM — intl-family only. */
    private static final Set<String> FEDEX_ALLOWED_INTL = Set.of(
            "INTERNATIONAL_PRIORITY", "INTERNATIONAL_ECONOMY",
            "INTERNATIONAL_FIRST", "INTERNATIONAL_PRIORITY_EXPRESS");

    /** Legacy ground-family denylist. Fallback for carriers with no
     *  validated per-territory allowlist (currently DHL + USPS). */
    private static final Set<String> GROUND_FAMILY_DENIED = Set.of(
            "FEDEX_GROUND", "GROUND_HOME_DELIVERY", "SMART_POST",
            "03", "11", "12");

    /** Territory → carrier → allowed-service-code set. */
    private static final Map<String, Map<String, Set<String>>> ALLOWLIST = Map.of(
            "PR", Map.of("UPS", UPS_ALLOWED_PR,   "FEDEX", FEDEX_ALLOWED_PR),
            "VI", Map.of("UPS", UPS_ALLOWED_INTL, "FEDEX", FEDEX_ALLOWED_INTL),
            "GU", Map.of("UPS", UPS_ALLOWED_INTL, "FEDEX", FEDEX_ALLOWED_INTL),
            "AS", Map.of("UPS", UPS_ALLOWED_INTL, "FEDEX", FEDEX_ALLOWED_INTL),
            "MP", Map.of("UPS", UPS_ALLOWED_INTL, "FEDEX", FEDEX_ALLOWED_INTL),
            "UM", Map.of("UPS", UPS_ALLOWED_INTL, "FEDEX", FEDEX_ALLOWED_INTL));

    private UsTerritoryNormalizer() {
        // static-only
    }

    /**
     * True when {@code country} is US (or blank, matching our default)
     * AND {@code state} is one of the six US-territory codes.
     */
    public static boolean isUsTerritory(String country, String state) {
        if (state == null) return false;
        String c = country == null ? "US" : country.trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(c) && !c.isEmpty()) return false;
        String s = state.trim().toUpperCase(Locale.ROOT);
        return US_TERRITORY_CODES.contains(s);
    }

    /**
     * Return the ISO 3166-1 country code the carrier actually needs
     * on the wire:
     * <ul>
     *   <li>US territory state under a US country → the territory
     *       code (PR/VI/GU/AS/MP/UM).</li>
     *   <li>Anything else → the input country verbatim.</li>
     * </ul>
     */
    public static String normalizeCountryCode(String country, String state) {
        if (isUsTerritory(country, state)) {
            return state.trim().toUpperCase(Locale.ROOT);
        }
        return country;
    }

    /**
     * Fast-fail check: is {@code serviceCode} deliverable to
     * {@code territory} on {@code carrier}?
     *
     * <ul>
     *   <li>{@code territory} not in {@link #US_TERRITORY_CODES} → true
     *       (no filter — this isn't a territory lane).</li>
     *   <li>Territory has a validated per-carrier allowlist → the code
     *       must be in it.</li>
     *   <li>Territory has no per-carrier allowlist (DHL / USPS) → fall
     *       back to legacy ground-family denylist.</li>
     * </ul>
     *
     * Runs pre-wire on the manual label path so operators get an
     * actionable "VI needs Worldwide services" error instead of the
     * carrier's cryptic {@code 121100} several seconds later.
     */
    public static boolean isServiceAllowedForTerritory(
            String territory, String carrier, String serviceCode) {
        if (territory == null || carrier == null || serviceCode == null) return true;
        String t = territory.trim().toUpperCase(Locale.ROOT);
        if (!US_TERRITORY_CODES.contains(t)) return true;
        String c = carrier.trim().toUpperCase(Locale.ROOT);
        String s = serviceCode.trim();
        Map<String, Set<String>> perCarrier = ALLOWLIST.get(t);
        Set<String> allow = perCarrier == null ? null : perCarrier.get(c);
        if (allow != null) return allow.contains(s);
        // No validated allowlist for this carrier — legacy ground-hide
        // fallback keeps behavior stable for DHL / USPS.
        return !GROUND_FAMILY_DENIED.contains(s);
    }
}
