package com.multiship.backend.repository;

import com.multiship.backend.model.ShipViaMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShipViaMappingRepository extends JpaRepository<ShipViaMapping, Long> {

    /** All rules for one order ship-method code (several may exist, by client/destination). */
    List<ShipViaMapping> findByShipviaCdIgnoreCase(String shipviaCd);

    List<ShipViaMapping> findAllByOrderByShipviaCdAsc();

    List<ShipViaMapping> findByServiceId(Long serviceId);
}
