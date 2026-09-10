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

    /** Same lookup including trashed batches — a WMS re-fetch after the operator
     *  trashed the batch must still warn that its orders live on. */
    Optional<ImportBatch> findFirstByContentHashOrderByIdDesc(String contentHash);

    /** Most recent LIVE batch with the same file name — re-saving an edited
     *  version of the same file updates this entry in place instead of piling
     *  up duplicate same-name rows in Import history. */
    Optional<ImportBatch> findFirstByFileNameIgnoreCaseAndDeletedAtIsNullOrderByIdDesc(String fileName);

    /**
     * Labelled orders whose customer reference (bulk orderRef / reference,
     * manual reference) is one of {@code refs} (upper-cased) — the live-state
     * half of the importer's duplicate-orderRef advisory. Lives here rather
     * than on OrderRepository, whose lock mode applies to every query on it
     * and is rejected for native / read-only selects.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT b.order_no, b.customer_ref
        FROM label_batch b
        WHERE UPPER(b.customer_ref) IN (:refs)
          AND UPPER(b.order_status) = 'GENERATED'
        """, nativeQuery = true)
    List<Object[]> findGeneratedOrdersByCustomerRefIn(
            @org.springframework.data.repository.query.Param("refs") java.util.Collection<String> refs);

    /**
     * Atomic status transition — the anti-race gate for
     * {@link com.multiship.backend.service.OrderImportServiceImpl#generateLabelsForBatch}.
     * Sets status=newStatus only if the current row status is currently
     * one of {@code allowedFromStatuses}. Returns the number of rows
     * updated: 0 means "someone else already flipped it" and the caller
     * MUST bail (409 BATCH_ALREADY_GENERATING). 1 means "we won the race
     * and now own the batch".
     *
     * <p>Uses UPPER() on both sides so a case-drifted stored value doesn't
     * silently skip the guard. The whole point is that this UPDATE runs
     * as a single SQL statement so two concurrent JVMs still serialize
     * on Postgres's per-row lock.
     */
    @Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ImportBatch b SET b.status = :newStatus "
            + "WHERE b.id = :id AND UPPER(b.status) IN :allowedFromStatuses")
    int atomicallyTransitionStatus(@Param("id") Long id,
                                    @Param("newStatus") String newStatus,
                                    @Param("allowedFromStatuses") java.util.Collection<String> allowedFromStatuses);

    /**
     * Startup housekeeper query — any batch left in status=IN_PROGRESS or
     * GENERATING when the JVM boots is a crash victim. The service flips
     * these back to a terminal state and leaves a note in the batch
     * metadata so operators know why the generation stopped.
     */
    List<ImportBatch> findByStatusInOrderByIdAsc(java.util.Collection<String> statuses);
}
