package com.multiship.backend.repository;

import com.multiship.backend.model.BulkLabelJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BulkLabelJobRepository extends JpaRepository<BulkLabelJob, Long> {

    List<BulkLabelJob> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    /**
     * Bulk MEDIUM #9 + #14 fix — lightweight projection for the status
     * endpoint so a 2-second FE poll doesn't drag a 130 MB base64 ZIP
     * over the wire on every request. Selects every field EXCEPT
     * {@code resultZipBase64}, replacing it with a boolean that indicates
     * whether the ZIP is populated (drives the {@code downloadable} DTO
     * field the FE uses to enable the Download button).
     *
     * <p>Native query so the SELECT list is explicit — Hibernate's
     * default {@code findById} loads the whole row including all
     * columns, and marking a specific column {@code @Basic(fetch=LAZY)}
     * requires bytecode instrumentation the build doesn't have.
     */
    @Query(value = """
        SELECT b.id AS id, b.status AS status, b.total_count AS totalCount,
               b.successful_count AS successfulCount, b.failed_count AS failedCount,
               b.failure_message AS failureMessage, b.created_at AS createdAt,
               b.started_at AS startedAt, b.completed_at AS completedAt,
               b.order_numbers AS orderNumbers, b.requested_by AS requestedBy,
               (b.result_zip_base64 IS NOT NULL AND LENGTH(b.result_zip_base64) > 0) AS hasResultZip
          FROM bulk_label_jobs b
         WHERE b.id = :id
        """, nativeQuery = true)
    Optional<BulkLabelJobSummary> findSummaryById(@Param("id") Long id);

    /**
     * Spring Data JPA interface projection — the field getters map by
     * name to the SELECT aliases in {@link #findSummaryById}. Kept as a
     * public inner interface (not a top-level DTO) because the projection
     * is only used from one place and doesn't leak beyond the repository.
     */
    interface BulkLabelJobSummary {
        Long getId();
        String getStatus();
        int getTotalCount();
        int getSuccessfulCount();
        int getFailedCount();
        String getFailureMessage();
        LocalDateTime getCreatedAt();
        LocalDateTime getStartedAt();
        LocalDateTime getCompletedAt();
        String getOrderNumbers();
        String getRequestedBy();
        boolean getHasResultZip();
    }

    /**
     * Used by the startup housekeeper (see
     * {@link com.multiship.backend.service.BulkLabelServiceImpl#reapStaleRunningJobs})
     * to find jobs left behind by a JVM crash: status=RUNNING and
     * startedAt older than the given cutoff. Ordered by id so the
     * housekeeper's log lines are stable.
     */
    List<BulkLabelJob> findByStatusAndStartedAtBeforeOrderByIdAsc(String status, LocalDateTime cutoff);
}
