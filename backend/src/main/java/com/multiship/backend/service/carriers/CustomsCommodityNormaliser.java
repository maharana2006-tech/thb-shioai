package com.multiship.backend.service.carriers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PR-T10 — carrier-neutral normalisation for customs commodity fields.
 * Stamps.com SERA (and USPS CBP in general) rejects loose shapes that
 * operator-typed data commonly carries:
 *
 * <ul>
 *   <li><b>HS codes</b> paste with dots ({@code 6109.10.0012}) — SERA
 *       expects digits only. {@link #normaliseHsCode} strips non-digits
 *       and truncates to the 10-digit CBP ceiling.</li>
 *   <li><b>country_of_origin</b> arrives as {@code "USA"}, {@code "United States"},
 *       {@code "u.s.a."}, etc. SERA + USPS CN22/CN23 want ISO 3166 alpha-2
 *       ({@code "US"}). {@link #normaliseCountryOfOrigin} pass-through
 *       valid alpha-2, maps common alpha-3, resolves top-20 country
 *       names, returns {@code null} when it can't.</li>
 *   <li><b>Descriptions</b> that overflow SERA's CN22-shape silently
 *       truncate at the carrier. {@link #truncateDescription} coerces
 *       to 255 chars with a trailing ellipsis so the caller + the
 *       carrier see the same string.</li>
 *   <li><b>Weight × quantity vs package weight</b> — advisory check.
 *       Returns false when the declared contents weight diverges from
 *       the package weight by more than 20%; caller logs a WARN. USPS
 *       Customs flags for inspection when declared &gt; package.</li>
 * </ul>
 *
 * <p>Pure-static util. Mirrors the shape of {@code UsTerritoryNormalizer}
 * (also pure-static) so neither participates in Spring injection and
 * the two can be called from the same hot path without wiring overhead.
 */
public final class CustomsCommodityNormaliser {

    /** USPS CBP's HS code ceiling. 6 / 8 / 10 are all legal; we normalise
     *  to the longest acceptable form the caller provided. */
    public static final int HS_CODE_MAX_DIGITS = 10;

    /** CN22 / CN23 description cap. 255 is the longest USPS form slot;
     *  SERA truncates silently past this. */
    public static final int DESCRIPTION_MAX_CHARS = 255;

    /** Declared contents weight tolerance against the package weight.
     *  ±20% matches USPS Customs' inspection-trigger threshold. */
    public static final BigDecimal WEIGHT_TOLERANCE_LOWER = new BigDecimal("0.80");
    public static final BigDecimal WEIGHT_TOLERANCE_UPPER = new BigDecimal("1.20");

    private CustomsCommodityNormaliser() {}

    /**
     * Strip non-digit chars and truncate to {@link #HS_CODE_MAX_DIGITS}.
     * Returns {@code null} for blank / all-letter inputs (caller should
     * reject upstream — a missing HS code on an intl shipment is a
     * regulatory omission, not a shape bug).
     */
    public static String normaliseHsCode(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        return digits.length() > HS_CODE_MAX_DIGITS
                ? digits.substring(0, HS_CODE_MAX_DIGITS)
                : digits;
    }

    /**
     * Coerce {@code country_of_origin} to ISO 3166 alpha-2 uppercase.
     * <ul>
     *   <li>Valid alpha-2 (from {@link Locale#getISOCountries()}) →
     *       pass-through uppercased.</li>
     *   <li>Common alpha-3 (USA, CAN, GBR, ...) → mapped.</li>
     *   <li>Common country name (United States, Great Britain, ...) →
     *       mapped.</li>
     *   <li>Anything else → {@code null}; caller rejects.</li>
     * </ul>
     */
    public static String normaliseCountryOfOrigin(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        // Strip common punctuation (U.S.A. → USA).
        String stripped = t.replaceAll("[\\s.\\-]+", "");
        if (stripped.length() == 2 && ISO2_CODES.contains(stripped)) return stripped;
        String mapped = ISO3_TO_ISO2.get(stripped);
        if (mapped != null) return mapped;
        return COMMON_NAME_TO_ISO2.get(t);
    }

    /** Truncate the description to {@link #DESCRIPTION_MAX_CHARS}
     *  characters, appending an ellipsis when truncation occurred. */
    public static String truncateDescription(String raw) {
        if (raw == null) return null;
        if (raw.length() <= DESCRIPTION_MAX_CHARS) return raw;
        // Keep 254 chars + 1 ellipsis so the result is still ≤ 255.
        return raw.substring(0, DESCRIPTION_MAX_CHARS - 1) + "…";
    }

    /** USPS CN22 contents_description cap. The customs form prints this
     *  on a short line; USPS validates against ~50 chars and rejects
     *  longer values with carrier error 4522242 "contents_description
     *  specified is invalid." Separate from {@link #DESCRIPTION_MAX_CHARS}
     *  (255) which bounds per-commodity {@code item_description}. */
    public static final int CONTENTS_DESCRIPTION_MAX_CHARS = 50;

    /**
     * Normalise the shipment-level {@code contents_description} that lands
     * on the CN22 form. Stricter than {@link #truncateDescription} because
     * USPS validates this field aggressively (SERA error 4522242 for
     * "Carbon road bicycle frame, unassembled" — comma + length suspected):
     *
     * <ul>
     *   <li>Commas / semicolons / pipes / slashes replaced with space —
     *       USPS internally parses the field and rejects list-shaped
     *       values.</li>
     *   <li>Non-printable-ASCII dropped — CN22 is a printed form, ink
     *       stays Latin-1.</li>
     *   <li>Whitespace collapsed.</li>
     *   <li>Truncated to {@link #CONTENTS_DESCRIPTION_MAX_CHARS}.</li>
     * </ul>
     *
     * <p>Returns {@code null} for null / all-whitespace input so callers
     * can omit the field rather than send a blank string.
     */
    public static String normaliseContentsDescription(String raw) {
        if (raw == null) return null;
        String cleaned = raw
                .replaceAll("[,;|/\\\\]+", " ")        // list separators → space
                .replaceAll("[^\\x20-\\x7E]+", " ")    // non-printable-ASCII → space
                .replaceAll("\\s+", " ")               // collapse whitespace
                .trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > CONTENTS_DESCRIPTION_MAX_CHARS
                ? cleaned.substring(0, CONTENTS_DESCRIPTION_MAX_CHARS).trim()
                : cleaned;
    }

    /**
     * Advisory reconciliation of declared contents weight against the
     * package weight. Returns {@code true} when the sum of
     * {@code (unitWeight × quantity)} falls within ±20% of the package
     * weight, or when any input is missing (nothing to compare).
     * Returns {@code false} only when all three are present AND the
     * declared total diverges by more than 20%.
     */
    public static boolean weightsReconcile(BigDecimal unitWeight, Integer qty,
                                            BigDecimal pkgWeight) {
        if (unitWeight == null || qty == null || pkgWeight == null) return true;
        if (unitWeight.signum() <= 0 || qty <= 0 || pkgWeight.signum() <= 0) return true;
        BigDecimal declared = unitWeight.multiply(BigDecimal.valueOf(qty));
        BigDecimal ratio = declared.divide(pkgWeight, 4, RoundingMode.HALF_UP);
        return ratio.compareTo(WEIGHT_TOLERANCE_LOWER) >= 0
                && ratio.compareTo(WEIGHT_TOLERANCE_UPPER) <= 0;
    }

    // ===== static lookup data =====

    private static final Set<String> ISO2_CODES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    /** Common alpha-3 → alpha-2. Covers the top ~40 lanes we see on
     *  outbound USPS intl; unknown codes fall through to a name-match
     *  attempt, then to null. */
    private static final Map<String, String> ISO3_TO_ISO2 = Map.ofEntries(
            Map.entry("USA", "US"), Map.entry("CAN", "CA"), Map.entry("MEX", "MX"),
            Map.entry("GBR", "GB"), Map.entry("IRL", "IE"), Map.entry("FRA", "FR"),
            Map.entry("DEU", "DE"), Map.entry("ESP", "ES"), Map.entry("ITA", "IT"),
            Map.entry("NLD", "NL"), Map.entry("BEL", "BE"), Map.entry("CHE", "CH"),
            Map.entry("AUT", "AT"), Map.entry("DNK", "DK"), Map.entry("SWE", "SE"),
            Map.entry("NOR", "NO"), Map.entry("FIN", "FI"), Map.entry("POL", "PL"),
            Map.entry("PRT", "PT"), Map.entry("GRC", "GR"), Map.entry("CZE", "CZ"),
            Map.entry("JPN", "JP"), Map.entry("KOR", "KR"), Map.entry("CHN", "CN"),
            Map.entry("HKG", "HK"), Map.entry("TWN", "TW"), Map.entry("SGP", "SG"),
            Map.entry("MYS", "MY"), Map.entry("THA", "TH"), Map.entry("IDN", "ID"),
            Map.entry("PHL", "PH"), Map.entry("IND", "IN"), Map.entry("PAK", "PK"),
            Map.entry("AUS", "AU"), Map.entry("NZL", "NZ"), Map.entry("ZAF", "ZA"),
            Map.entry("BRA", "BR"), Map.entry("ARG", "AR"), Map.entry("CHL", "CL"),
            Map.entry("COL", "CO"), Map.entry("PER", "PE"), Map.entry("ISR", "IL"),
            Map.entry("ARE", "AE"), Map.entry("SAU", "SA"), Map.entry("TUR", "TR"),
            Map.entry("EGY", "EG"), Map.entry("NGA", "NG"), Map.entry("KEN", "KE"));

    /** Country names → alpha-2. Keyed by UPPERCASE input (no punctuation
     *  variants — those are stripped by {@link #normaliseCountryOfOrigin}
     *  before the map lookup). Covers the top ~25 lanes. */
    private static final Map<String, String> COMMON_NAME_TO_ISO2 = Map.ofEntries(
            Map.entry("UNITED STATES", "US"),
            Map.entry("UNITED STATES OF AMERICA", "US"),
            Map.entry("AMERICA", "US"),
            Map.entry("UNITED KINGDOM", "GB"),
            Map.entry("GREAT BRITAIN", "GB"),
            Map.entry("ENGLAND", "GB"),
            Map.entry("SCOTLAND", "GB"),
            Map.entry("WALES", "GB"),
            Map.entry("CANADA", "CA"),
            Map.entry("MEXICO", "MX"),
            Map.entry("GERMANY", "DE"),
            Map.entry("FRANCE", "FR"),
            Map.entry("SPAIN", "ES"),
            Map.entry("ITALY", "IT"),
            Map.entry("NETHERLANDS", "NL"),
            Map.entry("HOLLAND", "NL"),
            Map.entry("BELGIUM", "BE"),
            Map.entry("SWITZERLAND", "CH"),
            Map.entry("AUSTRIA", "AT"),
            Map.entry("SWEDEN", "SE"),
            Map.entry("NORWAY", "NO"),
            Map.entry("DENMARK", "DK"),
            Map.entry("FINLAND", "FI"),
            Map.entry("POLAND", "PL"),
            Map.entry("PORTUGAL", "PT"),
            Map.entry("GREECE", "GR"),
            Map.entry("IRELAND", "IE"),
            Map.entry("JAPAN", "JP"),
            Map.entry("CHINA", "CN"),
            Map.entry("SOUTH KOREA", "KR"),
            Map.entry("KOREA", "KR"),
            Map.entry("INDIA", "IN"),
            Map.entry("AUSTRALIA", "AU"),
            Map.entry("NEW ZEALAND", "NZ"),
            Map.entry("BRAZIL", "BR"),
            Map.entry("ISRAEL", "IL"),
            Map.entry("TURKEY", "TR"));
}
