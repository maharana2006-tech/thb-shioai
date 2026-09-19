package com.multiship.backend.repository;

import com.multiship.backend.model.InvoiceCopies;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PR-Printer-R7a — CRUD for the invoice_copies table. See V70.
 */
@Repository
public interface InvoiceCopiesRepository extends JpaRepository<InvoiceCopies, Long> {

    /**
     * Exact-match lookup for the service's resolveCopies chain.
     * A NULL {@code clientCode} matches the tenant-default row for the carrier.
     */
    @Query("SELECT ic FROM InvoiceCopies ic "
            + "WHERE (:clientCode IS NULL AND ic.clientCode IS NULL "
            + "       OR UPPER(ic.clientCode) = UPPER(:clientCode)) "
            + "  AND UPPER(ic.carrierCode) = UPPER(:carrierCode)")
    Optional<InvoiceCopies> findRule(@Param("clientCode") String clientCode,
                                     @Param("carrierCode") String carrierCode);

    /**
     * All rules sorted so the FE can render "Default" rows above client rows.
     * NULL client_code first (Postgres NULLS FIRST is default on ASC).
     */
    @Query("SELECT ic FROM InvoiceCopies ic "
            + "ORDER BY ic.clientCode NULLS FIRST, ic.carrierCode ASC")
    List<InvoiceCopies> findAllOrdered();
}
