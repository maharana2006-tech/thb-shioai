package com.multiship.backend.service.carrier;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.TenantSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * C1 — resolves the "ship-from" defaults for a tenant. Applied in
 * {@code CarrierServiceImpl} + {@code OrderController} as the fallback
 * chain shifts from
 * <pre>
 *   request sender → warehouse → carrier.shipper.*
 * </pre>
 * to
 * <pre>
 *   request sender → warehouse → tenant_settings.shipper.* → carrier.shipper.*
 * </pre>
 *
 * <p>Every field falls back independently — a tenant that only wants to
 * override the country still gets phone / name / etc. from the platform
 * default. WARN log fires when the ultimate platform fallback is used
 * so ops can see "some tenant isn't fully configured at /settings/system".
 *
 * <p>The audit's fail-hard mode (throw when no config resolves) is
 * deferred behind {@code carrier.shipper.strict-mode} — a later PR flips
 * that once every tenant has configured their defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipperDefaultsService {

    /** Keys in {@code tenant_settings}. Camel-case to match FE JSON. */
    public static final String KEY_NAME          = "shipper.name";
    public static final String KEY_PHONE         = "shipper.phone";
    public static final String KEY_ADDRESS_LINE1 = "shipper.addressLine1";
    public static final String KEY_ADDRESS_LINE2 = "shipper.addressLine2";
    public static final String KEY_CITY          = "shipper.city";
    public static final String KEY_STATE         = "shipper.state";
    public static final String KEY_POSTAL_CODE   = "shipper.postalCode";
    public static final String KEY_COUNTRY_CODE  = "shipper.countryCode";

    /** All keys — used by the /settings/system CRUD FE. */
    public static final java.util.List<String> ALL_KEYS = java.util.List.of(
            KEY_NAME, KEY_PHONE, KEY_ADDRESS_LINE1, KEY_ADDRESS_LINE2,
            KEY_CITY, KEY_STATE, KEY_POSTAL_CODE, KEY_COUNTRY_CODE);

    /** Keys that strict-mode requires every tenant to override. addressLine2
     *  is excluded because many addresses legitimately don't have it. */
    public static final java.util.List<String> REQUIRED_KEYS = java.util.List.of(
            KEY_NAME, KEY_PHONE, KEY_ADDRESS_LINE1,
            KEY_CITY, KEY_STATE, KEY_POSTAL_CODE, KEY_COUNTRY_CODE);

    private final CarrierProperties carrierProperties;
    private final TenantSettingsService tenantSettings;

    /**
     * Resolve for a tenant. {@code null} / blank tenantCode → platform default.
     *
     * <p>When {@code carrier.shipper.strict-mode=true} AND a non-blank
     * {@code tenantCode} is given, any REQUIRED field (see
     * {@link #REQUIRED_KEYS}) that would fall through to platform default
     * throws {@link IllegalStateException} naming the missing field(s).
     * The pre-flip caller never sees this; existing callers under the
     * default flag=false still get the silent-fallback behaviour.
     */
    public CarrierProperties.ShipperDefaults resolveFor(String tenantCode) {
        CarrierProperties.ShipperDefaults platform = carrierProperties.getShipper();
        if (tenantCode == null || tenantCode.isBlank()) {
            return platform;
        }
        CarrierProperties.ShipperDefaults merged = new CarrierProperties.ShipperDefaults();
        merged.setName(mergeField(tenantCode, KEY_NAME, platform.getName()));
        merged.setPhone(mergeField(tenantCode, KEY_PHONE, platform.getPhone()));
        merged.setAddressLine1(mergeField(tenantCode, KEY_ADDRESS_LINE1, platform.getAddressLine1()));
        merged.setAddressLine2(mergeField(tenantCode, KEY_ADDRESS_LINE2, platform.getAddressLine2()));
        merged.setCity(mergeField(tenantCode, KEY_CITY, platform.getCity()));
        merged.setState(mergeField(tenantCode, KEY_STATE, platform.getState()));
        merged.setPostalCode(mergeField(tenantCode, KEY_POSTAL_CODE, platform.getPostalCode()));
        merged.setCountryCode(mergeField(tenantCode, KEY_COUNTRY_CODE, platform.getCountryCode()));
        if (platform.isStrictMode()) assertStrictFor(tenantCode);
        return merged;
    }

    /**
     * Audit §4 strict-mode: throw when any required field for the given
     * tenant has no override in tenant_settings. Called inline by
     * {@link #resolveFor} only when {@code carrier.shipper.strict-mode=true}
     * so the common-case path stays allocation-free.
     */
    private void assertStrictFor(String tenantCode) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            Optional<String> v = tenantSettings.getSetting(tenantCode, key);
            if (v.isEmpty() || v.get().isBlank()) missing.add(key);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "carrier.shipper.strict-mode is on and tenant " + tenantCode
                    + " has no override for: " + String.join(", ", missing)
                    + ". Configure at /settings/system or set"
                    + " carrier.shipper.strict-mode=false.");
        }
    }

    /**
     * Full map for the /settings/system FE — one entry per key.
     * "resolved" means "what the request path would actually use for
     * this tenant right now"; "isTenantOverride" tells the FE whether
     * to render "(platform default)" placeholder styling.
     */
    public java.util.Map<String, FieldValue> currentValues(String tenantCode) {
        CarrierProperties.ShipperDefaults platform = carrierProperties.getShipper();
        java.util.Map<String, FieldValue> out = new java.util.LinkedHashMap<>();
        addField(out, tenantCode, KEY_NAME, platform.getName());
        addField(out, tenantCode, KEY_PHONE, platform.getPhone());
        addField(out, tenantCode, KEY_ADDRESS_LINE1, platform.getAddressLine1());
        addField(out, tenantCode, KEY_ADDRESS_LINE2, platform.getAddressLine2());
        addField(out, tenantCode, KEY_CITY, platform.getCity());
        addField(out, tenantCode, KEY_STATE, platform.getState());
        addField(out, tenantCode, KEY_POSTAL_CODE, platform.getPostalCode());
        addField(out, tenantCode, KEY_COUNTRY_CODE, platform.getCountryCode());
        return out;
    }

    private String mergeField(String tenantCode, String key, String platformValue) {
        Optional<String> override = tenantSettings.getSetting(tenantCode, key);
        if (override.isPresent() && !override.get().isBlank()) {
            return override.get().trim();
        }
        // Only WARN once per (tenant, key) per boot? For now log every miss —
        // volume is low (only when shipper is falling through to platform).
        log.debug("shipper default for tenant={} key={} → using platform value", tenantCode, key);
        return platformValue;
    }

    private void addField(java.util.Map<String, FieldValue> out, String tenantCode,
                          String key, String platformValue) {
        Optional<String> override = tenantSettings.getSetting(tenantCode, key);
        String resolved;
        boolean isTenantOverride;
        if (override.isPresent() && !override.get().isBlank()) {
            resolved = override.get().trim();
            isTenantOverride = true;
        } else {
            resolved = platformValue;
            isTenantOverride = false;
        }
        out.put(key, new FieldValue(resolved, isTenantOverride, platformValue));
    }

    public record FieldValue(String resolved, boolean isTenantOverride, String platformValue) {}
}
