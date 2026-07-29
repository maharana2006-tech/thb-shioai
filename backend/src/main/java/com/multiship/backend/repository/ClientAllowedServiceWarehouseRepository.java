package com.multiship.backend.repository;

import com.multiship.backend.model.ClientAllowedServiceWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientAllowedServiceWarehouseRepository extends JpaRepository<ClientAllowedServiceWarehouse, Long> {

    List<ClientAllowedServiceWarehouse> findByAllowedServiceIdOrderByWarehouseIdAsc(Long allowedServiceId);

    List<ClientAllowedServiceWarehouse> findByClientCodeIgnoreCase(String clientCode);

    /** Bulk fetch across a set of allowlist rows — used when resolving allowed
     *  services for a specific warehouse. */
    @Query("select w from ClientAllowedServiceWarehouse w where w.allowedServiceId in :allowedServiceIds")
    List<ClientAllowedServiceWarehouse> findByAllowedServiceIdIn(
            @Param("allowedServiceIds") List<Long> allowedServiceIds);

    /** Replace-PUT support: clear before re-inserting the requested set. */
    @Modifying
    @Query("delete from ClientAllowedServiceWarehouse w where w.allowedServiceId = :allowedServiceId")
    void deleteAllByAllowedServiceId(@Param("allowedServiceId") Long allowedServiceId);

    /** Cascade helper: fires when the parent allowlist row (or the client) goes away. */
    @Modifying
    @Query("delete from ClientAllowedServiceWarehouse w where upper(w.clientCode) = upper(:clientCode)")
    void deleteAllByClientCode(@Param("clientCode") String clientCode);

    /** Cascade helper: fires when a warehouse is removed from the platform. */
    @Modifying
    @Query("delete from ClientAllowedServiceWarehouse w where w.warehouseId = :warehouseId")
    void deleteAllByWarehouseId(@Param("warehouseId") Long warehouseId);
}
