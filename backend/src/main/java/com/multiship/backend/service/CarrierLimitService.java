package com.multiship.backend.service;

import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.repository.CarrierShippingLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves per-carrier / per-service MPS + total-weight caps from the
 * {@code carrier_shipping_limit} table. Sprint 48 B2; extended in
 * Sprint 52 with direction-awareness + commodity-line cap.
 *
 * <p>Caching: hot on the shipment-create path — every call would otherwise
 * hit Postgres. In-memory ConcurrentHashMap with a 5-min TTL is plenty
 * (caps change quarterly, not per-minute).
 *
 * <p>Fallback: when no row matches, returns a high default so a missing
 * seeder can't block shipments — an ops incident, not a customer-facing
 * failure. The fallback emits a WARN so the gap is visible in logs.
 *
 * <p>Resolution order (most-specific first, mirrored in
 * {@link CarrierShippingLimitRepository#findResolvableLimits}):
 * <ol>
 *   <li>(carrier, service, scope, direction)</li>
 *   <li>(carrier, service, scope, direction=NULL)</li>
 *   <li>(carrier, null-service, scope, direction)</li>
 *   <li>(carrier, null-service, scope, direction=NULL)</li>
 *   <li>(carrier, null-service, scope='BOTH', …)</li>
 *   <li>synthesised default (log WARN)</li>
 * </ol>
 *
 * <p>TODO(sprint52): US → PR (Puerto Rico) return caps are direction=RETURN
 * PLUS origin/destination-inspected. Seeding here isn't enough — needs a
 * runtime check when direction=RETURN AND origin=US AND dest=PR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierLimitService {

    /** Very high fallback so absent config never blocks a real shipment. */
    private static final int DEFAULT_MAX_PACKAGES = 10_000;
    /** Documented carrier ceiling worst-case — every real seed row overrides
     *  this. Only fires when a carrier row exists but predates Sprint 52
     *  (max_commodities left NULL) OR the whole row is missing. */
    private static final int DEFAULT_MAX_COMMODITIES = 999;
    /** Time-to-live for the in-memory cache (millis). Caps change quarterly
     *  in reality, so 5 min is comfortable. */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final CarrierShippingLimitRepository repository;

    private record Cached(CarrierShippingLimit value, long expiresAt) {}
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Legacy 3-arg entry point (Sprint 48). Delegates with direction=null
     * so the resolver picks the least-specific direction row (typically
     * still the carrier's default cap).
     */
    public CarrierShippingLimit resolveLimit(String carrierCode, String serviceCode, boolean international) {
        return resolveLimit(carrierCode, serviceCode, international, null);
    }

    /**
     * Sprint 52 direction-aware entry point.
     *
     * @param carrierCode UPS/FEDEX/DHL/STAMPS (canonical)
     * @param serviceCode Specific service (FEDEX_ENVELOPE, FEDEX_GROUND, …).
     *                    Null = ask for the carrier default.
     * @param international true → scope=INTERNATIONAL, else DOMESTIC.
     * @param direction FORWARD | RETURN | null. Null matches any direction
     *                   (used by callers that can't tell forward from return).
     */
    public CarrierShippingLimit resolveLimit(String carrierCode, String serviceCode,
                                              boolean international, String direction) {
        String scope = international ? "INTERNATIONAL" : "DOMESTIC";
        String normDirection = direction == null ? null : direction.trim().toUpperCase();
        String key = carrierCode + "|" + (serviceCode == null ? "_" : serviceCode)
                + "|" + scope + "|" + (normDirection == null ? "_" : normDirection);
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt > now) {
            return hit.value;
        }
        CarrierShippingLimit resolved = repository.resolve(carrierCode, serviceCode, scope, normDirection)
                .orElseGet(() -> fallback(carrierCode, serviceCode, scope, normDirection));
        cache.put(key, new Cached(resolved, now + CACHE_TTL_MS));
        return resolved;
    }

    /** Sprint 52 — accessor with a safe default when the seed row predates
     *  the max_commodities column. */
    public int maxCommoditiesOf(CarrierShippingLimit limit) {
        if (limit == null || limit.getMaxCommodities() == null) return DEFAULT_MAX_COMMODITIES;
        return limit.getMaxCommodities();
    }

    /** Invalidate the cache — call from admin endpoints when the table is edited. */
    public void invalidateCache() {
        cache.clear();
    }

    /** Synthesised row when no config exists — never blocks. */
    private CarrierShippingLimit fallback(String carrierCode, String serviceCode,
                                          String scope, String direction) {
        // WARN (was DEBUG): ops needs to know when a carrier is running
        // without an explicit cap. Prod-scale operators will grep for this
        // to find missing seed rows after a new service is enabled.
        log.warn("No carrier_shipping_limit row for {}/{}/{}/{}, using fallback (packages={}, commodities={}).",
                carrierCode, serviceCode, scope, direction,
                DEFAULT_MAX_PACKAGES, DEFAULT_MAX_COMMODITIES);
        return CarrierShippingLimit.builder()
                .carrierCode(carrierCode)
                .serviceCode(serviceCode)
                .scope(scope)
                .direction(direction)
                .maxPackages(DEFAULT_MAX_PACKAGES)
                .maxCommodities(DEFAULT_MAX_COMMODITIES)
                .maxTotalWeightLb(null)
                .effectiveFrom(LocalDateTime.now())
                .active(true)
                .notes("synthetic fallback — no seed row found")
                .build();
    }

    /**
     * True when the given package count + total weight exceeds the resolved
     * cap. Total weight is compared in LB; caller converts.
     */
    public boolean requiresSplit(CarrierShippingLimit limit, int packageCount, BigDecimal totalWeightLb) {
        if (limit == null) return false;
        if (limit.getMaxPackages() != null && packageCount > limit.getMaxPackages()) return true;
        if (limit.getMaxTotalWeightLb() != null && totalWeightLb != null
                && totalWeightLb.compareTo(limit.getMaxTotalWeightLb()) > 0) return true;
        return false;
    }
}
