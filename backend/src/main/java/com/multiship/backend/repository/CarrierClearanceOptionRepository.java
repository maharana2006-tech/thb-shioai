package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierClearanceOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierClearanceOptionRepository
        extends JpaRepository<CarrierClearanceOptionEntity, CarrierClearanceOptionEntity.PK> {
}
