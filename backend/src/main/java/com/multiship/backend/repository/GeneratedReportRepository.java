package com.multiship.backend.repository;

import com.multiship.backend.model.GeneratedReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {

    List<GeneratedReport> findTop50ByOrderByGeneratedAtDesc();

    List<GeneratedReport> findByTenantIdOrderByGeneratedAtDesc(String tenantId);
}
