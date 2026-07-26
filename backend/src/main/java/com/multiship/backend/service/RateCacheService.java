package com.multiship.backend.service;

import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 39 — cache identical rate quotes for a short TTL. The
 * {@link RateShopService} fan-out hits four carriers per request; when
 * the same shipment envelope comes back (user hits Refresh, or picker
 * open/close), we don't need to call the carriers again — rates are
 * stable over a few minutes.
 *
 * <p>Key = carrierCode + lane fingerprint (origin+destination postal +
 * country, weight + package type, requested service type). Value =
 * carrier's rate options list. TTL = 5 minutes by default so cross-
 * business-day fluctuations don't linger.
 *
 * <p>Empty rate lists are NOT cached — a fresh request should retry
 * the carrier in case the previous miss was transient.
 */
public interface RateCacheService {

    /** Look up a cached rate list. Returns empty when no entry, when
     *  expired, OR when the previous entry was empty. */
    Optional<CachedRates> lookup(String carrierCode, ShipmentRequestDTO shipment);

    /** Store a non-empty rate list. Empty inputs are silently ignored. */
    void store(String carrierCode, ShipmentRequestDTO shipment, List<RateOption> options);

    /** Bust cached entries. Null carrier = clear everything; non-null
     *  = clear only that carrier's entries. Returns the number of
     *  entries removed. */
    int invalidate(String carrierCode);

    /** Point-in-time counters for observability. */
    CacheStats stats();

    /**
     * Cached rate list plus the moment it was cached — the service
     * uses {@link #cachedAt} to compute a "cached N seconds ago" note
     * that the frontend can render as a small badge.
     */
    record CachedRates(List<RateOption> options, LocalDateTime cachedAt) {
    }

    record CacheStats(int size, long hits, long misses, long stores, long invalidations) {
    }
}
