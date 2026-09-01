package com.multiship.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 39 impl. In-memory only (single-instance deploys). If we ever
 * scale horizontally, swap this out for a distributed cache (Redis, ...);
 * the interface stays stable.
 *
 * <p>Sprint 49 Tier 3 Fix 6 — swapped from plain {@code ConcurrentHashMap}
 * (unbounded, lazy expiry on read only — pathological accessor could
 * grow the cache indefinitely then never evict) to Caffeine with a
 * {@code maximumSize} + {@code expireAfterWrite}. Get/put semantics
 * are unchanged for callers.
 */
@Slf4j
@Service
public class RateCacheServiceImpl implements RateCacheService {

    /** 5-min TTL — carriers refresh rate tables ~every 15 min in practice, so
     *  5 min gives us a comfortable margin against staleness while still
     *  eliminating ≥90% of duplicate carrier calls within a picker session. */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** Hard cap on distinct lane fingerprints; well above healthy usage. */
    private static final long MAX_ENTRIES = 5_000;

    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(TTL)
            .build();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong stores = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    @Override
    public Optional<CachedRates> lookup(String carrierCode, ShipmentRequestDTO shipment) {
        String key = key(carrierCode, shipment);
        if (key == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        Entry entry = cache.getIfPresent(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        // Caffeine expireAfterWrite makes stale entries invisible via
        // getIfPresent, but keep the explicit check for the test hook
        // (putRaw with a past expiresAt below).
        if (Instant.now().isAfter(entry.expiresAt)) {
            cache.invalidate(key);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(new CachedRates(entry.options, entry.cachedAt));
    }

    @Override
    public void store(String carrierCode, ShipmentRequestDTO shipment, List<RateOption> options) {
        if (options == null || options.isEmpty()) return;
        String key = key(carrierCode, shipment);
        if (key == null) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        cache.put(key, new Entry(List.copyOf(options), now, Instant.now().plus(TTL)));
        stores.incrementAndGet();
    }

    @Override
    public int invalidate(String carrierCode) {
        if (!StringUtils.hasText(carrierCode)) {
            long removed = cache.estimatedSize();
            cache.invalidateAll();
            invalidations.incrementAndGet();
            log.info("Rate cache: cleared all {} entries", removed);
            return (int) removed;
        }
        String prefix = carrierCode.trim().toUpperCase(Locale.ROOT) + "|";
        long[] removed = {0};
        cache.asMap().keySet().removeIf(k -> {
            boolean match = k.startsWith(prefix);
            if (match) removed[0]++;
            return match;
        });
        invalidations.incrementAndGet();
        log.info("Rate cache: cleared {} entries for {}", removed[0], carrierCode);
        return (int) removed[0];
    }

    @Override
    public CacheStats stats() {
        return new CacheStats((int) cache.estimatedSize(),
                hits.get(), misses.get(), stores.get(), invalidations.get());
    }

    /**
     * Fingerprint the lane so identical shipments hit the same key.
     * Uses the fields carriers actually price on:
     *   ACCOUNT NUMBER (negotiated pricing is per-account — without it one
     *   tenant's negotiated rates were served verbatim to another tenant on
     *   the same lane within TTL), carrier, service type, package type,
     *   weight + weight unit, origin postal + country, destination postal +
     *   country, residential flag, declared value + currency.
     * Still ignored: referenceNumber / shipper name / etc. — no price impact.
     */
    static String key(String carrierCode, ShipmentRequestDTO r) {
        if (r == null || !StringUtils.hasText(carrierCode)) return null;
        String weight = r.getWeight() == null ? "" : r.getWeight().toPlainString();
        String weightUnit = r.getWeightUnit() == null ? "" : r.getWeightUnit().trim().toUpperCase(Locale.ROOT);
        return carrierCode.trim().toUpperCase(Locale.ROOT)
                + "|" + trimUpper(r.getAccountNumber())
                + "|" + trimUpper(r.getServiceType())
                + "|" + trimUpper(r.getPackageType())
                + "|" + weight
                + "|" + weightUnit
                + "|" + trimUpper(r.getShipperCountryCode())
                + "|" + trimUpper(r.getShipperPostalCode())
                + "|" + trimUpper(r.getRecipientCountryCode())
                + "|" + trimUpper(r.getRecipientPostalCode())
                + "|" + (Boolean.TRUE.equals(r.getRecipientResidential()) ? "R" : "C")
                + "|" + (r.getDeclaredValue() == null ? "" : r.getDeclaredValue().toPlainString())
                + "|" + trimUpper(r.getDeclaredValueCurrency());
    }

    private static String trimUpper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Test-only: swap the cache clock. Not on the interface. */
    void putRaw(String key, List<RateOption> options, LocalDateTime cachedAt, Instant expiresAt) {
        cache.put(key, new Entry(List.copyOf(options), cachedAt, expiresAt));
    }

    private record Entry(List<RateOption> options, LocalDateTime cachedAt, Instant expiresAt) {}
}
