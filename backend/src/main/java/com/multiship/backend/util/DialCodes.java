package com.multiship.backend.util;

import java.util.Locale;
import java.util.Map;

/**
 * ISO country → international dial code, mirroring the frontend's
 * DIAL_CODE_BY_COUNTRY (LabelDocumentPage) so the ZPL facsimile prints the
 * same phone string as the on-screen/PDF label. Covers the common lanes;
 * unknown countries fall through to the raw digits.
 */
public final class DialCodes {

    private DialCodes() {}

    private static final Map<String, String> BY_COUNTRY = Map.ofEntries(
            Map.entry("US", "1"), Map.entry("CA", "1"), Map.entry("GB", "44"),
            Map.entry("IN", "91"), Map.entry("AU", "61"), Map.entry("DE", "49"),
            Map.entry("FR", "33"), Map.entry("IT", "39"), Map.entry("ES", "34"),
            Map.entry("NL", "31"), Map.entry("BE", "32"), Map.entry("CH", "41"),
            Map.entry("SE", "46"), Map.entry("NO", "47"), Map.entry("DK", "45"),
            Map.entry("FI", "358"), Map.entry("JP", "81"), Map.entry("CN", "86"),
            Map.entry("KR", "82"), Map.entry("SG", "65"), Map.entry("HK", "852"),
            Map.entry("MX", "52"), Map.entry("BR", "55"), Map.entry("AR", "54"),
            Map.entry("CL", "56"), Map.entry("CO", "57"), Map.entry("PE", "51"),
            Map.entry("AE", "971"), Map.entry("ZA", "27"), Map.entry("NZ", "64"),
            Map.entry("IE", "353"), Map.entry("PT", "351"), Map.entry("AT", "43"));

    /**
     * Digits-only phone with the country's dial code prepended when known and
     * not already present — identical semantics to the frontend's
     * formatPhoneForLabel, so the two label renderers agree.
     */
    public static String withDialCode(String phone, String countryCode) {
        if (phone == null || phone.isBlank()) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return phone;
        String cc = countryCode == null ? null
                : BY_COUNTRY.get(countryCode.trim().toUpperCase(Locale.ROOT));
        if (cc == null) return digits;
        return digits.startsWith(cc) ? digits : cc + digits;
    }
}
