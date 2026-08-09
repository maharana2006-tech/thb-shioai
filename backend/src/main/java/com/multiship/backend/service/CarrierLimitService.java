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
 * {@code carrier_shipping_limit} table. Sprint 48 B2.
 *
 * <p>Caching: hot on the shipment-create path — every call would otherwise
 * hit Postgres. Spring cache with a 5-min TTL is plenty (caps change
 * quarterly, not per-minute). Cache name matches
 * {@code CacheConfig#CARRIER_LIMITS}.
 *
 * <p>Fallback: when no row matches, returns a high default (10,000 pkgs,
 * no weight cap) so a missing seeder can't block shipments — an ops
 * incident, not a customer-facing failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierLimitService {

    /** Very high fallback so absent config never blocks a real shipment. */
    private static final int DEFAULT_MAX_PACKAGES = 10_000;
    /** Time-to-live for the in-memory cache (millis). Caps change quarterly
     *  in reality, so 5 min is comfortable. */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final CarrierShippingLimitRepository repository;

    private record Cached(CarrierShippingLimit value, long expiresAt) {}
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * @param carrierCode UPS/FEDEX/DHL/STAMPS (canonical)
     * @param serviceCode Specific service (FEDEX_ENVELOPE, FEDEX_GROUND, etc.).
     *                    Null = ask for the carrier default.
     * @param international true → scope=INTERNATIONAL, else DOMESTIC.
     */
    public CarrierShippingLimit resolveLimit(String carrierCode, String serviceCode, boolean international) {
        String scope = international ? "INTERNATIONAL" : "DOMESTIC";
        String key = carrierCode + "|" + (serviceCode == null ? "_" : serviceCode) + "|" + scope;
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt > now) {
            return hit.value;
        }
        CarrierShippingLimit resolved = repository.resolve(carrierCode, serviceCode, scope)
                .orElseGet(() -> fallback(carrierCode, serviceCode, scope));
        cache.put(key, new Cached(resolved, now + CACHE_TTL_MS));
        return resolved;
    }

    /** Invalidate the cache — call from admin endpoints when the table is edited. */
    public void invalidateCache() {
        cache.clear();
    }

    /** Synthesised row when no config exists — never blocks. */
    private CarrierShippingLimit fallback(String carrierCode, String serviceCode, String scope) {
        log.debug("No carrier_shipping_limit row for {}/{}/{}, using fallback (unlimited).",
                carrierCode, serviceCode, scope);
        return CarrierShippingLimit.builder()
                .carrierCode(carrierCode)
                .serviceCode(serviceCode)
                .scope(scope)
                .maxPackages(DEFAULT_MAX_PACKAGES)
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
