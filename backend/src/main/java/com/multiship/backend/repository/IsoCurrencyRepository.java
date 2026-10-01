package com.multiship.backend.repository;

import com.multiship.backend.model.IsoCurrencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IsoCurrencyRepository extends JpaRepository<IsoCurrencyEntity, String> {
}
