package com.multiship.backend.service.carrier;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.TenantSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
