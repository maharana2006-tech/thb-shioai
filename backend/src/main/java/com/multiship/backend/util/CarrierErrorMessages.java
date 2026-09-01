package com.multiship.backend.util;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Turns a raw carrier rejection ("FEDEX createShipment HTTP 400: {json}")
 * into one operator-facing sentence. Shared by the bulk importer's row
 * messages and the Logs page's CARRIER_REJECTED notes so the same failure
 * reads identically everywhere; the raw payload belongs in server logs /
 * tooltips, never in operator-facing text.
 */
public final class CarrierErrorMessages {

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

        if (up.contains("NOTSERVED") || up.contains("NOT SERVED") || up.contains("DESTINATION.COUNTRY")
                || up.contains("ORIGIN.COUNTRY")) {
            return carrierName + " doesn't serve this lane on the selected service." + tail;
        }
        if (up.contains("PHONENUMBER") || up.contains("PHONE NUMBER") || up.contains("PHONE.")) {
            return carrierName + " needs a valid recipient phone number for this shipment." + tail;
        }
        if (up.contains("NOT A REGISTERED") || up.contains("NOT AUTHORIZED") || up.contains("NOT AUTHORISED")
                || up.contains("UNAUTHORIZED")
                || (up.contains("ACCOUNT") && (up.contains("HTTP 400") || up.contains("HTTP 401") || up.contains("HTTP 403")))) {
            return carrierName + " rejected the billing account. The account isn't authorised for this carrier — check Settings → Carriers." + tail;
        }
        if (up.contains("POSTAL") || up.contains("ZIP")) {
            return carrierName + " rejected the postal code for this address." + tail;
        }
        if (up.contains("CUSTOMS") || up.contains("COMMODITY") || up.contains("TOTALCUSTOMSVALUE")) {
            return carrierName + " rejected the customs details for this international shipment." + tail;
        }
        // Not a recognised code. Only a raw carrier PAYLOAD is worth hiding — a
        // JSON body or a bare "…HTTP 4xx: {…}" dump is debug output, not an
        // operator message. A short, clean cause (a transport error, a timeout)
        // is honest and useful, so it's kept verbatim.
        boolean looksLikePayload = raw.contains("{") || raw.contains("}")
                || java.util.regex.Pattern.compile("HTTP\\s*\\d{3}").matcher(up).find();
        if (looksLikePayload) {
            return carrierName + " rejected this shipment." + tail;
        }
        return raw;
    }
}
