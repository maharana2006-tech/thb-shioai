package com.multiship.backend.service.carriers;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pre-flight service ↔ lane compatibility checks.
 *
 * <p>Motivation — Batch #8 post-mortem (2026-09-12): 25 of 7,368 bulk-
 * import orders errored with the same shape of carrier rejection —
 * a service level that doesn't cover the AK / HI destination. Cause
 * split three ways: UPS 3 Day Select (code {@code 12}) to AK/HI hit
 * {@code UPS 121210 "service is not available from the origin to the
 * destination"}; FedEx Express Saver to AK/HI hit
 * {@code REQUESTEDSHIPMENT.SERVICETYPE.NOTSUPPORTED "FEDEX_EXPRESS_SAVER
 * is not supported for the origin and destination pair"}; and FedEx
 * Standard Overnight to specific HI ZIPs hit the same NOTSUPPORTED
 * error.
 *
 * <p>Each error cost a real UPS/FedEx round-trip and produced an ERROR
 * order the operator had to individually retry. The fix is to catch
 * these at import-validate time — {@link #checkLane} runs before the
 * batch commits, and rows with an unsupported service/destination pair
 * are held as {@code NEEDS_FIX} with an actionable message ("UPS 3 Day
 * Select doesn't cover AK — pick 2nd Day Air (02) or Next Day Air (01)
 * instead"). Encoded from carrier docs current 2026-09-12; extend by
 * appending entries to {@link #DOMESTIC48_ONLY_SERVICES}.
 *
 * <p>Rules encoded here are only the HARD lane exclusions the carrier
 * definitively rejects — soft advisories like "FedEx Standard Overnight
 * has some AK ZIP-level exclusions" would over-block if enforced here,
 * so they're intentionally left to the reactive carrier response.
 */
public final class CarrierServiceLaneRules {

    private CarrierServiceLaneRules() {}

    /** US destinations outside the contiguous 48 states — AK, HI, and
     *  the outlying-territory postal codes carriers treat as separate
     *  service zones (PR, VI, GU, AS, MP). */
    private static final Set<String> NON_48_US = Set.of("AK", "HI", "PR", "VI", "GU", "AS", "MP");

    /** AK + HI only — for services that DO reach PR/territories but not the
     *  Pacific / Arctic non-continental states (FedEx Express Saver, some
     *  UPS ground-tier services). */
    private static final Set<String> AK_HI_ONLY = Set.of("AK", "HI");

    /** Services that only cover the contiguous 48 US states.
     *  Key = "CARRIER|SERVICE_CODE" (upper-cased). Value = the friendly
     *  service name for the operator's error message. Add carriers /
     *  services here as new lane exclusions surface. */
    private static final Map<String, String> DOMESTIC48_ONLY_SERVICES = Map.ofEntries(
            // UPS — Ground and 3 Day Select are 48-states-only per UPS
            // service guide. UPS surfaces 121210 "service is not available"
            // when either is picked for AK/HI/PR/etc.
            Map.entry("UPS|03", "UPS Ground"),
            Map.entry("UPS|GROUND", "UPS Ground"),
            Map.entry("UPS|12", "UPS 3 Day Select"),
            Map.entry("UPS|3_DAY_SELECT", "UPS 3 Day Select"),
            // FedEx — Ground / Home Delivery / SmartPost don't reach AK/HI;
            // FedEx SmartPost specifically also doesn't reach PR.
            Map.entry("FEDEX|FEDEX_GROUND", "FedEx Ground"),
            Map.entry("FEDEX|GROUND_HOME_DELIVERY", "FedEx Home Delivery"),
            Map.entry("FEDEX|SMART_POST", "FedEx SmartPost"),
            Map.entry("FEDEX|FEDEX_GROUND_ECONOMY", "FedEx Ground Economy"));

    /** Services that reach the 48 states + PR/territories but NOT AK/HI —
     *  a distinct tier from {@link #DOMESTIC48_ONLY_SERVICES}. FedEx
     *  Express Saver is the archetypal example (Batch #8: 14 of 25 errors). */
    private static final Map<String, String> NON_AK_HI_SERVICES = Map.of(
            "FEDEX|FEDEX_EXPRESS_SAVER", "FedEx Express Saver");

    /**
     * International service ↔ destination-country exclusions (2026-09-12
     * Batch #6 post-mortem). Key = "CARRIER|SERVICE|COUNTRY_ISO2"
     * (upper-cased). Value = the friendly service name for the operator's
     * error message.
     *
     * <p>FedEx International First is a next-day-by-8/10AM premium service
     * offered only to select US ZIPs → select major cities in a small set
     * of countries. FedEx rejects the shipment with SERVICETYPE.NOTSUPPORTED
     * for destinations not in the covered set. Batch #6 saw 11 rejections
     * on these lanes; the pattern is stable enough to encode.
     */
    private static final Map<String, String> INTL_SERVICE_LANE_EXCLUSIONS = Map.ofEntries(
            // FedEx International First — not offered to ZA/IN/PE/BR/MX
            // per FedEx published coverage (2026-09). Batch-6 failure
            // orders: ZA×4, IN×2, PE×2, BR×1, MX×1.
            Map.entry("FEDEX|INTERNATIONAL_FIRST|ZA", "FedEx International First"),
            Map.entry("FEDEX|INTERNATIONAL_FIRST|IN", "FedEx International First"),
            Map.entry("FEDEX|INTERNATIONAL_FIRST|PE", "FedEx International First"),
            Map.entry("FEDEX|INTERNATIONAL_FIRST|BR", "FedEx International First"),
            Map.entry("FEDEX|INTERNATIONAL_FIRST|MX", "FedEx International First"));

    /** Recommended replacement services when the operator's pick doesn't
     *  cover the destination. Grouped by carrier + rough purpose the
     *  operator likely wanted. Kept simple — a full alternative-service
     *  resolver belongs on the routing side, not the import validator. */
    private static final Map<String, String> UPS_ALT_FOR_48_ONLY =
            Map.of("SUGGEST", "2nd Day Air (02) or Next Day Air (01)");
    private static final Map<String, String> FEDEX_ALT_FOR_48_ONLY =
            Map.of("SUGGEST", "FedEx 2Day, Priority Overnight, or First Overnight");

    /**
     * Check whether the service/carrier combination can reach the given
     * destination. Returns an error message when the pair is a known
     * hard-rejection, or {@code null} when the pair is either fine or
     * we don't have a rule (unknown pair falls through to the reactive
     * carrier response, same as pre-fix behaviour).
     *
     * @param carrier      Carrier code (UPS/FEDEX/…), null-safe.
     * @param service      Service code as it will land on the wire
     *                     (e.g. "03", "FEDEX_GROUND"), null-safe.
     * @param destState    Destination state code, upper-cased. Only US
     *                     states are checked — non-US destinations
     *                     always return null (their carriers handle
     *                     lane checks their own way).
     * @param destCountry  Destination country code. Non-US → null.
     */
    public static String checkLane(String carrier, String service, String destState, String destCountry) {
        // destCountry alone is enough for the intl-lane checks below;
        // destState nullability only blocks the US intra-state rules.
        if (carrier == null || service == null || destCountry == null) {
            return null;
        }
        String c = destCountry.trim().toUpperCase(Locale.ROOT);
        String carrierUp = carrier.trim().toUpperCase(Locale.ROOT);
        String serviceUp = service.trim().toUpperCase(Locale.ROOT);
        String key = carrierUp + "|" + serviceUp;

        // Intl service ↔ country exclusions (Batch #6 post-mortem). Runs
        // BEFORE the US-only checks below because it applies regardless of
        // destState (state may be blank for many intl countries).
        String intlFriendly = INTL_SERVICE_LANE_EXCLUSIONS.get(key + "|" + c);
        if (intlFriendly != null) {
            return intlFriendly + " isn't offered to " + c + ". "
                    + intlSuggestion(carrierUp, serviceUp)
                    + " (carrier rejects this lane with a "
                    + rejectionCode(carrier)
                    + " error before the label prints).";
        }

        if (!"US".equals(c)) {
            // Non-US intra-state lanes: skip. Carriers surface their own
            // errors and the state list is too varied to encode.
            return null;
        }
        if (destState == null) return null;
        String stateUp = destState.trim().toUpperCase(Locale.ROOT);

        String friendly = DOMESTIC48_ONLY_SERVICES.get(key);
        if (friendly != null && NON_48_US.contains(stateUp)) {
            return friendly + " doesn't cover " + stateUp + ". "
                    + suggestion(carrier)
                    + " (carrier rejects this lane with a "
                    + rejectionCode(carrier)
                    + " error before the label prints).";
        }
        friendly = NON_AK_HI_SERVICES.get(key);
        if (friendly != null && AK_HI_ONLY.contains(stateUp)) {
            return friendly + " doesn't cover " + stateUp + ". "
                    + suggestion(carrier)
                    + " (carrier rejects this lane with a "
                    + rejectionCode(carrier)
                    + " error before the label prints).";
        }
        return null;
    }

    /** International-service replacement suggestion. INTERNATIONAL_FIRST
     *  is a next-day premium — its natural fallback is
     *  INTERNATIONAL_PRIORITY (2-3 business days), which has broader
     *  country coverage. Other intl services fall through to a generic. */
    private static String intlSuggestion(String carrier, String service) {
        if ("FEDEX".equals(carrier) && "INTERNATIONAL_FIRST".equals(service)) {
            return "Pick FedEx International Priority (INTERNATIONAL_PRIORITY) or Economy instead.";
        }
        return "Pick a service that covers this destination.";
    }

    private static String suggestion(String carrier) {
        String c = carrier.trim().toUpperCase(Locale.ROOT);
        if ("UPS".equals(c)) {
            return "Pick " + UPS_ALT_FOR_48_ONLY.get("SUGGEST") + " instead.";
        }
        if ("FEDEX".equals(c)) {
            return "Pick " + FEDEX_ALT_FOR_48_ONLY.get("SUGGEST") + " instead.";
        }
        return "Pick a service that covers this destination.";
    }

    private static String rejectionCode(String carrier) {
        String c = carrier.trim().toUpperCase(Locale.ROOT);
        if ("UPS".equals(c)) return "UPS 121210";
        if ("FEDEX".equals(c)) return "FedEx SERVICETYPE.NOTSUPPORTED";
        return "carrier";
    }
}
