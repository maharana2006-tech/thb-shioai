package com.multiship.backend.repository;

import com.multiship.backend.model.ClientShipviaCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientShipviaCodeMapRepository extends JpaRepository<ClientShipviaCodeMap, Long> {

    List<ClientShipviaCodeMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientShipviaCodeMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);

    /** Audit B2 (#371) — exact upsert-match lookup. Null is a real value
     *  for destCountry / destRegion (platform-wide alias), so the
     *  {@code CAST(:x AS string) IS NULL} branch keeps JPQL honest; a
     *  bare {@code = :x} would silently miss null rows. CAST wrapper
     *  avoids the Hibernate "LOWER(bytea)" surprise on null parameters. */
    @Query("""
        SELECT m FROM ClientShipviaCodeMap m
         WHERE LOWER(m.clientCode) = LOWER(:clientCode)
           AND LOWER(m.erpCode) = LOWER(:erpCode)
           AND ((CAST(:destCountry AS string) IS NULL AND m.destCountry IS NULL)
                OR m.destCountry = :destCountry)
           AND ((CAST(:destRegion AS string) IS NULL AND m.destRegion IS NULL)
                OR m.destRegion = :destRegion)
    """)
    Optional<ClientShipviaCodeMap> findForUpsert(
            @Param("clientCode") String clientCode,
            @Param("erpCode") String erpCode,
            @Param("destCountry") String destCountry,
            @Param("destRegion") String destRegion);

    /** Every client's alias for one ERP code — the cascade behind a rule delete. */
    List<ClientShipviaCodeMap> findByErpCodeIgnoreCase(String erpCode);

    /** Aliases pointing at one catalog service — what stops resolving if it is switched off. */
    long countByServiceId(Long serviceId);

    /** V126 merge — pick the most-specific matching rule for an incoming
     *  (client, ERP code, warehouse, country, region) tuple.
     *
     *  Specificity ladder (highest wins; nulls on a column = "any"):
     *     1. client + warehouse + country
     *     2. client + warehouse + any
     *     3. client + any       + country
     *     4. client + any       + any
     *     5. global             + warehouse + country
     *     6. global             + warehouse + any
     *     7. global             + any       + country
     *     8. global             + any       + any
     *
     *  Encoded by scoring with CASE: 4 points for a client match (vs
     *  global), 2 for warehouse, 1 for country. Highest score wins.
     *  Region match is a tiebreaker (any-region rules are catch-alls).
     *
     *  CAST(:x AS string|bigint) IS NULL guards are the same pattern as
     *  findForUpsert — Hibernate otherwise picks LOWER(bytea) on null. */
    @Query("""
        SELECT m FROM ClientShipviaCodeMap m
         WHERE LOWER(m.erpCode) = LOWER(:erpCode)
           AND (m.clientCode IS NULL OR LOWER(m.clientCode) = LOWER(:clientCode))
           AND (m.warehouseId IS NULL
                OR (CAST(:warehouseId AS long) IS NOT NULL
                    AND m.warehouseId = :warehouseId))
           AND (m.destCountry IS NULL
                OR (CAST(:destCountry AS string) IS NOT NULL
                    AND m.destCountry = :destCountry))
           AND (m.destRegion IS NULL
                OR (CAST(:destRegion AS string) IS NOT NULL
                    AND m.destRegion = :destRegion))
         ORDER BY
            (CASE WHEN m.clientCode  IS NOT NULL THEN 4 ELSE 0 END
           + CASE WHEN m.warehouseId IS NOT NULL THEN 2 ELSE 0 END
           + CASE WHEN m.destCountry IS NOT NULL THEN 1 ELSE 0 END) DESC,
            CASE WHEN m.destRegion IS NOT NULL THEN 1 ELSE 0 END DESC,
            m.id ASC
    """)
    List<ClientShipviaCodeMap> findMatches(
            @Param("clientCode") String clientCode,
            @Param("erpCode") String erpCode,
            @Param("warehouseId") Long warehouseId,
            @Param("destCountry") String destCountry,
            @Param("destRegion") String destRegion);
}
