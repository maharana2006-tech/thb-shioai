package com.multiship.backend.repository;

import com.multiship.backend.model.ClientServiceCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientServiceCodeMapRepository extends JpaRepository<ClientServiceCodeMap, Long> {

    List<ClientServiceCodeMap> findByClientCodeIgnoreCaseOrderByErpCodeAsc(String clientCode);

    Optional<ClientServiceCodeMap> findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(String clientCode, String erpCode);

    /** Aliases pointing at one catalog service — what stops resolving if it is switched off. */
    long countByServiceId(Long serviceId);
}
