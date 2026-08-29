package com.multiship.backend.repository;

import com.multiship.backend.model.ClientBillingMarkup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientBillingMarkupRepository extends JpaRepository<ClientBillingMarkup, String> {

    Optional<ClientBillingMarkup> findByClientCodeIgnoreCase(String clientCode);

    /**
     * Sprint 52 — used by ClientServiceImpl.toDTO to compute
     * hasBillingMarkup on every client list/get response. Cheaper than
     * findByClientCodeIgnoreCase since we only need the boolean; JPA
     * translates this to SELECT 1 FROM ... WHERE ... LIMIT 1.
     */
    boolean existsByClientCodeIgnoreCase(String clientCode);
}
