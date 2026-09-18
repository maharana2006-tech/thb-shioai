package com.multiship.backend.repository;

import com.multiship.backend.model.PrinterDiscovered;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for the printer-scan snapshot rows. See {@link PrinterDiscovered}
 * + V67 + design doc.
 */
@Repository
public interface PrinterDiscoveredRepository extends JpaRepository<PrinterDiscovered, Long> {

    /** UPSERT anchor — the (tenant, host, port) uniqueness contract from V67. */
    Optional<PrinterDiscovered> findByTenantCodeAndHostAndPort(
            String tenantCode, String host, Integer port);

    /**
     * Most-recent scan snapshot for the tenant. The FE picker filters by
     * MAX(scan_seq); we return everything at OR above the max so admins
     * can compare warehouses (multi-agent) side-by-side. Callers can
     * downselect on {@code agentId} client-side if they want a single
     * warehouse's view.
     */
    List<PrinterDiscovered> findByTenantCodeOrderByScanSeqDescIdAsc(String tenantCode);

    /** Max scan_seq across all agents for this tenant. Used to allocate
     *  the next scan's sequence number monotonically. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(MAX(p.scanSeq), 0) FROM PrinterDiscovered p WHERE p.tenantCode = :tenantCode")
    long findMaxScanSeqForTenant(@org.springframework.data.repository.query.Param("tenantCode") String tenantCode);
}
