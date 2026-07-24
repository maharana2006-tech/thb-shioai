package com.multiship.backend.repository;

import com.multiship.backend.model.ClientShippingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientShippingPolicyRepository extends JpaRepository<ClientShippingPolicy, String> {

    /** clientCode is the PK, but ignore-case for callers. */
    Optional<ClientShippingPolicy> findByClientCodeIgnoreCase(String clientCode);
}
