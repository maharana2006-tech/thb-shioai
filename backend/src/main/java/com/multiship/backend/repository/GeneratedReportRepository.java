package com.multiship.backend.repository;

import com.multiship.backend.model.GeneratedReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {

    List<GeneratedReport> findTop50ByOrderByGeneratedAtDesc();

    List<GeneratedReport> findByTenantIdOrderByGeneratedAtDesc(String tenantId);

    /**
     * Audit R2 #362 — nightly retention: null the heavy {@code csv_bytes}
     * blob for rows generated before {@code cutoff}. Metadata row stays
     * so operators still see "we ran this on YYYY-MM-DD" but the multi-MB
     * CSV is reclaimed. Users needing an old export re-run the schedule.
     */
    @Modifying
    @Query("UPDATE GeneratedReport g SET g.csvBytes = NULL "
            + "WHERE g.generatedAt < :cutoff AND g.csvBytes IS NOT NULL")
    int nullifyCsvBytesOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Audit R2 #362 — nightly retention: hard-delete rows generated
     * before {@code cutoff}.
     */
    @Modifying
    @Query("DELETE FROM GeneratedReport g WHERE g.generatedAt < :cutoff")
    int deleteRowsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
