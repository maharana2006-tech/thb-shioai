package com.multiship.backend.repository;

import com.multiship.backend.model.CountryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CountryRepository extends JpaRepository<CountryEntity, String> {

    /** V111 — rows the platform treats as US territories. */
    List<CountryEntity> findByIsUsTerritoryTrue();
}
