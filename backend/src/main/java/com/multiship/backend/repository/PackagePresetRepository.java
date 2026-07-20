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
}
