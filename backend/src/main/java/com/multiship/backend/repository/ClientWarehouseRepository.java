package com.multiship.backend.repository;

import com.multiship.backend.model.ClientWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientWarehouseRepository extends JpaRepository<ClientWarehouse, Long> {

    /** How many clients currently attach the given warehouse. */
    long countByWarehouseId(Long warehouseId);

    /** All warehouses attached to one client (default first, then oldest-first). */
    List<ClientWarehouse> findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(String clientCode);

    /** Every client currently attached to a given warehouse. */
    List<ClientWarehouse> findByWarehouseId(Long warehouseId);

    /** Existing link — used for de-dupe on attach and for detach lookup. */
    Optional<ClientWarehouse> findByClientCodeIgnoreCaseAndWarehouseId(String clientCode, Long warehouseId);

    /** The current default for a client, when one exists. */
    Optional<ClientWarehouse> findByClientCodeIgnoreCaseAndIsDefaultTrue(String clientCode);
}
