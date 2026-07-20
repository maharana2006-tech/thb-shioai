package com.multiship.backend.util;

import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/**
 * Customs-territory lookup: goods only face customs when they cross a customs
 * border, and customs unions merge many countries into ONE territory. A
 * Germany→France parcel crosses a country border but NOT a customs border —
 * no declaration, no importer, no broker.
 *
 * Kept in sync with the frontend taxonomy in multiship-react/src/utils/countries.ts
 * (EU members + union tags). Update BOTH if a union's membership changes.
 */
public final class CustomsTerritories {

    private static final Map<String, String> UNION_BY_COUNTRY = new HashMap<>();

    static {
        // EU customs union (27)
        for (String c : new String[]{"AT","BE","BG","HR","CY","CZ","DK","EE","FI","FR","DE","GR","HU",
                "IE","IT","LV","LT","LU","MT","NL","PL","PT","RO","SK","SI","ES","SE"}) {
            UNION_BY_COUNTRY.put(c, "EU");
        }
        // Eurasian Economic Union
        for (String c : new String[]{"RU","BY","KZ","AM","KG"}) UNION_BY_COUNTRY.put(c, "EAEU");
        // Gulf Cooperation Council
        for (String c : new String[]{"SA","AE","KW","QA","BH","OM"}) UNION_BY_COUNTRY.put(c, "GCC");
        // Southern African Customs Union
        for (String c : new String[]{"ZA","BW","NA","LS","SZ"}) UNION_BY_COUNTRY.put(c, "SACU");
    }

    private CustomsTerritories() {}

    /** The customs territory a country belongs to: its union, or itself. */
    public static String territoryOf(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return "";
        String code = countryCode.trim().toUpperCase(Locale.ROOT);
        return UNION_BY_COUNTRY.getOrDefault(code, code);
    }

    /** True when both countries sit in the same customs territory (no customs border between them). */
    public static boolean sameTerritory(String a, String b) {
        String ta = territoryOf(a);
        String tb = territoryOf(b);
        return !ta.isEmpty() && ta.equals(tb);
    }
}
