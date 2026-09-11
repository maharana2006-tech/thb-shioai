package com.multiship.backend.repository;

import com.multiship.backend.model.ImportStagingUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImportStagingUploadRepository extends JpaRepository<ImportStagingUpload, Long> {

    /** The newest still-open upload of the same content — re-uploading a file resumes it. */
    Optional<ImportStagingUpload> findFirstByContentHashAndStatusOrderByIdDesc(String contentHash, String status);

    /** Expiry sweep; staged rows go with their upload (ON DELETE CASCADE). */
    @Transactional
    @Modifying
    @Query("DELETE FROM ImportStagingUpload u WHERE u.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
