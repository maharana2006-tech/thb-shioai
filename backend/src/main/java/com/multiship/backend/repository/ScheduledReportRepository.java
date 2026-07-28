package com.multiship.backend.repository;

import com.multiship.backend.model.ScheduledReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledReportRepository extends JpaRepository<ScheduledReport, Long> {

    List<ScheduledReport> findByTenantIdOrderByNameAsc(String tenantId);

    List<ScheduledReport> findAllByOrderByNameAsc();

    /** Active schedules whose nextRunAt is due — the scheduler picks these up. */
    @Query("""
        SELECT s FROM ScheduledReport s
        WHERE s.active = true AND (s.nextRunAt IS NULL OR s.nextRunAt <= :now)
    """)
    List<ScheduledReport> findDue(@Param("now") LocalDateTime now);
}
