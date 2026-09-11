package com.multiship.backend.repository;

import com.multiship.backend.model.ImportStagingRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ImportStagingRowRepository extends JpaRepository<ImportStagingRow, Long> {

    List<ImportStagingRow> findByUploadIdOrderByRowNoAsc(Long uploadId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ImportStagingRow r WHERE r.uploadId = :uploadId")
    int deleteAllForUpload(@Param("uploadId") Long uploadId);
}
