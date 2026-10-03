package com.multiship.backend.repository;

import com.multiship.backend.model.ReasonForExportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReasonForExportRepository extends JpaRepository<ReasonForExportEntity, String> {
}
