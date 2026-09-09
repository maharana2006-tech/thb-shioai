package com.multiship.backend.service;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ShipmentValidationResult.ValidationIssue;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sprint 52 PR β — pure-static format checks for postal code + state
 * per country. Consumed by
 * {@link ShipmentValidationService} after the presence checks pass, on
 * both sender + recipient addresses. Catches typos like "Delaware"
 * instead of "DE", or ZIP "abcde", before any carrier round-trip.
 *
 * <p>Deliberately narrow scope:
 * <ul>
 *   <li>Format regex for postal code (US, CA, AU, MX, BR, UK)</li>
 *   <li>Enum enforcement for state / province code (US 50 + DC +
 *       territories; CA 13; AU 8; MX 32; BR 27)</li>
 * </ul>
 *
 * <p>What this <strong>doesn't</strong> catch (needs GeoNames DB —
 * see PR γ — or carrier validation, see PR δ):
 * <ul>
 *   <li>ZIP in wrong state ("94105" typed with state "NY" — real ZIP,
 *       wrong binding).</li>
 *   <li>Real address doesn't exist at that ZIP (needs carrier / USPS
 *       CleanseAddress).</li>
 * </ul>
 *
 * <p>Countries not listed fall through as unvalidated — the validator
 * doesn't error just because it doesn't know the country's format.
 * That's intentional: false positives on obscure countries are worse
 * than missing a check on them. Add per-country rules as new markets
 * come online.
 */
public final class AddressFormatValidator {

    /**
     * ISO alpha-2 codes for countries that have no national postal-code
     * system. Sourced from Wikipedia's "List of postal codes" (2026-09-09
     * snapshot — see the "no postal code" rows) plus the Universal Postal
     * Union addressing reference. Live-49-country audit added HK / AE
     * after operator hit UPS's "Recipient postal code is required" on
     * the wire — the field is a hard-required NotBlank on every party
     * block, but UPS / FedEx / DHL all accept an empty PostalCode for
     * this set of destinations.
     *
     * <p>Extend via the {@code carrier.postal-code.optional-countries}
     * env property when a new market comes online without repackaging
     * the JAR — see {@link #withOptionalCountries}.
     *
     * <p>Sources:
     * <ul>
     *   <li>https://en.wikipedia.org/wiki/List_of_postal_codes</li>
     *   <li>https://www.upu.int/en/Postal-Solutions/Programmes-Services/Addressing-Solutions</li>
     * </ul>
     */
    public static final Set<String> NO_POSTAL_CODE_COUNTRIES = Set.of(
            "AO", "AG", "AW", "BS", "BW", "BZ", "BJ", "BF", "BI", "CM",
            "CF", "TD", "KM", "CG", "CD", "CK", "CI", "DJ", "DM", "GQ",
            "ER", "FJ", "GA", "GM", "GN", "GD", "GY", "HM", "HK", "KI",
            "KP", "LY", "ML", "MR", "NR", "NU", "QA", "RW", "SC", "SL",
            "SX", "SB", "SS", "SY", "TG", "TK", "TO", "TV", "UG", "AE",
            "VU", "YE", "ZW");

    /** US ZIP: 5 digits, optional ZIP+4 suffix. */
    private static final Pattern ZIP_US = Pattern.compile("^\\d{5}(-\\d{4})?$");

    /** Canadian postal code: `A1A 1A1` (space optional). Uppercase only —
     *  callers should uppercase before match. Letters exclude D, F, I,
     *  O, Q, U per Canada Post rules (and W, Z aren't valid first
     *  letters), but relaxing to any letter avoids user surprise. */
    private static final Pattern POSTAL_CA = Pattern.compile("^[A-Z]\\d[A-Z] ?\\d[A-Z]\\d$");

    /** AU: 4 digits, no letters. Some states pad-zero starting (e.g. NT
     *  starts with 08); leading-zero allowed. */
    private static final Pattern POSTAL_AU = Pattern.compile("^\\d{4}$");

    /** MX: 5 digits. */
    private static final Pattern POSTAL_MX = Pattern.compile("^\\d{5}$");

    /** BR: `XXXXX-XXX` (5 digits, hyphen, 3 digits). Hyphen sometimes
     *  omitted in casual input; we accept both. */
    private static final Pattern POSTAL_BR = Pattern.compile("^\\d{5}-?\\d{3}$");

    /** UK: various formats — this regex covers the standard shapes per
     *  BS 7666 (excludes special forms like BFPO, Girobank, etc.). */
    private static final Pattern POSTAL_UK = Pattern.compile(
            "^([A-Z]{1,2}\\d[A-Z\\d]?|ASCN|STHL|TDCU|BBND|BIQQ|FIQQ|PCRN|SIQQ|TKCA) ?\\d[A-Z]{2}$");

    /** US 50 states + DC + territories (PR, VI, GU, AS, MP + minor
     *  outlying islands). Uppercase 2-letter ISO / USPS codes. */
    private static final Set<String> STATES_US = Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","DC","FL","GA","HI","ID","IL","IN",
            "IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV","NH",
            "NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN","TX","UT",
            "VT","VA","WA","WV","WI","WY",
            "PR","VI","GU","AS","MP","UM","FM","MH","PW","AA","AE","AP");

    /** Canadian provinces + territories (ISO 3166-2 codes, 2 letters). */
    private static final Set<String> PROVINCES_CA = Set.of(
            "AB","BC","MB","NB","NL","NS","NT","NU","ON","PE","QC","SK","YT");

    /** Australian states + territories. */
    private static final Set<String> STATES_AU = Set.of(
            "NSW","VIC","QLD","WA","SA","TAS","ACT","NT");

    /** Mexican states (ISO 3166-2 codes without MX- prefix). */
    private static final Set<String> STATES_MX = Set.of(
            "AGU","BCN","BCS","CAM","CHP","CHH","COA","COL","CMX","DIF","DUR","GUA",
            "GRO","HID","JAL","MEX","MIC","MOR","NAY","NLE","OAX","PUE","QUE","ROO",
            "SLP","SIN","SON","TAB","TAM","TLA","VER","YUC","ZAC");

    /** Brazilian states (ISO 3166-2 codes without BR- prefix). 26 states + DF. */
    private static final Set<String> STATES_BR = Set.of(
            "AC","AL","AP","AM","BA","CE","DF","ES","GO","MA","MT","MS","MG","PA","PB",
            "PR","PE","PI","RJ","RN","RS","RO","RR","SC","SP","SE","TO");

    /** Per-country ZIP validator lookup. */
    private static final Map<String, Pattern> ZIP_PATTERNS = Map.of(
            "US", ZIP_US, "CA", POSTAL_CA, "AU", POSTAL_AU,
            "MX", POSTAL_MX, "BR", POSTAL_BR, "GB", POSTAL_UK, "UK", POSTAL_UK);

    /** Per-country state enum lookup. */
    private static final Map<String, Set<String>> STATE_ENUMS = Map.of(
            "US", STATES_US, "CA", PROVINCES_CA, "AU", STATES_AU,
            "MX", STATES_MX, "BR", STATES_BR);

    private AddressFormatValidator() { /* static utility */ }

    /**
     * True when the country's own postal system exists — i.e. an empty
     * postal code should trigger a "Recipient postal code is required"
     * error. Countries in {@link #NO_POSTAL_CODE_COUNTRIES} return
     * {@code false}: the operator can leave the postal field blank and
     * downstream carriers accept the wire.
     *
     * @param countryCode ISO alpha-2 (case-insensitive; null returns {@code true}
     *                    so missing country defaults to the strict path).
     */
    public static boolean postalCodeRequiredFor(String countryCode) {
        return postalCodeRequiredFor(countryCode, Set.of());
    }

    /**
     * Variant that unions the built-in {@link #NO_POSTAL_CODE_COUNTRIES}
     * with an operator-provided extension set (fed via the
     * {@code carrier.postal-code.optional-countries} env property). Lets
     * a deployment opt-out of the postal-required check for markets not
     * yet baked into the JAR — a market change (e.g. Ireland dropping
     * Eircode requirement) can ship as a property flip, no code deploy.
     *
     * @param countryCode ISO alpha-2 (case-insensitive; null → required).
     * @param extraOptional additional ISO codes to treat as no-postal.
     */
    public static boolean postalCodeRequiredFor(String countryCode, Set<String> extraOptional) {
        if (!StringUtils.hasText(countryCode)) return true;
        String key = countryCode.trim().toUpperCase(Locale.ROOT);
        if (NO_POSTAL_CODE_COUNTRIES.contains(key)) return false;
        if (extraOptional != null) {
            for (String c : extraOptional) {
                if (c != null && key.equalsIgnoreCase(c.trim())) return false;
            }
        }
        return true;
    }

    /**
     * Validate one address (postal + state format). Returns a mutable
     * list of {@link ValidationIssue} — empty when the address's country
     * has no known rules OR when everything matches.
     *
     * <p>{@code fieldPrefix} is used to namespace the {@code field} on
     * each issue: caller passes {@code "sender"} or {@code "recipient"}
     * so the FE knows which block to highlight.
     */
    public static List<ValidationIssue> validate(String countryCode, String postalCode,
                                                  String state, String fieldPrefix) {
        List<ValidationIssue> issues = new java.util.ArrayList<>();
        if (!StringUtils.hasText(countryCode)) return issues;
        String country = countryCode.trim().toUpperCase(Locale.ROOT);

        // Postal code format.
        Pattern zipRegex = ZIP_PATTERNS.get(country);
        if (zipRegex != null && StringUtils.hasText(postalCode)) {
            String normalized = postalCode.trim().toUpperCase(Locale.ROOT);
            if (!zipRegex.matcher(normalized).matches()) {
                issues.add(ValidationIssue.builder()
                        .code(ErrorCode.VALIDATION_ERROR.name())
                        .message("Postal code \"" + postalCode.trim() + "\" doesn't match the "
                                + country + " format (" + describeFormat(country) + ").")
                        .field(fieldPrefix + ".postalCode")
                        .build());
            }
        }

        // State / province enum.
        Set<String> stateEnum = STATE_ENUMS.get(country);
        if (stateEnum != null && StringUtils.hasText(state)) {
            String normalized = state.trim().toUpperCase(Locale.ROOT);
            if (!stateEnum.contains(normalized)) {
                issues.add(ValidationIssue.builder()
                        .code(ErrorCode.VALIDATION_ERROR.name())
                        .message("State/province \"" + state.trim() + "\" isn't a valid "
                                + country + " code. Use the 2-letter code (e.g. "
                                + exampleState(country) + "), not the full name.")
                        .field(fieldPrefix + ".state")
                        .build());
            }
        }

        return issues;
    }

    private static String describeFormat(String country) {
        return switch (country) {
            case "US" -> "12345 or 12345-6789";
            case "CA" -> "A1A 1A1";
            case "AU" -> "4 digits";
            case "MX" -> "5 digits";
            case "BR" -> "12345-678";
            case "GB", "UK" -> "SW1A 1AA";
            default -> "n/a";
        };
    }

    private static String exampleState(String country) {
        return switch (country) {
            case "US" -> "CA, NY, TX";
            case "CA" -> "ON, QC, BC";
            case "AU" -> "NSW, VIC, QLD";
            case "MX" -> "CMX, JAL, NLE";
            case "BR" -> "SP, RJ, MG";
            default -> "";
        };
    }
}
