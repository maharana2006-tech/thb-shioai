package com.multiship.backend.repository;

import com.multiship.backend.model.ClientAllowedService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAllowedServiceRepository extends JpaRepository<ClientAllowedService, Long> {

    /** Every service allowed for a client (default first, then oldest-first). */
    List<ClientAllowedService> findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(String clientCode);

    Optional<ClientAllowedService> findByClientCodeIgnoreCaseAndServiceId(String clientCode, Long serviceId);

    Optional<ClientAllowedService> findByClientCodeIgnoreCaseAndIsDefaultTrue(String clientCode);

    boolean existsByClientCodeIgnoreCaseAndServiceId(String clientCode, Long serviceId);
}
