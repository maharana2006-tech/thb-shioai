package com.multiship.backend.service.events;

/**
 * Sprint 49 Tier 3 Fix 1 — signal that a rate-cache-invalidating
 * config change has landed.
 *
 * <p>Publish from any write path that could alter the price a
 * subsequent rate-shop returns: carrier account changes, shipping
 * service catalog edits, routing rule updates, client markup changes,
 * package preset edits, ...
 *
 * <p>{@code carrierCode == null} means "affects every carrier" (e.g.
 * a client-level markup change that layers over any carrier's price).
 */
public record CarrierConfigChangedEvent(String carrierCode, String reason) {
}
