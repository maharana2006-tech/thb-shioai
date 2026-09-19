package com.multiship.backend.repository;

import com.multiship.backend.model.PrinterScanAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for the per-tenant printer scan agent enrollment. See
 * {@link PrinterScanAgent} + V67 + design doc.
 */
@Repository
public interface PrinterScanAgentRepository extends JpaRepository<PrinterScanAgent, Long> {

    /** Enrollment lookup — used to enforce the (tenant, agent_id) unique
     *  constraint at the service layer before hitting the DB. */
    Optional<PrinterScanAgent> findByTenantCodeAndAgentId(String tenantCode, String agentId);

    /** Agent's own long-poll — matches on the raw key's SHA-256 hash. */
    Optional<PrinterScanAgent> findByApiKeyHash(String apiKeyHash);

    /** Admin surface: list every agent enrolled by a tenant so the
     *  /settings/printers page can show "warehouse-north was last
     *  seen 2 min ago". */
    List<PrinterScanAgent> findByTenantCodeAndActiveTrueOrderByEnrolledAtDesc(String tenantCode);

    /** PR-Printer-P4b — feeds {@code PrinterScanAgentMetrics}, which
     *  re-emits a {@code printer_scan_agent_last_seen_seconds} gauge
     *  every 15s. Sorted so gauge rows are deterministic across runs. */
    List<PrinterScanAgent> findByActiveTrueOrderByTenantCodeAscAgentIdAsc();

    /** PR-Printer-R1 — revoked-agents list for the FE Scanners tab
     *  (R4). Newest-revoked first so accidental-click recovery is
     *  quick. Only inactive rows; revoke row is the sink state. */
    List<PrinterScanAgent> findByTenantCodeAndActiveFalseOrderByRevokedAtDesc(String tenantCode);
}
