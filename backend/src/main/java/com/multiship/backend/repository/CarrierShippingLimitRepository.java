package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierShippingLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CarrierShippingLimitRepository extends JpaRepository<CarrierShippingLimit, Long> {

    /**
     * Sprint 52 — direction-aware resolver. The service picks the most
     * specific row using this ORDER BY ranking. Six tiers, worst case:
     *   1. exact  (carrier, service, scope,  direction)  — most specific
     *   2. exact  (carrier, service, scope,  direction=NULL)
     *   3.        (carrier, null service, scope, direction)
     *   4.        (carrier, null service, scope, direction=NULL)
     *   5.        (carrier, null service, scope='BOTH', ...)
     *   6. fallback — the service synthesises a default row (never blocks)
     *
     * The query returns every row that could match; the service picks
     * the top row after sorting. Doing the tiering in SQL keeps the
     * caching-key logic simple + gives Postgres a single index-only scan.
     */
    @Query("""
        SELECT l FROM CarrierShippingLimit l
        WHERE UPPER(l.carrierCode) = UPPER(:carrier)
          AND (:service IS NULL OR l.serviceCode = :service OR l.serviceCode IS NULL)
          AND (l.scope = :scope OR l.scope = 'BOTH')
          AND (:direction IS NULL OR l.direction = :direction OR l.direction IS NULL)
          AND l.active = true
          AND (l.effectiveUntil IS NULL OR l.effectiveUntil > CURRENT_TIMESTAMP)
        ORDER BY
          CASE WHEN l.serviceCode = :service THEN 0 ELSE 1 END,
          CASE WHEN l.scope = :scope THEN 0 ELSE 1 END,
          CASE WHEN :direction IS NOT NULL AND l.direction = :direction THEN 0
               WHEN l.direction IS NULL THEN 1
               ELSE 2 END,
          l.effectiveFrom DESC
    """)
    List<CarrierShippingLimit> findResolvableLimits(@Param("carrier") String carrier,
                                                   @Param("service") String service,
                                                   @Param("scope") String scope,
                                                   @Param("direction") String direction);

    /** Legacy 3-arg version — kept so existing callers compile. Delegates
     *  with direction=null (matches any direction). */
    default List<CarrierShippingLimit> findResolvableLimits(String carrier, String service, String scope) {
        return findResolvableLimits(carrier, service, scope, null);
    }

    default Optional<CarrierShippingLimit> resolve(String carrier, String service, String scope, String direction) {
        List<CarrierShippingLimit> hits = findResolvableLimits(carrier, service, scope, direction);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    default Optional<CarrierShippingLimit> resolve(String carrier, String service, String scope) {
        return resolve(carrier, service, scope, null);
    }
}
