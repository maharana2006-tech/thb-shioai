package com.multiship.backend.repository;

import com.multiship.backend.model.ShippingService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingServiceRepository extends JpaRepository<ShippingService, Long> {

    List<ShippingService> findAllByOrderByCarrierAscSortOrderAsc();

    List<ShippingService> findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(String carrier);

    Optional<ShippingService> findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(String carrier, String serviceCode);
}
