package com.multiship.backend.repository;

import com.multiship.backend.model.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /** Newest imports first for the Data History list. */
    List<ImportBatch> findAllByOrderByIdDesc();

    /** Live (non-deleted) imports, newest first — the normal Data History list. */
    List<ImportBatch> findAllByDeletedAtIsNullOrderByIdDesc();

    /** Soft-deleted imports, newest first — the Trash view. */
    List<ImportBatch> findAllByDeletedAtIsNotNullOrderByIdDesc();

    /** Most recent LIVE batch that carries an identical content hash — used to
     *  reject re-uploading the same file. A soft-deleted batch does not block a
     *  re-upload, so the operator can delete and re-import a corrected file. */
    Optional<ImportBatch> findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(String contentHash);
}
