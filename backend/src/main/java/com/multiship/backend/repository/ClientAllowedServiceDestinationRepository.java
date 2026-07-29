package com.multiship.backend.repository;

import com.multiship.backend.model.ClientAllowedServiceDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientAllowedServiceDestinationRepository extends JpaRepository<ClientAllowedServiceDestination, Long> {

    List<ClientAllowedServiceDestination> findByAllowedServiceIdOrderByCountryAsc(Long allowedServiceId);

    List<ClientAllowedServiceDestination> findByClientCodeIgnoreCase(String clientCode);

    /** Replace-PUT support: clear before re-inserting the requested set. */
    @Modifying
    @Query("delete from ClientAllowedServiceDestination d where d.allowedServiceId = :allowedServiceId")
    void deleteAllByAllowedServiceId(@Param("allowedServiceId") Long allowedServiceId);

    /** Cascade helper: fires when the parent allowlist row (or the client) goes away. */
    @Modifying
    @Query("delete from ClientAllowedServiceDestination d where upper(d.clientCode) = upper(:clientCode)")
    void deleteAllByClientCode(@Param("clientCode") String clientCode);
}
