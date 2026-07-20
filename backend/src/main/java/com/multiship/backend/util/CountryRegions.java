package com.multiship.backend.util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Country → shipping REGION lookup for ship-method rules ("Express to Europe
 * rides UPS Worldwide Saver"). This is the backend mirror of the frontend
 * taxonomy in multiship-react/src/utils/countries.ts — keep BOTH in sync.
 * (Different from CustomsTerritories: regions are geographic/UX groupings;
 * territories are customs law.)
 */
public final class CountryRegions {

    public static final List<String> REGIONS = List.of(
            "North America", "Europe", "Middle East", "Asia", "Oceania",
            "South America", "Africa", "Other");

    private static final Map<String, String> REGION_BY_CODE = new HashMap<>();

    private static void put(String region, String codes) {
        for (String c : codes.split(" ")) REGION_BY_CODE.put(c, region);
    }

    static {
        put("North America", "US CA MX AG AI AW BB BL BM BQ BS BZ CR CU CW DM DO GD GL GP GT HN HT JM KN KY LC MF MQ MS NI PA PM PR SV SX TC TT VC VG VI");
        put("Europe", "GB IE DE FR ES IT NL BE AD AL AT AX BA BG BY CH CY CZ DK EE FI FO GG GI GR HR HU IM IS JE LI LT LU LV MC MD ME MK MT NO PL PT RO RS RU SE SI SJ SK SM UA VA XK");
        put("Middle East", "AE BH IL IQ IR JO KW LB OM PS QA SA SY TR YE");
        put("Asia", "JP CN HK SG KR IN AF AM AZ BD BN BT GE ID KG KH KP KZ LA LK MM MN MO MV MY NP PH PK TH TJ TL TM TW UZ VN");
        put("Oceania", "AU NZ AS CK FJ FM GU KI MH MP NC NR NU NF PF PG PW SB TK TO TV VU WF WS");
        put("South America", "AR BO BR CL CO EC FK GF GY PE PY SR UY VE");
        put("Africa", "ZA AO BF BI BJ BW CD CF CG CI CM CV DJ DZ EG EH ER ET GA GH GM GN GQ GW KE KM LR LS LY MA MG ML MR MU MW MZ NA NE NG RE RW SC SD SH SL SN SO SS ST SZ TD TG TN TZ UG YT ZM ZW");
        put("Other", "AQ BV CC CX GS HM IO PN TF UM");
    }

    private CountryRegions() {}

    /** The region a country belongs to; unknown codes land in "Other". */
    public static String regionOf(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return "Other";
        return REGION_BY_CODE.getOrDefault(countryCode.trim().toUpperCase(Locale.ROOT), "Other");
    }
}
