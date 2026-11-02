package com.multiship.backend.repository;

import com.multiship.backend.model.ImportStagingUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImportStagingUploadRepository extends JpaRepository<ImportStagingUpload, Long> {

    /** The newest still-open upload of the same content — re-uploading a file resumes it. */
    Optional<ImportStagingUpload> findFirstByContentHashAndStatusOrderByIdDesc(String contentHash, String status);

    /**
     * BUG-046 — pessimistic-write lock on the staging-upload row so two
     * concurrent {@code /staging/{id}/save} requests serialise. The
     * loser sees the first saver's {@code lastSavedBatchId} set + its
     * staging-row {@code savedBatchId} entries populated, and the
     * {@code saveStaging} path's existing "already saved" guard trips
     * with a 422. Must be called from inside a {@code @Transactional}
     * boundary so the lock is held until the save commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ImportStagingUpload u WHERE u.id = :id")
    Optional<ImportStagingUpload> findByIdForUpdate(@Param("id") Long id);

    /** A user's uploads in one state, newest first ("Waiting in staging" list). */
    java.util.List<ImportStagingUpload> findByCreatedByIgnoreCaseAndStatusOrderByIdDesc(String createdBy, String status);

    /** Expiry sweep; staged rows go with their upload (ON DELETE CASCADE). */
    @Transactional
    @Modifying
    @Query("DELETE FROM ImportStagingUpload u WHERE u.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
