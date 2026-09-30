package com.multiship.backend.service.carrier;

/**
 * C4 — static bridge so JPA entities ({@code User#getCarrierDisplayName})
 * and legacy static-method call sites ({@code ShippingConfigService#
 * canonicalCarrierFor}) can reach {@link CarrierAliasService} without
 * requiring @Autowired injection.
 *
 * <p>Set once by {@code CarrierAliasService.reload()} at @PostConstruct.
 * Callers get null/pass-through until then, which is fine because none
 * of these methods run during Spring bean construction.
 */
public final class CarrierAliasHolder {

    private static volatile CarrierAliasService svc;

    private CarrierAliasHolder() {}

    static void set(CarrierAliasService s) {
        svc = s;
    }

    /** @return canonical carrier code, or null if unknown / not yet loaded. */
    public static String canonicalize(String code) {
        CarrierAliasService s = svc;
        return s == null ? null : s.canonicalize(code);
    }

    /** @return display label; falls back to the input (upper-cased) when unknown. */
    public static String display(String code) {
        CarrierAliasService s = svc;
        if (s != null) return s.display(code);
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
