package com.multiship.backend.repository;

import com.multiship.backend.model.PrinterTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PR-Printer-R8a — printer_tags M2M CRUD + autocomplete surface.
 */
@Repository
public interface PrinterTagRepository extends JpaRepository<PrinterTag, Long> {

    List<PrinterTag> findByPrinterIdOrderByTagAsc(Long printerId);

    void deleteByPrinterId(Long printerId);

    /** Autocomplete surface: distinct tags across every registered
     *  printer, sorted alphabetically. Cheap under the current tag
     *  volume; add a caching layer if this becomes a hot path. */
    @Query("SELECT DISTINCT t.tag FROM PrinterTag t ORDER BY t.tag ASC")
    List<String> findDistinctTags();

    /** All rows for a set of printer IDs — feeds the FE table's chip
     *  column in one round-trip. */
    List<PrinterTag> findByPrinterIdInOrderByPrinterIdAscTagAsc(java.util.Collection<Long> printerIds);
}
