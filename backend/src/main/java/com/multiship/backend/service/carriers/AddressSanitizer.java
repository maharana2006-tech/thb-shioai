package com.multiship.backend.service.carriers;

import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;

/**
 * Field-level scrubbing for addresses before they hit a carrier API.
 * Carriers reject or misinterpret payloads with control characters,
 * curly quotes, doubled punctuation, or non-code state values —
 * this helper normalises those without altering meaningful content.
 *
 * <p>Rules applied to every string field:
 * <ul>
 *   <li>trim leading/trailing whitespace</li>
 *   <li>strip ASCII control characters (0x00–0x1F, 0x7F)</li>
 *   <li>fold curly / angled quotes to straight quotes ('/")</li>
 *   <li>drop stray backticks</li>
 *   <li>collapse runs of internal whitespace to a single space</li>
 *   <li>collapse doubled punctuation (,, .. -- __) to a single char</li>
 * </ul>
 *
 * <p>Field-specific extras:
 * <ul>
 *   <li>{@code state} — upper-cased; if it looks like a 2- or 3-letter
 *       carrier code after scrubbing, kept as-is; otherwise passed
 *       through unchanged so the carrier can echo back a validation
 *       error the operator can understand.</li>
 *   <li>{@code postalCode} — every whitespace character removed
 *       (Canadian "K1A 0B1" becomes "K1A0B1"; most carriers accept
 *       either, but a few reject the spaces).</li>
 *   <li>{@code countryCode} — upper-cased.</li>
 * </ul>
 */
public final class AddressSanitizer {

    private AddressSanitizer() {}

    public static AddressToValidate sanitize(AddressToValidate a) {
        if (a == null) return null;
        return new AddressToValidate(
                clean(a.name()),
                clean(a.company()),
                clean(a.addressLine1()),
                clean(a.addressLine2()),
                clean(a.addressLine3()),
                clean(a.city()),
                cleanState(a.state()),
                cleanPostalCode(a.postalCode()),
                cleanCountry(a.countryCode())
        );
    }

    /** Generic string scrub — safe for any address field. */
    static String clean(String s) {
        if (s == null) return null;
        String out = s
                // strip ASCII control chars
                .replaceAll("[\\x00-\\x1F\\x7F]", "")
                // curly / angled quotes → straight
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('«', '"').replace('»', '"')
                // backticks are almost always accidental in addresses
                .replace("`", "")
                // collapse doubled punctuation
                .replaceAll(",{2,}", ",")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("-{2,}", "-")
                .replaceAll("_{2,}", "_")
                // collapse whitespace runs
                .replaceAll("\\s+", " ")
                .trim();
        return out;
    }

    static String cleanState(String s) {
        String base = clean(s);
        if (base == null || base.isEmpty()) return base;
        String upper = base.toUpperCase(java.util.Locale.ROOT);
        // If it looks like a 2/3-letter code with no separators, keep the
        // upper-case form. Otherwise return the trimmed value verbatim
        // (e.g. "Ontario", "New South Wales") — the carrier will decide.
        if (upper.matches("[A-Z]{2,3}")) return upper;
        return upper;
    }

    static String cleanPostalCode(String s) {
        String base = clean(s);
        if (base == null) return null;
        return base.replaceAll("\\s+", "");
    }

    static String cleanCountry(String s) {
        String base = clean(s);
        if (base == null || base.isEmpty()) return base;
        return base.toUpperCase(java.util.Locale.ROOT);
    }
}
