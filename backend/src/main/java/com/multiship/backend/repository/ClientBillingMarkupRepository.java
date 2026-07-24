package com.multiship.backend.repository;

import com.multiship.backend.model.ClientBillingMarkup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientBillingMarkupRepository extends JpaRepository<ClientBillingMarkup, String> {

    Optional<ClientBillingMarkup> findByClientCodeIgnoreCase(String clientCode);
}
