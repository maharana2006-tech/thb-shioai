package com.multiship.backend.util;

import java.util.Locale;
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
 */
public final class UsTerritoryNormalizer {

    /** ISO 3166-1 alpha-2 codes for the six US territories. Both codes
     *  double as valid ISO state codes for {@code country=US} — that
     *  ambiguity is the root of the connector-side confusion this
     *  helper resolves. */
    public static final Set<String> US_TERRITORY_CODES = Set.of(
            "PR", "VI", "GU", "AS", "MP", "UM");

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
}
