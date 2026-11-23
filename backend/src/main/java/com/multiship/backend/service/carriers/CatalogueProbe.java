package com.multiship.backend.service.carriers;

import com.multiship.backend.service.carriers.CarrierConnector.ServiceOffering;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One representative address per ship-from country, used only to ask a carrier
 * which services it offers from there (catalogue sync). Never used on a label.
 */
final class CatalogueProbe {

    record Addr(String country, String city, String state, String postal) {
        /** Carrier-neutral address fields; the caller supplies the carrier's own key names. */
        Map<String, Object> toMap(String cityKey, String stateKey, String postalKey, String countryKey) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(cityKey, city);
            if (!state.isEmpty()) m.put(stateKey, state);
            m.put(postalKey, postal);
            m.put(countryKey, country);
            return m;
        }
    }

    record Lane(Addr to, String scope) {
    }

    // ponytail: one fixed address per common origin; an origin missing here syncs the
    // built-in list. Use the account's warehouse address if lane-exact results ever matter.
    private static final Map<String, Addr> ORIGINS = Map.ofEntries(
            addr("US", "New York", "NY", "10019"),
            addr("GB", "London", "", "SW1A 1AA"),
            addr("DE", "Berlin", "", "10115"),
            addr("FR", "Paris", "", "75001"),
            addr("NL", "Amsterdam", "", "1012 JS"),
            addr("IT", "Roma", "RM", "00184"),
            addr("ES", "Madrid", "", "28001"),
            addr("IN", "New Delhi", "DL", "110001"),
            addr("CN", "Shanghai", "", "200000"),
            addr("JP", "Tokyo", "", "100-0001"),
            addr("AU", "Sydney", "NSW", "2000"),
            addr("CA", "Toronto", "ON", "M5H 2N2"),
            addr("MX", "Ciudad de Mexico", "DF", "06000"),
            addr("BR", "Sao Paulo", "SP", "01310-100"),
            addr("SG", "Singapore", "", "018956"));

    private CatalogueProbe() {
    }

    private static Map.Entry<String, Addr> addr(String country, String city, String state, String postal) {
        return Map.entry(country, new Addr(country, city, state, postal));
    }

    /** The probe address for an origin country, or null when none is known. */
    static Addr origin(String country) {
        return ORIGINS.get(country == null ? "US" : country.trim().toUpperCase(Locale.ROOT));
    }

    /** A domestic lane plus an export lane (to GB from the US, to the US from anywhere else). */
    static List<Lane> lanes(Addr from) {
        Addr abroad = ORIGINS.get("US".equals(from.country()) ? "GB" : "US");
        return List.of(new Lane(from, "DOMESTIC"), new Lane(abroad, "INTERNATIONAL"));
    }

    /** Adds an offering; a service seen on both lanes becomes scope BOTH. */
    static void merge(Map<String, ServiceOffering> byCode, ServiceOffering o) {
        byCode.merge(o.serviceCode(), o, (a, b) -> a.scope().equals(b.scope())
                ? a : new ServiceOffering(a.serviceCode(), a.name(), "BOTH"));
    }
}
