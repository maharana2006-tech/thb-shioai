package com.multiship.backend.service.events;

import com.multiship.backend.service.RateCacheService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 49 Tier 3 Fix 1 — event → cache-invalidate wiring.
 */
class RateCacheInvalidatorTest {

    @Test
    void publishedCarrierChangeInvalidatesJustThatCarrier() {
        RateCacheService cache = mock(RateCacheService.class);
        when(cache.invalidate("UPS")).thenReturn(3);
        RateCacheInvalidator inv = new RateCacheInvalidator(cache);

        inv.onConfigChange(new CarrierConfigChangedEvent("UPS", "account-upsert"));

        verify(cache).invalidate(eq("UPS"));
    }

    @Test
    void nullCarrierClearsAll() {
        RateCacheService cache = mock(RateCacheService.class);
        RateCacheInvalidator inv = new RateCacheInvalidator(cache);

        inv.onConfigChange(new CarrierConfigChangedEvent(null, "global-setting-changed"));

        // invalidate(null) is the "clear-all" signal per RateCacheService contract.
        verify(cache).invalidate(null);
    }
}
