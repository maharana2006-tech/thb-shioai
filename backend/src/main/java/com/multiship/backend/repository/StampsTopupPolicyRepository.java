package com.multiship.backend.repository;

import com.multiship.backend.model.StampsTopupPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StampsTopupPolicyRepository extends JpaRepository<StampsTopupPolicyEntity, Long> {

    List<StampsTopupPolicyEntity> findAllByEnabledTrue();

    Optional<StampsTopupPolicyEntity> findByCarrierAccountRefId(Long carrierAccountRefId);
}
