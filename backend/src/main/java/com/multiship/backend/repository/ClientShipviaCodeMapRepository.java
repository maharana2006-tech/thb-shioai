package com.multiship.backend.repository;

import com.multiship.backend.model.ClientShipviaCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientShipviaCodeMapRepository extends JpaRepository<ClientShipviaCodeMap, Long> {

    List<ClientShipviaCodeMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientShipviaCodeMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);

    /** Every client's alias for one ERP code — the cascade behind a rule delete. */
    List<ClientShipviaCodeMap> findByErpCodeIgnoreCase(String erpCode);

    /** Aliases pointing at one catalog service — what stops resolving if it is switched off. */
    long countByServiceId(Long serviceId);
}
