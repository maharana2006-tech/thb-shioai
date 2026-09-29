package com.multiship.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The client layout of a bulk file — the columns a client's own system writes
 * (CLIENT_ID, ATTENTION, … SHIPVIA_CD, GROUP_ID …, as in POB250's WSH2609F.csv)
 * — and what each one means here. A file in this layout imports like one in the
 * standard layout; the standard template stays as it is.
 */
public final class ImportColumnAliases {

    private ImportColumnAliases() {}

    /** The client layout, column by column, in its order. */
    public static final List<String> CLIENT_LAYOUT = List.of(
            "CLIENT_ID", "ATTENTION", "COMPANY_NAME", "PHONE", "EMAIL", "ADDRESS1", "ADDRESS2", "CITY",
            "STATE_CODE", "ZIP", "COUNTRY_CODE", "WEIGHT", "ONE_RATE", "SHIPVIA_CD", "THIRD_PARTY_ACC",
            "GROUP_ID", "ITEM_NUMBERS", "QUANTITY", "SHIP_DATE", "HTSCode",
            "THP_ADDRESS", "THP_CITY", "THP_STATE", "THP_POSTAL", "THP_COUNTRY");

    /** Client column (lower case) → our field. CITY and WEIGHT already share our names. */
    private static final Map<String, String> TO_FIELD = Map.ofEntries(
            Map.entry("client_id", "clientCode"),
            Map.entry("attention", "recipientName"),
            Map.entry("company_name", "recipientCompany"),
            Map.entry("phone", "recipientPhone"),
            Map.entry("email", "recipientEmail"),
            Map.entry("address1", "addressLine1"),
            Map.entry("address2", "addressLine2"),
            Map.entry("city", "city"),
            Map.entry("state_code", "state"),
            Map.entry("zip", "postalCode"),
            Map.entry("country_code", "countryCode"),
            Map.entry("weight", "weight"),
            Map.entry("shipvia_cd", "serviceType"),
            Map.entry("third_party_acc", "accountNumber"),
            // Rows sharing a GROUP_ID go to different stores: it's a reference, not one shipment.
            Map.entry("group_id", "reference"),
            Map.entry("item_numbers", "itemDescription"),
            Map.entry("quantity", "itemQuantity"),
            Map.entry("htscode", "hsCode"));

    /** Client columns with nothing to go to yet → the warning a filled cell gets. */
    private static final Map<String, String> UNUSED = Map.of(
            "one_rate", "ONE_RATE isn't supported yet — the shipment is rated normally",
            "ship_date", "SHIP_DATE isn't used yet — labels are dated the day they're generated",
            "thp_address", "THP_ADDRESS (third-party billing address) isn't used yet",
            "thp_city", "THP_CITY (third-party billing address) isn't used yet",
            "thp_state", "THP_STATE (third-party billing address) isn't used yet",
            "thp_postal", "THP_POSTAL (third-party billing address) isn't used yet",
            "thp_country", "THP_COUNTRY (third-party billing address) isn't used yet");

    /** Our field for a column header in either layout; null when it's neither (a custom field). */
    public static String fieldOf(String header) {
        if (header == null) return null;
        String h = header.trim().toLowerCase(Locale.ROOT);
        String aliased = TO_FIELD.get(h);
        if (aliased != null) return aliased;
        for (String f : OrderImportServiceImpl.HEADERS) {
            if (f.toLowerCase(Locale.ROOT).equals(h)) return f;
        }
        return null;
    }

    /** True for a client-layout column name (known or not-yet-used). */
    static boolean isClientColumn(String header) {
        String h = header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
        return TO_FIELD.containsKey(h) || UNUSED.containsKey(h);
    }

    /** The warning for a filled not-yet-used client column, or null. */
    static String unusedWarning(String header) {
        return header == null ? null : UNUSED.get(header.trim().toLowerCase(Locale.ROOT));
    }

    /** Adds our field name for each client column found, so the parser reads either layout. */
    static void addFieldNames(Map<String, Integer> lowerHeaderMap) {
        for (Map.Entry<String, Integer> e : new LinkedHashMap<>(lowerHeaderMap).entrySet()) {
            String field = TO_FIELD.get(e.getKey());
            if (field != null) lowerHeaderMap.putIfAbsent(field.toLowerCase(Locale.ROOT), e.getValue());
        }
    }

    /** The client layout's columns as template keys: our field where there is one, else the client name. */
    static List<String> clientLayoutKeys() {
        return CLIENT_LAYOUT.stream().map(c -> {
            String f = TO_FIELD.get(c.toLowerCase(Locale.ROOT));
            return f != null ? f : c;
        }).toList();
    }

    /** Template key → the client's column name, for the header row. */
    static Map<String, String> clientHeaderNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : CLIENT_LAYOUT) {
            String f = TO_FIELD.get(c.toLowerCase(Locale.ROOT));
            out.put(f != null ? f : c, c);
        }
        return out;
    }
}
