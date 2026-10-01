package com.multiship.backend.repository;

import com.multiship.backend.model.DtcGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface DtcGenerationJobRepository extends JpaRepository<DtcGenerationJob, Long> {

    /**
     * Worker claim — oldest QUEUED row first, skipping ones other nodes hold.
     * No @Lock: a lock mode is illegal on a native query; the FOR UPDATE
     * SKIP LOCKED clause in the SQL itself provides the row lock.
     */
    @Query(value = """
        SELECT * FROM dtc_generation_job
        WHERE status = 'QUEUED'
        ORDER BY queued_at ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<DtcGenerationJob> lockNextQueued();

    /** Requeue jobs whose worker died mid-run (heartbeat older than 10 min). */
    @Query("""
        UPDATE DtcGenerationJob j SET j.status = 'QUEUED', j.workerId = NULL,
               j.startedAt = NULL, j.heartbeatAt = NULL
        WHERE j.status = 'RUNNING' AND j.heartbeatAt < :cutoff
        """)
    @org.springframework.data.jpa.repository.Modifying
    int requeueStaleRunning(@Param("cutoff") java.time.LocalDateTime cutoff);

    /** Liveness tick for every job this worker currently holds. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE DtcGenerationJob j SET j.heartbeatAt = :now
        WHERE j.status = 'RUNNING' AND j.workerId = :workerId
        """)
    int heartbeat(@Param("workerId") String workerId, @Param("now") java.time.LocalDateTime now);

    /** True when an active job already exists for this batch (dedupe enqueue). */
    boolean existsByTenantIdAndBatchIdAndStatusIn(String tenantId, BigDecimal batchId,
                                                  java.util.Collection<String> statuses);

    /** Latest job for a batch — the progress poll target after enqueue. */
    Optional<DtcGenerationJob> findFirstByTenantIdAndBatchIdOrderByQueuedAtDesc(
            String tenantId, BigDecimal batchId);
}
