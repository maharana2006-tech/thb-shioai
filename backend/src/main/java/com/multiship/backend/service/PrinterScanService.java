package com.multiship.backend.service;

import com.multiship.backend.model.PrinterDiscovered;
import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.repository.PrinterDiscoveredRepository;
import com.multiship.backend.repository.PrinterScanAgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * PR-Printer-P1.5 — business logic for the LAN scan agent enrollment
 * + discovery ingestion. See {@code docs/printer-auto-detect-design.md}.
 *
 * <p>Auth model: agent keys are 32-byte SecureRandom tokens hex-encoded
 * ({@link #generateRawKey}), returned once at enrollment. Storage is
 * SHA-256 hex (not bcrypt) — the agent polls every 5s so bcrypt would
 * burn CPU on every verification; a stolen hash is still a stolen
 * secret so the cheaper hash is fine here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterScanService {

    private final PrinterScanAgentRepository agentRepository;
    private final PrinterDiscoveredRepository discoveredRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    // ================================================================
    // Enrollment (admin)
    // ================================================================

    /**
     * Enroll a new scan agent for a tenant. Returns the raw key ONCE —
     * the caller must show it to the admin and never persist it. Storage
     * is SHA-256 hex of the raw key; verification recomputes the hash
     * and compares.
     *
     * <p>Uniqueness: {@code (tenantCode, agentId)} — a customer with N
     * warehouses can enroll N agents. A duplicate throws
     * {@link IllegalStateException} so the caller returns 409.
     */
    @Transactional
    public EnrollResult enrollAgent(String tenantCode, String agentId, String hostname, String enrolledBy) {
        if (!StringUtils.hasText(tenantCode) || !StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("tenantCode + agentId are required.");
        }
        Optional<PrinterScanAgent> existing = agentRepository.findByTenantCodeAndAgentId(tenantCode, agentId);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getActive())) {
            throw new IllegalStateException(
                    "Agent " + agentId + " is already enrolled for tenant " + tenantCode + ". Revoke first or pick a different agentId.");
        }
        String rawKey = generateRawKey();
        String keyHash = sha256Hex(rawKey);

        PrinterScanAgent agent = existing.orElseGet(PrinterScanAgent::new);
        agent.setTenantCode(tenantCode);
        agent.setAgentId(agentId);
        agent.setHostname(hostname);
        agent.setApiKeyHash(keyHash);
        agent.setEnrolledAt(LocalDateTime.now());
        agent.setEnrolledBy(enrolledBy);
        agent.setActive(Boolean.TRUE);
        agent.setRevokedAt(null);
        agent.setLastSeenAt(null);
        agent.setScanRequestedAt(null);
        PrinterScanAgent saved = agentRepository.save(agent);
        log.info("Printer scan agent enrolled: tenant={} agentId={} enrolledBy={}",
                tenantCode, agentId, enrolledBy);
        return new EnrollResult(saved.getId(), rawKey);
    }

    /** Revoke an agent (admin). Idempotent — a re-enrollment reuses the
     *  row and returns a fresh key. The URL tenantCode is guarded against
     *  the loaded row's tenantCode: ADMIN sees every tenant, but a UI
     *  that scoped the request to tenant ACME must not silently revoke a
     *  BETA row because the ADMIN pasted the wrong id. */
    @Transactional
    public boolean revokeAgent(String tenantCode, long agentRowId) {
        Optional<PrinterScanAgent> found = agentRepository.findById(agentRowId);
        if (found.isEmpty()) return false;
        PrinterScanAgent a = found.get();
        if (tenantCode == null || !tenantCode.equalsIgnoreCase(a.getTenantCode())) {
            throw new IllegalArgumentException(
                    "Agent " + agentRowId + " does not belong to tenant " + tenantCode + ".");
        }
        if (!Boolean.TRUE.equals(a.getActive())) return false;
        a.setActive(Boolean.FALSE);
        a.setRevokedAt(LocalDateTime.now());
        agentRepository.save(a);
        log.info("Printer scan agent revoked: tenant={} agentId={} id={}",
                a.getTenantCode(), a.getAgentId(), a.getId());
        return true;
    }

    // ================================================================
    // Long-poll (agent)
    // ================================================================

    /**
     * Agent's long-poll — checks whether an admin has requested a scan
     * since the last poll. Refreshes {@code last_seen_at} on every call.
     *
     * @return {@code true} if a scan was requested (and this call cleared
     *         the flag); {@code false} otherwise.
     */
    @Transactional
    public boolean pollScanRequest(String rawKey) {
        PrinterScanAgent agent = requireActiveAgentByRawKey(rawKey);
        agent.setLastSeenAt(LocalDateTime.now());
        boolean requested = agent.getScanRequestedAt() != null;
        if (requested) {
            agent.setScanRequestedAt(null);
        }
        agentRepository.save(agent);
        return requested;
    }

    // ================================================================
    // Scan-now (admin)
    // ================================================================

    /**
     * Admin's "Scan now" button. Sets the nudge flag on every active
     * agent for the tenant; agent picks it up on the next poll.
     *
     * @return number of agents nudged (0 if the tenant has none active).
     */
    @Transactional
    public int scanNow(String tenantCode) {
        List<PrinterScanAgent> agents = agentRepository
                .findByTenantCodeAndActiveTrueOrderByEnrolledAtDesc(tenantCode);
        LocalDateTime now = LocalDateTime.now();
        for (PrinterScanAgent a : agents) {
            a.setScanRequestedAt(now);
            agentRepository.save(a);
        }
        log.info("Printer scan-now requested: tenant={} agents={}", tenantCode, agents.size());
        return agents.size();
    }

    // ================================================================
    // POST discovered (agent)
    // ================================================================

    /**
     * Agent posts its scan results. UPSERTs by {@code (tenant, host, port)};
     * allocates a monotonic {@code scan_seq} so the FE picker can filter to
     * "most recent scan only". Refreshes {@code last_seen_at} too.
     */
    @Transactional
    public int upsertDiscovered(String rawKey, List<DiscoveredRow> rows) {
        PrinterScanAgent agent = requireActiveAgentByRawKey(rawKey);
        agent.setLastSeenAt(LocalDateTime.now());
        agentRepository.save(agent);

        if (rows == null || rows.isEmpty()) return 0;
        long nextSeq = discoveredRepository.findMaxScanSeqForTenant(agent.getTenantCode()) + 1L;
        LocalDateTime now = LocalDateTime.now();
        int upserted = 0;
        for (DiscoveredRow row : rows) {
            if (!StringUtils.hasText(row.host()) || row.port() == null) continue;
            PrinterDiscovered pd = discoveredRepository
                    .findByTenantCodeAndHostAndPort(agent.getTenantCode(), row.host(), row.port())
                    .orElseGet(PrinterDiscovered::new);
            pd.setTenantCode(agent.getTenantCode());
            pd.setAgentId(agent.getAgentId());
            pd.setHost(row.host());
            pd.setPort(row.port());
            pd.setName(row.name());
            pd.setLocation(row.location());
            pd.setConnectionGuess(row.connectionGuess());
            pd.setFormatGuess(row.formatGuess());
            pd.setPaperGuess(row.paperGuess());
            pd.setQueuePath(row.queuePath());
            pd.setRawTxt(row.rawTxt());
            pd.setDiscoveredAt(now);
            pd.setScanSeq(nextSeq);
            discoveredRepository.save(pd);
            upserted++;
        }
        log.info("Printer scan ingest: tenant={} agent={} rows={} scanSeq={}",
                agent.getTenantCode(), agent.getAgentId(), upserted, nextSeq);
        return upserted;
    }

    // ================================================================
    // Latest snapshot (admin — feeds the FE picker)
    // ================================================================

    /**
     * Latest scan snapshot for the tenant, most-recent {@code scan_seq}
     * first (all agents interleaved so the FE can group by warehouse).
     */
    public List<PrinterDiscovered> latestForTenant(String tenantCode) {
        return discoveredRepository.findByTenantCodeOrderByScanSeqDescIdAsc(tenantCode);
    }

    /** Admin surface: list every active enrollment for a tenant. */
    public List<PrinterScanAgent> listAgentsForTenant(String tenantCode) {
        return agentRepository.findByTenantCodeAndActiveTrueOrderByEnrolledAtDesc(tenantCode);
    }

    // ================================================================
    // Helpers
    // ================================================================

    private PrinterScanAgent requireActiveAgentByRawKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            throw new IllegalArgumentException("Agent key is required.");
        }
        String hash = sha256Hex(rawKey);
        PrinterScanAgent agent = agentRepository.findByApiKeyHash(hash)
                .orElseThrow(() -> new IllegalStateException("Unknown or revoked agent key."));
        if (!Boolean.TRUE.equals(agent.getActive())) {
            throw new IllegalStateException("Agent key has been revoked.");
        }
        return agent;
    }

    /** 32-byte SecureRandom, hex-encoded → 64-char raw key. */
    String generateRawKey() {
        byte[] buf = new byte[32];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** SHA-256 hex of a raw key. Package-private for tests. */
    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every Java runtime; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ================================================================
    // DTOs
    // ================================================================

    /** Returned once at enrollment. Raw key never re-appears. */
    public record EnrollResult(Long agentRowId, String rawKey) {}

    /** Wire shape agents POST. All fields except {@code host}+{@code port}
     *  are nullable — the agent reports what it found; the FE picker
     *  lets the admin fill gaps before creating the real printer row. */
    public record DiscoveredRow(
            String host,
            Integer port,
            String name,
            String location,
            String connectionGuess,
            String formatGuess,
            String paperGuess,
            String queuePath,
            String rawTxt) {}
}
