package com.multiship.backend.repository;

import com.multiship.backend.model.StampsFunding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StampsFundingRepository extends JpaRepository<StampsFunding, Long> {

    List<StampsFunding> findTop20ByCarrierAccountRefIdOrderByCreatedAtDesc(Long carrierAccountRefId);

    Optional<StampsFunding> findByIdempotencyKey(String idempotencyKey);
}
