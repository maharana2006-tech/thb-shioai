package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierPickupTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierPickupTypeRepository
        extends JpaRepository<CarrierPickupTypeEntity, CarrierPickupTypeEntity.PK> {
}
