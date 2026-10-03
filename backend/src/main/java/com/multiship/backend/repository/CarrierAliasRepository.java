package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierAliasRepository extends JpaRepository<CarrierAliasEntity, String> {
}
