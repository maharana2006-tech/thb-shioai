package com.multiship.backend.service.events;

import com.multiship.backend.service.RateCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sprint 49 Tier 3 Fix 1 — subscribes to
 * {@link CarrierConfigChangedEvent} and clears the affected slice of
 * the rate cache so the next rate-shop reflects the new config.
 *
 * <p>Prior behavior: config edits (account changes, service catalog
 * updates, routing rule tweaks) sat behind up to 5 min of stale prices
 * because the cache only invalidated on manual POST to
 * {@code /api/v1/rate-cache/invalidate}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateCacheInvalidator {

    private final RateCacheService rateCacheService;

    @EventListener
    public void onConfigChange(CarrierConfigChangedEvent event) {
        int cleared = rateCacheService.invalidate(event.carrierCode());
        log.info("Rate cache auto-invalidate — carrier={} reason={} cleared={}",
                event.carrierCode(), event.reason(), cleared);
    }
}
