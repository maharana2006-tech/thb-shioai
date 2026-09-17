package com.multiship.backend.repository;

import com.multiship.backend.model.ImportBatchRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportBatchRowRepository extends JpaRepository<ImportBatchRow, Long> {

    /**
     * Find all rows for a specific import batch, ordered by order_ref descending
     */
    @Query("SELECT r FROM ImportBatchRow r WHERE r.importBatch.id = :importBatchId ORDER BY r.orderRef DESC")
    List<ImportBatchRow> findByImportBatchId(@Param("importBatchId") Long importBatchId);

    /**
     * Count rows for a specific import batch
     */
    long countByImportBatchId(Long importBatchId);

    /**
     * Find rows with errors for a specific batch
     */
    @Query("SELECT r FROM ImportBatchRow r WHERE r.importBatch.id = :importBatchId AND r.errors IS NOT NULL AND r.errors != '' ORDER BY r.orderRef DESC")
    List<ImportBatchRow> findRowsWithErrorsByBatchId(@Param("importBatchId") Long importBatchId);

    /**
     * Find rows by order reference
     */
    List<ImportBatchRow> findByOrderRef(String orderRef);

    /**
     * Find rows by carrier code and batch
     */
    List<ImportBatchRow> findByCarrierCodeAndImportBatchId(String carrierCode, Long importBatchId);
}
