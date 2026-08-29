package com.multiship.backend.repository;

import com.multiship.backend.model.ServicePackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServicePackageRepository extends JpaRepository<ServicePackage, Long> {

    List<ServicePackage> findByServiceId(Long serviceId);

    void deleteByServiceId(Long serviceId);

    /**
     * Sprint 52 — used by PackagingCompatibilityGuard on the manual-pick
     * label path to check whether an operator-picked (service, preset) pair
     * is actually linked. Cheaper than {@link #findByServiceId} + iterate
     * because it stops at the first match and returns a boolean instead of
     * materialising entities.
     */
    boolean existsByServiceIdAndPresetId(Long serviceId, Long presetId);

    /**
     * Sprint 52 — used to distinguish "service has no links at all" (config
     * incomplete → SERVICE_HAS_NO_LINKED_PACKAGES) from "service has links
     * but this preset isn't one of them" (PACKAGE_NOT_ALLOWED_FOR_SERVICE).
     */
    long countByServiceId(Long serviceId);
}
