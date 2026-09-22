package com.multiship.backend.repository;

import com.multiship.backend.model.ExternalSystemClientLoginOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalSystemClientLoginOverrideRepository
        extends JpaRepository<ExternalSystemClientLoginOverride, Long> {

    Optional<ExternalSystemClientLoginOverride> findByConnectionIdAndClientCode(
            Long connectionId, String clientCode);

    List<ExternalSystemClientLoginOverride> findByConnectionId(Long connectionId);
}
