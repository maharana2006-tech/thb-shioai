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
}
