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
     * Resolution order (called by CarrierLimitService):
     *   1. exact (carrier, service, scope) — most specific
     *   2. exact (carrier, service, "BOTH")
     *   3. (carrier, null service, scope)
     *   4. (carrier, null service, "BOTH")
     */
    @Query("""
        SELECT l FROM CarrierShippingLimit l
        WHERE UPPER(l.carrierCode) = UPPER(:carrier)
          AND (:service IS NULL OR l.serviceCode = :service OR l.serviceCode IS NULL)
          AND (l.scope = :scope OR l.scope = 'BOTH')
          AND l.active = true
          AND (l.effectiveUntil IS NULL OR l.effectiveUntil > CURRENT_TIMESTAMP)
        ORDER BY
          CASE WHEN l.serviceCode = :service THEN 0 ELSE 1 END,
          CASE WHEN l.scope = :scope THEN 0 ELSE 1 END,
          l.effectiveFrom DESC
    """)
    List<CarrierShippingLimit> findResolvableLimits(@Param("carrier") String carrier,
                                                   @Param("service") String service,
                                                   @Param("scope") String scope);

    default Optional<CarrierShippingLimit> resolve(String carrier, String service, String scope) {
        List<CarrierShippingLimit> hits = findResolvableLimits(carrier, service, scope);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
