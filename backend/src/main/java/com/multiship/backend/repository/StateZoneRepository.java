package com.multiship.backend.repository;

import com.multiship.backend.model.StateZoneEntity;
import com.multiship.backend.model.StateZoneEntity.StateZoneId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StateZoneRepository extends JpaRepository<StateZoneEntity, StateZoneId> {
}
