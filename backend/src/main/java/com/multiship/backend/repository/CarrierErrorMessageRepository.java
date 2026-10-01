package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierErrorMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierErrorMessageRepository extends JpaRepository<CarrierErrorMessageEntity, Long> {
}
