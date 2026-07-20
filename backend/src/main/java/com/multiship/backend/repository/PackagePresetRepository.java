package com.multiship.backend.repository;

import com.multiship.backend.model.PackagePreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagePresetRepository extends JpaRepository<PackagePreset, Long> {

    List<PackagePreset> findAllByOrderByIsDefaultDescNameAsc();

    Optional<PackagePreset> findFirstByIsDefaultTrueAndEnabledTrue();

    Optional<PackagePreset> findByNameIgnoreCase(String name);

    List<PackagePreset> findByIsDefaultTrue();

    /** Sync upsert key for carrier packaging: one row per (carrier, code, origin country). */
    Optional<PackagePreset> findByCarrierIgnoreCaseAndCarrierPackageCodeIgnoreCaseAndOriginCountryIgnoreCase(
            String carrier, String carrierPackageCode, String originCountry);
}
