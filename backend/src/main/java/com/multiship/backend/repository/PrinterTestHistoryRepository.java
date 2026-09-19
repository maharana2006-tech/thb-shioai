package com.multiship.backend.repository;

import com.multiship.backend.model.PrinterTestHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PR-Printer-R9.5a — read/append surface for the rolling test-print
 * log. See V72 + model javadoc.
 */
@Repository
public interface PrinterTestHistoryRepository extends JpaRepository<PrinterTestHistory, Long> {

    /**
     * Newest-first for the FE panel section. Pageable so the caller
     * controls the N cap (controller applies a hard maximum too).
     */
    List<PrinterTestHistory> findByPrinterIdOrderByTestedAtDesc(Long printerId, Pageable pageable);
}
