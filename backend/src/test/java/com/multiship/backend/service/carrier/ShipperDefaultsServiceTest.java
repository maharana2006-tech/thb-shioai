package com.multiship.backend.service.carrier;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.TenantSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** C1 — resolveFor merges tenant overrides on top of platform defaults per key. */
class ShipperDefaultsServiceTest {

    private CarrierProperties props;
    private TenantSettingsService tenantSettings;
    private ShipperDefaultsService svc;

    @BeforeEach
    void setUp() {
        props = new CarrierProperties();
        CarrierProperties.ShipperDefaults platform = props.getShipper();
        platform.setName("Multiship Warehouse");
        platform.setPhone("0000000000");
        platform.setAddressLine1("1 Warehouse Way");
        platform.setAddressLine2("");
        platform.setCity("Bengaluru");
        platform.setState("KA");
        platform.setPostalCode("560001");
        platform.setCountryCode("IN");

        tenantSettings = mock(TenantSettingsService.class);
        svc = new ShipperDefaultsService(props, tenantSettings);
    }

    @Test
    void nullTenantReturnsPlatformDefault() {
        var out = svc.resolveFor(null);
        assertEquals("IN", out.getCountryCode());
        assertEquals("Bengaluru", out.getCity());
    }

    @Test
    void tenantOverrideWinsPerField() {
        when(tenantSettings.getSetting("ACME", ShipperDefaultsService.KEY_COUNTRY_CODE))
                .thenReturn(Optional.of("US"));
        when(tenantSettings.getSetting(eq("ACME"), eq(ShipperDefaultsService.KEY_STATE)))
                .thenReturn(Optional.of("NY"));

        var out = svc.resolveFor("ACME");
        // Overrides applied.
        assertEquals("US", out.getCountryCode());
        assertEquals("NY", out.getState());
        // Unset keys still fall through to platform.
        assertEquals("Bengaluru", out.getCity());
        assertEquals("Multiship Warehouse", out.getName());
    }

    @Test
    void blankOverrideFallsThroughToPlatform() {
        when(tenantSettings.getSetting("ACME", ShipperDefaultsService.KEY_COUNTRY_CODE))
                .thenReturn(Optional.of("   "));
        var out = svc.resolveFor("ACME");
        assertEquals("IN", out.getCountryCode());
    }

    @Test
    void strictModeThrowsWhenRequiredFieldFallsThroughToPlatform() {
        props.getShipper().setStrictMode(true);
        // Tenant has configured country + state only; the other required
        // fields (name/phone/line1/city/postal) are missing — strict-mode
        // should refuse to resolve.
        when(tenantSettings.getSetting(eq("ACME"), eq(ShipperDefaultsService.KEY_COUNTRY_CODE)))
                .thenReturn(Optional.of("US"));
        when(tenantSettings.getSetting(eq("ACME"), eq(ShipperDefaultsService.KEY_STATE)))
                .thenReturn(Optional.of("NY"));
        when(tenantSettings.getSetting(eq("ACME"), anyString()))
                .thenReturn(Optional.empty());
        // Re-prime the two overrides so anyString() default doesn't shadow them.
        when(tenantSettings.getSetting(eq("ACME"), eq(ShipperDefaultsService.KEY_COUNTRY_CODE)))
                .thenReturn(Optional.of("US"));
        when(tenantSettings.getSetting(eq("ACME"), eq(ShipperDefaultsService.KEY_STATE)))
                .thenReturn(Optional.of("NY"));

        ShipperDefaultsMissingException ex = assertThrows(ShipperDefaultsMissingException.class,
                () -> svc.resolveFor("ACME"));
        assertEquals(com.multiship.backend.dto.ErrorCode.SHIPPER_DEFAULTS_MISSING, ex.getErrorCode());
        assertTrue(ex.getMissingKeys().contains(ShipperDefaultsService.KEY_NAME));
        assertTrue(ex.getMessage().contains("ACME"),
                "message must name the tenant: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(ShipperDefaultsService.KEY_NAME),
                "message must list the missing field: " + ex.getMessage());
    }

    @Test
    void strictModeAllowsResolveWhenEveryRequiredFieldIsOverridden() {
        props.getShipper().setStrictMode(true);
        for (String key : ShipperDefaultsService.REQUIRED_KEYS) {
            when(tenantSettings.getSetting(eq("ACME"), eq(key)))
                    .thenReturn(Optional.of("tenant-" + key));
        }
        var out = svc.resolveFor("ACME");
        assertEquals("tenant-" + ShipperDefaultsService.KEY_NAME, out.getName());
        assertEquals("tenant-" + ShipperDefaultsService.KEY_COUNTRY_CODE, out.getCountryCode());
    }

    @Test
    void lenientReadUnderStrictModeReturnsMergedValuesInsteadOfThrowing() {
        props.getShipper().setStrictMode(true);
        CarrierProperties.ShipperDefaults d = svc.resolveFor("ARHDEV", false);
        assertEquals(props.getShipper().getName(), d.getName());
        assertThrows(ShipperDefaultsMissingException.class, () -> svc.resolveFor("ARHDEV"));
    }

    @Test
    void strictModeDoesNotAffectBlankTenantPath() {
        props.getShipper().setStrictMode(true);
        // Platform default resolution — no tenant → no strict check.
        var out = svc.resolveFor(null);
        assertEquals("IN", out.getCountryCode());
    }

    /* -------- BUG-025 sender-aware strict-mode -------- */

    /**
     * BUG-025 (QA 2026-10-23) — a request that carries a complete sender
     * block never falls back to the platform default, so the strict-mode
     * throw at that point is a false positive. The new helper lets the
     * CarrierServiceImpl caller skip the strict check when the sender
     * covers every REQUIRED_KEYS field.
     */
    @Test
    void senderCoversRequiredFields_trueWhenEveryRequiredFieldPresent() {
        com.multiship.backend.dto.ManualShipmentRequest.Address from =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        from.setName("Acme Warehouse");
        from.setPhone("5551234567");
        from.setAddressLine1("1 Warehouse Way");
        from.setCity("Louisville");
        from.setState("KY");
        from.setPostalCode("40209");
        from.setCountryCode("US");
        assertTrue(ShipperDefaultsService.senderCoversRequiredFields(from));
    }

    @Test
    void senderCoversRequiredFields_addressLine2IsOptional() {
        // REQUIRED_KEYS deliberately excludes addressLine2 (many addresses
        // legitimately have none). Confirm the helper agrees.
        com.multiship.backend.dto.ManualShipmentRequest.Address from =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        from.setName("Acme");
        from.setPhone("1");
        from.setAddressLine1("1 Way");
        // addressLine2 left null — must still count as covered.
        from.setCity("c");
        from.setState("s");
        from.setPostalCode("p");
        from.setCountryCode("US");
        assertTrue(ShipperDefaultsService.senderCoversRequiredFields(from));
    }

    @Test
    void senderCoversRequiredFields_falseWhenAnyRequiredFieldMissing() {
        com.multiship.backend.dto.ManualShipmentRequest.Address from =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        from.setName("Acme");
        from.setPhone("1");
        from.setAddressLine1("1 Way");
        from.setCity("c");
        from.setState("s");
        from.setPostalCode("p");
        // countryCode missing
        assertFalse(ShipperDefaultsService.senderCoversRequiredFields(from));
    }

    @Test
    void senderCoversRequiredFields_falseWhenFieldBlank() {
        com.multiship.backend.dto.ManualShipmentRequest.Address from =
                new com.multiship.backend.dto.ManualShipmentRequest.Address();
        from.setName("Acme");
        from.setPhone("   ");  // whitespace-only ≠ populated
        from.setAddressLine1("1 Way");
        from.setCity("c");
        from.setState("s");
        from.setPostalCode("p");
        from.setCountryCode("US");
        assertFalse(ShipperDefaultsService.senderCoversRequiredFields(from));
    }

    @Test
    void senderCoversRequiredFields_falseWhenNull() {
        assertFalse(ShipperDefaultsService.senderCoversRequiredFields(null));
    }

    @Test
    void strictModeSkippedWhenCallerPassesEnforceStrictFalse() {
        // The sender-aware fix wires this via `resolveFor(code, !senderCovers)`.
        // Pin the two-arg contract so the regression surface is "the second
        // arg actually suppresses the strict check".
        props.getShipper().setStrictMode(true);
        when(tenantSettings.getSetting(eq("ACME"), anyString())).thenReturn(Optional.empty());

        var out = svc.resolveFor("ACME", false);  // enforceStrict=false
        assertNotNull(out);
        // Platform values flow through unchanged — no exception.
        assertEquals("IN", out.getCountryCode());
    }

    @Test
    void strictModeStillFiresWhenCallerPassesEnforceStrictTrueAndTenantHasNoOverride() {
        // Defence against accidental always-skip: enforceStrict=true path
        // still throws when the tenant is unconfigured.
        props.getShipper().setStrictMode(true);
        when(tenantSettings.getSetting(eq("ACME"), anyString())).thenReturn(Optional.empty());

        assertThrows(ShipperDefaultsMissingException.class,
                () -> svc.resolveFor("ACME", true));
    }

    @Test
    void currentValuesFlagsOverridesForFe() {
        when(tenantSettings.getSetting("ACME", ShipperDefaultsService.KEY_COUNTRY_CODE))
                .thenReturn(Optional.of("US"));

        var map = svc.currentValues("ACME");
        assertNotNull(map);
        assertEquals(8, map.size());

        var country = map.get(ShipperDefaultsService.KEY_COUNTRY_CODE);
        assertEquals("US", country.resolved());
        assertTrue(country.isTenantOverride());
        assertEquals("IN", country.platformValue());

        var city = map.get(ShipperDefaultsService.KEY_CITY);
        assertEquals("Bengaluru", city.resolved());
        assertFalse(city.isTenantOverride());
    }
}
