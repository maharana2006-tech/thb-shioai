package com.multiship.backend.repository;

import com.multiship.backend.model.CarrierLabelFormatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierLabelFormatRepository
        extends JpaRepository<CarrierLabelFormatEntity, CarrierLabelFormatEntity.PK> {
}
