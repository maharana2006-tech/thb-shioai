package com.multiship.backend.repository;

import com.multiship.backend.model.ImportGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImportGenerationJobRepository extends JpaRepository<ImportGenerationJob, Long> {

    List<String> ACTIVE = List.of(ImportGenerationJob.QUEUED, ImportGenerationJob.RUNNING);

    /**
     * Lock the oldest queued job. SKIP LOCKED lets any number of workers (on any
     * number of servers) poll at once without two of them claiming the same job.
     * Must run inside a transaction that also marks the job RUNNING — the lock
     * is released at commit.
     */
    @Query(value = "SELECT * FROM import_generation_job WHERE status = 'QUEUED' "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ImportGenerationJob> lockNextQueued();

    Optional<ImportGenerationJob> findFirstByImportBatchIdOrderByIdDesc(Long importBatchId);

    Optional<ImportGenerationJob> findFirstByImportBatchIdAndStatusInOrderByIdDesc(
            Long importBatchId, Collection<String> statuses);

    boolean existsByImportBatchIdAndStatusIn(Long importBatchId, Collection<String> statuses);

    /** Progress write from the running worker; also counts as a heartbeat. */
    @Modifying
    @Transactional
    @Query("UPDATE ImportGenerationJob j SET j.progressDone = :done, j.progressTotal = :total, "
            + "j.note = :note, j.heartbeatAt = :now WHERE j.id = :id")
    int updateProgress(@Param("id") Long id, @Param("done") int done, @Param("total") int total,
                       @Param("note") String note, @Param("now") LocalDateTime now);

    /** Liveness for every job this worker is running, independent of progress. */
    @Modifying
    @Transactional
    @Query("UPDATE ImportGenerationJob j SET j.heartbeatAt = :now "
            + "WHERE j.workerId = :worker AND j.status = 'RUNNING'")
    int heartbeat(@Param("worker") String worker, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE ImportGenerationJob j SET j.cancelRequested = true "
            + "WHERE j.importBatchId = :batchId AND j.status IN ('QUEUED', 'RUNNING')")
    int requestCancel(@Param("batchId") Long batchId);

    @Query("SELECT j.cancelRequested FROM ImportGenerationJob j WHERE j.id = :id")
    Boolean isCancelRequested(@Param("id") Long id);

    /**
     * Crash recovery: a RUNNING job whose worker stopped heart-beating goes back
     * on the queue. The rerun is safe — generation first syncs rows with the
     * orders the label batch already holds, so labelled orders aren't re-sent.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ImportGenerationJob j SET j.status = 'QUEUED', j.workerId = null "
            + "WHERE j.status = 'RUNNING' AND (j.heartbeatAt IS NULL OR j.heartbeatAt < :cutoff)")
    int requeueStale(@Param("cutoff") LocalDateTime cutoff);
}
