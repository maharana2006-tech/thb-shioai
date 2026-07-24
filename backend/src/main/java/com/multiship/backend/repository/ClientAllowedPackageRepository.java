package com.multiship.backend.repository;

import com.multiship.backend.model.ClientAllowedPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAllowedPackageRepository extends JpaRepository<ClientAllowedPackage, Long> {

    List<ClientAllowedPackage> findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(String clientCode);

    Optional<ClientAllowedPackage> findByClientCodeIgnoreCaseAndPresetId(String clientCode, Long presetId);

    Optional<ClientAllowedPackage> findByClientCodeIgnoreCaseAndIsDefaultTrue(String clientCode);

    boolean existsByClientCodeIgnoreCaseAndPresetId(String clientCode, Long presetId);
}
