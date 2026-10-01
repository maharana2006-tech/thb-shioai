package com.multiship.backend.util;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Turns a raw carrier rejection ("FEDEX createShipment HTTP 400: {json}")
 * into one operator-facing sentence. Shared by the bulk importer's row
 * messages and the Logs page's CARRIER_REJECTED notes so the same failure
 * reads identically everywhere; the raw payload belongs in server logs /
 * tooltips, never in operator-facing text.
 *
 * <p>V118 — the five "clean" pattern→sentence mappings live in the
 * {@code carrier_error_message} table and are DB-tunable.
 * {@code CarrierErrorMessagePlatformService} swaps the live list via
 * {@link #setPatternRules} at ApplicationReadyEvent. Bootstrap defaults
 * match the pre-V118 hardcoded switch, so a DB outage never degrades to
 * raw payloads reaching the operator.
 */
public final class CarrierErrorMessages {

    /** One pattern-rule row. {@code matchAnyOf} is pipe-separated OR
     *  tokens; {@code humanized} may contain the literal {@code {carrier}}
     *  placeholder, substituted at runtime with the pretty carrier name. */
    public record PatternRule(String matchAnyOf, String humanized) {}

    /** Bootstrap defaults mirror the pre-V118 hardcoded switch. */
    private static final List<PatternRule> BOOTSTRAP_RULES = List.of(
            new PatternRule("NOTSERVED|NOT SERVED|DESTINATION.COUNTRY|ORIGIN.COUNTRY",
                    "{carrier} doesn't serve this lane on the selected service."),
            new PatternRule("PHONENUMBER|PHONE NUMBER|PHONE.",
                    "{carrier} needs a valid recipient phone number for this shipment."),
            new PatternRule("NOT A REGISTERED|NOT AUTHORIZED|NOT AUTHORISED|UNAUTHORIZED",
                    "{carrier} rejected the billing account. The account isn't authorised for this carrier — check Settings → Carriers."),
            new PatternRule("POSTAL|ZIP",
                    "{carrier} rejected the postal code for this address."),
            new PatternRule("CUSTOMS|COMMODITY|TOTALCUSTOMSVALUE",
                    "{carrier} rejected the customs details for this international shipment."));

    private static volatile List<PatternRule> patternRules = BOOTSTRAP_RULES;

    /** V118 hook — {@code CarrierErrorMessagePlatformService} swaps the
     *  live list at startup with DB rows. Null / empty input leaves the
     *  bootstrap default in place. */
    public static void setPatternRules(List<PatternRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        patternRules = List.copyOf(rules);
    }

    private CarrierErrorMessages() {}

    /**
     * @param raw         the raw failure text (connector message, possibly
     *                    wrapping an HTTP body)
     * @param carrierCode canonical carrier for the sentence ("FEDEX"…); null
     *                    → "The carrier"
     */
    public static String humanize(String raw, String carrierCode) {
        if (!StringUtils.hasText(raw)) return "The carrier rejected this shipment.";
        String carrier = StringUtils.hasText(carrierCode)
                ? carrierCode.trim().toUpperCase(Locale.ROOT) : "";
        String carrierName = switch (carrier) {
            case "FEDEX" -> "FedEx";
            case "UPS" -> "UPS";
            case "USPS" -> "USPS";
            case "DHL" -> "DHL";
            default -> "The carrier";
        };
        // Preserve the "(saved as order N)" locator the carrier layer appends.
        String tail = "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\(saved as order \\d+\\))").matcher(raw);
        if (m.find()) tail = " " + m.group(1);
        String up = raw.toUpperCase(Locale.ROOT);

        // V118 — walk the DB-driven rules (bootstrap default when empty).
        for (PatternRule rule : patternRules) {
            if (matchesAnyToken(up, rule.matchAnyOf())) {
                return rule.humanized().replace("{carrier}", carrierName) + tail;
            }
        }

        // Not a recognised code. Only a raw carrier PAYLOAD is worth hiding — a
        // JSON body or a bare "…HTTP 4xx: {…}" dump is debug output, not an
        // operator message. A short, clean cause (a transport error, a timeout)
        // is honest and useful, so it's kept verbatim.
        boolean looksLikePayload = raw.contains("{") || raw.contains("}")
                || java.util.regex.Pattern.compile("HTTP\\s*\\d{3}").matcher(up).find();
        if (looksLikePayload) {
            // Keep the carrier's own sentence when the payload carries one —
            // "rejected this shipment." alone left operators opening orders one
            // by one to learn that 300 rows failed for the same reason.
            String reason = extractReason(raw);
            return carrierName + " rejected this shipment" + (reason != null ? ": " + reason : ".") + tail;
        }
        return raw;
    }

    /** True when any pipe-separated token from {@code anyOf} is a substring of
     *  the already-uppercased {@code upperHaystack}. */
    private static boolean matchesAnyToken(String upperHaystack, String anyOf) {
        if (anyOf == null || anyOf.isBlank()) return false;
        for (String token : anyOf.split("\\|")) {
            String t = token.trim();
            if (!t.isEmpty() && upperHaystack.contains(t.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern[] REASON_PATTERNS = {
            java.util.regex.Pattern.compile("\"(?:message|Description|description|errorDescription|detail)\"\\s*:\\s*\"([^\"]{3,220})\""),
            java.util.regex.Pattern.compile("(?i)(the service is currently unavailable[^\"}{]{0,120})"),
            java.util.regex.Pattern.compile("(?i)(missing or invalid [^\"}{]{3,120})"),
    };

    /** First human sentence inside a carrier payload, or null. */
    public static String extractReason(String raw) {
        if (raw == null) return null;
        for (java.util.regex.Pattern p : REASON_PATTERNS) {
            java.util.regex.Matcher m = p.matcher(raw);
            if (m.find()) {
                String r = m.group(1).replace("\\n", " ").replaceAll("\\s+", " ").trim();
                r = r.replaceAll("\\(saved as order \\d+\\)", "").trim();
                if (r.length() > 3) return r.endsWith(".") ? r.substring(0, r.length() - 1) : r;
            }
        }
        return null;
    }
}
