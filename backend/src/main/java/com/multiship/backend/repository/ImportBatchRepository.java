package com.multiship.backend.repository;

import com.multiship.backend.model.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /** Newest imports first for the Data History list. */
    List<ImportBatch> findAllByOrderByIdDesc();

    /**
     * Audit R2 #330 — nightly retention: null the heavy {@code rows_json}
     * blob for batches created before {@code cutoff}. Metadata row (who
     * imported when, how many rows) stays so the Data History list still
     * shows the audit trail; only the full row payload is reclaimed.
     */
    @Modifying
    @Query("UPDATE ImportBatch b SET b.rowsJson = NULL "
            + "WHERE b.createdAt < :cutoff AND b.rowsJson IS NOT NULL")
    int nullifyRowsJsonOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Audit R2 #330 — nightly retention: hard-delete batches created
     * before {@code cutoff}.
     */
    @Modifying
    @Query("DELETE FROM ImportBatch b WHERE b.createdAt < :cutoff")
    int deleteRowsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** Live (non-deleted) imports, newest first — the normal Data History list. */
    List<ImportBatch> findAllByDeletedAtIsNullOrderByIdDesc();

    /** Soft-deleted imports, newest first — the Trash view. */
    List<ImportBatch> findAllByDeletedAtIsNotNullOrderByIdDesc();

    /** Most recent LIVE batch that carries an identical content hash — used to
     *  reject re-uploading the same file. A soft-deleted batch does not block a
     *  re-upload, so the operator can delete and re-import a corrected file. */
    Optional<ImportBatch> findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(String contentHash);
}
