package com.multiship.backend.repository;

import com.multiship.backend.model.CountryRegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRegionRepository extends JpaRepository<CountryRegionEntity, String> {
}
