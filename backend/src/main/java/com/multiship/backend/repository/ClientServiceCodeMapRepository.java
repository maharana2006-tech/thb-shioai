package com.multiship.backend.repository;

import com.multiship.backend.model.ClientServiceCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientServiceCodeMapRepository extends JpaRepository<ClientServiceCodeMap, Long> {

    List<ClientServiceCodeMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientServiceCodeMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);

    /** Audit B2 (#371) — exact upsert-match lookup. See
     *  {@link ClientShipviaCodeMapRepository#findForUpsert} for the
     *  CAST-wrapped null-handling rationale. */
    @Query("""
        SELECT m FROM ClientServiceCodeMap m
         WHERE LOWER(m.clientCode) = LOWER(:clientCode)
           AND LOWER(m.erpCode) = LOWER(:erpCode)
           AND ((CAST(:destCountry AS string) IS NULL AND m.destCountry IS NULL)
                OR m.destCountry = :destCountry)
           AND ((CAST(:destRegion AS string) IS NULL AND m.destRegion IS NULL)
                OR m.destRegion = :destRegion)
    """)
    Optional<ClientServiceCodeMap> findForUpsert(
            @Param("clientCode") String clientCode,
            @Param("erpCode") String erpCode,
            @Param("destCountry") String destCountry,
            @Param("destRegion") String destRegion);

    /** Aliases pointing at one catalog service — what stops resolving if it is switched off. */
    long countByServiceId(Long serviceId);
}
