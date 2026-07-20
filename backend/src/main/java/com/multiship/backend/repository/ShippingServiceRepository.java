package com.multiship.backend.repository;

import com.multiship.backend.model.ShippingService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingServiceRepository extends JpaRepository<ShippingService, Long> {

    List<ShippingService> findAllByOrderByCarrierAscSortOrderAsc();

    List<ShippingService> findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc(String originCountry);

    List<ShippingService> findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(String carrier);

    Optional<ShippingService> findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(String carrier, String serviceCode);

    /** Sync upsert key: one row per (carrier, code, origin country). */
    Optional<ShippingService> findByCarrierIgnoreCaseAndServiceCodeIgnoreCaseAndOriginCountryIgnoreCase(
            String carrier, String serviceCode, String originCountry);

    @Query("select distinct s.originCountry from ShippingService s where s.originCountry is not null order by s.originCountry")
    List<String> findDistinctOriginCountries();
}
