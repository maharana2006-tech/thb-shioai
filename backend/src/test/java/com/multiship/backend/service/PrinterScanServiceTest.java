package com.multiship.backend.service;

import com.multiship.backend.model.PrinterDiscovered;
import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.repository.PrinterDiscoveredRepository;
import com.multiship.backend.repository.PrinterScanAgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-Printer-P1.5 — pure-Mockito coverage for the LAN scan agent business
 * logic. Every branch is tested through the service layer; the controller
 * is a thin envelope and gets its own coverage via a Spring MVC test if
 * needed later.
 */
class PrinterScanServiceTest {

    private PrinterScanAgentRepository agentRepo;
    private PrinterDiscoveredRepository discoveredRepo;
    private PrinterScanService service;

    @BeforeEach
    void setUp() {
        agentRepo = mock(PrinterScanAgentRepository.class);
        discoveredRepo = mock(PrinterDiscoveredRepository.class);
        service = new PrinterScanService(agentRepo, discoveredRepo);
        when(agentRepo.save(any(PrinterScanAgent.class))).thenAnswer(inv -> {
            PrinterScanAgent a = inv.getArgument(0);
            if (a.getId() == null) a.setId(1L);
            return a;
        });
        when(discoveredRepo.save(any(PrinterDiscovered.class))).thenAnswer(inv -> {
            PrinterDiscovered p = inv.getArgument(0);
            if (p.getId() == null) p.setId(1L);
            return p;
        });
    }

    // ================================================================
    // enrollAgent
    // ================================================================

    @Test
    void enrollAgent_returnsRawKeyOnce_persistsSha256Hash() {
        when(agentRepo.findByTenantCodeAndAgentId("ACME", "warehouse-north"))
                .thenReturn(Optional.empty());

        PrinterScanService.EnrollResult result =
                service.enrollAgent("ACME", "warehouse-north", "host-1.acme.local", "alice");

        assertNotNull(result.rawKey(), "Raw key must be returned exactly once");
        assertEquals(64, result.rawKey().length(), "32 bytes hex = 64 chars");

        ArgumentCaptor<PrinterScanAgent> cap = ArgumentCaptor.forClass(PrinterScanAgent.class);
        verify(agentRepo).save(cap.capture());
        PrinterScanAgent saved = cap.getValue();
        assertEquals("ACME", saved.getTenantCode());
        assertEquals("warehouse-north", saved.getAgentId());
        assertEquals("host-1.acme.local", saved.getHostname());
        assertEquals("alice", saved.getEnrolledBy());
        assertEquals(Boolean.TRUE, saved.getActive());
        // Stored is SHA-256 of raw, not raw itself.
        assertEquals(PrinterScanService.sha256Hex(result.rawKey()), saved.getApiKeyHash(),
                "api_key_hash must be SHA-256 hex of the raw key — never the raw key itself");
    }

    @Test
    void enrollAgent_duplicateActive_throws() {
        PrinterScanAgent existing = new PrinterScanAgent();
        existing.setActive(Boolean.TRUE);
        when(agentRepo.findByTenantCodeAndAgentId("ACME", "warehouse-north"))
                .thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.enrollAgent("ACME", "warehouse-north", "host", "alice"));
    }

    @Test
    void enrollAgent_reenrollAfterRevoke_reusesRow() {
        PrinterScanAgent existing = new PrinterScanAgent();
        existing.setId(42L);
        existing.setActive(Boolean.FALSE);
        existing.setRevokedAt(LocalDateTime.now().minusDays(1));
        when(agentRepo.findByTenantCodeAndAgentId("ACME", "warehouse-north"))
                .thenReturn(Optional.of(existing));

        PrinterScanService.EnrollResult result =
                service.enrollAgent("ACME", "warehouse-north", "host", "alice");

        assertNotNull(result.rawKey());
        ArgumentCaptor<PrinterScanAgent> cap = ArgumentCaptor.forClass(PrinterScanAgent.class);
        verify(agentRepo).save(cap.capture());
        PrinterScanAgent saved = cap.getValue();
        assertEquals(42L, saved.getId(), "Re-enrollment must UPDATE the revoked row, not INSERT a duplicate");
        assertEquals(Boolean.TRUE, saved.getActive());
        assertNull(saved.getRevokedAt(), "Re-enroll must clear the revoked timestamp");
    }

    @Test
    void enrollAgent_missingTenantOrAgentId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.enrollAgent(null, "agent", "host", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> service.enrollAgent("ACME", null, "host", "alice"));
    }

    // ================================================================
    // revokeAgent
    // ================================================================

    @Test
    void revokeAgent_flipsActive_setsRevokedAt() {
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setId(7L);
        agent.setTenantCode("ACME");
        agent.setActive(Boolean.TRUE);
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        boolean ok = service.revokeAgent("ACME", 7L);
        assertTrue(ok);
        assertEquals(Boolean.FALSE, agent.getActive());
        assertNotNull(agent.getRevokedAt());
    }

    @Test
    void revokeAgent_alreadyRevoked_returnsFalse() {
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setTenantCode("ACME");
        agent.setActive(Boolean.FALSE);
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        assertFalse(service.revokeAgent("ACME", 7L));
    }

    @Test
    void revokeAgent_missingRow_returnsFalse() {
        when(agentRepo.findById(99L)).thenReturn(Optional.empty());
        assertFalse(service.revokeAgent("ACME", 99L));
    }

    @Test
    void revokeAgent_wrongTenant_throwsIllegalArgument() {
        // PR-P4b guard: URL says tenant=ACME but row 7 belongs to BETA.
        // Must NOT silently revoke — throws so the controller returns 400.
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setId(7L);
        agent.setTenantCode("BETA");
        agent.setActive(Boolean.TRUE);
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        assertThrows(IllegalArgumentException.class,
                () -> service.revokeAgent("ACME", 7L));
        // Row must remain active — no silent damage.
        assertEquals(Boolean.TRUE, agent.getActive());
    }

    // ================================================================
    // unrevokeAgent — PR-Printer-R1
    // ================================================================

    @Test
    void unrevokeAgent_flipsActive_clearsRevokedAt_preservesKeyHash() {
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setId(7L);
        agent.setTenantCode("ACME");
        agent.setActive(Boolean.FALSE);
        agent.setRevokedAt(LocalDateTime.now().minusMinutes(2));
        agent.setApiKeyHash("keep-this-hash");
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        boolean ok = service.unrevokeAgent("ACME", 7L);
        assertTrue(ok);
        assertEquals(Boolean.TRUE, agent.getActive());
        assertNull(agent.getRevokedAt());
        // The original key hash MUST survive — the caveat is documented
        // in the service javadoc and the FE confirm dialog.
        assertEquals("keep-this-hash", agent.getApiKeyHash());
    }

    @Test
    void unrevokeAgent_alreadyActive_returnsFalse() {
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setTenantCode("ACME");
        agent.setActive(Boolean.TRUE);
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        assertFalse(service.unrevokeAgent("ACME", 7L));
    }

    @Test
    void unrevokeAgent_missingRow_returnsFalse() {
        when(agentRepo.findById(99L)).thenReturn(Optional.empty());
        assertFalse(service.unrevokeAgent("ACME", 99L));
    }

    @Test
    void unrevokeAgent_wrongTenant_throwsIllegalArgument() {
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setId(7L);
        agent.setTenantCode("BETA");
        agent.setActive(Boolean.FALSE);
        when(agentRepo.findById(7L)).thenReturn(Optional.of(agent));

        assertThrows(IllegalArgumentException.class,
                () -> service.unrevokeAgent("ACME", 7L));
        // Row must remain revoked — no cross-tenant repair path.
        assertEquals(Boolean.FALSE, agent.getActive());
    }

    // ================================================================
    // pollScanRequest
    // ================================================================

    @Test
    void pollScanRequest_flagSet_returnsTrue_clearsFlag_refreshesLastSeen() {
        String raw = "abc123";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setScanRequestedAt(LocalDateTime.now().minusMinutes(1));
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));

        boolean requested = service.pollScanRequest(raw);
        assertTrue(requested);
        assertNull(agent.getScanRequestedAt(), "Poll must clear the nudge flag");
        assertNotNull(agent.getLastSeenAt());
    }

    @Test
    void pollScanRequest_noFlag_returnsFalse_stillRefreshesLastSeen() {
        String raw = "abc123";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setScanRequestedAt(null);
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));

        assertFalse(service.pollScanRequest(raw));
        assertNotNull(agent.getLastSeenAt(),
                "last_seen must refresh on EVERY poll (needed for the P4 Grafana staleness alert)");
    }

    @Test
    void pollScanRequest_unknownKey_throws() {
        when(agentRepo.findByApiKeyHash(any())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.pollScanRequest("wrong"));
    }

    @Test
    void pollScanRequest_revokedKey_throws() {
        String raw = "revoked-key";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.FALSE);
        agent.setApiKeyHash(hash);
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));

        assertThrows(IllegalStateException.class, () -> service.pollScanRequest(raw));
    }

    // ================================================================
    // scanNow
    // ================================================================

    @Test
    void scanNow_setsFlagOnEveryActiveAgentForTenant() {
        PrinterScanAgent a1 = new PrinterScanAgent(); a1.setActive(Boolean.TRUE);
        PrinterScanAgent a2 = new PrinterScanAgent(); a2.setActive(Boolean.TRUE);
        when(agentRepo.findByTenantCodeAndActiveTrueOrderByEnrolledAtDesc("ACME"))
                .thenReturn(List.of(a1, a2));

        int nudged = service.scanNow("ACME");
        assertEquals(2, nudged);
        assertNotNull(a1.getScanRequestedAt());
        assertNotNull(a2.getScanRequestedAt());
        verify(agentRepo, times(2)).save(any(PrinterScanAgent.class));
    }

    // ================================================================
    // upsertDiscovered
    // ================================================================

    @Test
    void upsertDiscovered_newRow_persistsWithMonotonicScanSeq() {
        String raw = "agent-key";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setTenantCode("ACME");
        agent.setAgentId("warehouse-north");
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));
        when(discoveredRepo.findMaxScanSeqForTenant("ACME")).thenReturn(5L);
        when(discoveredRepo.findByTenantCodeAndHostAndPort("ACME", "192.168.1.50", 9100))
                .thenReturn(Optional.empty());

        PrinterScanService.DiscoveredRow row = new PrinterScanService.DiscoveredRow(
                "192.168.1.50", 9100, "ZebraLabel", "Bay 3",
                "RAW_9100", "ZPL", "LABEL_4X6", null, "ty=Zebra ZD421");
        int upserted = service.upsertDiscovered(raw, List.of(row));

        assertEquals(1, upserted);
        ArgumentCaptor<PrinterDiscovered> cap = ArgumentCaptor.forClass(PrinterDiscovered.class);
        verify(discoveredRepo).save(cap.capture());
        PrinterDiscovered saved = cap.getValue();
        assertEquals("ACME", saved.getTenantCode());
        assertEquals("warehouse-north", saved.getAgentId());
        assertEquals("192.168.1.50", saved.getHost());
        assertEquals(9100, saved.getPort());
        assertEquals(6L, saved.getScanSeq(), "scan_seq must monotonically increase (max + 1)");
    }

    @Test
    void upsertDiscovered_existingRow_updates_notInsertNew() {
        String raw = "agent-key";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setTenantCode("ACME");
        agent.setAgentId("warehouse-north");
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));
        PrinterDiscovered existing = new PrinterDiscovered();
        existing.setId(99L);
        when(discoveredRepo.findByTenantCodeAndHostAndPort("ACME", "192.168.1.50", 9100))
                .thenReturn(Optional.of(existing));
        when(discoveredRepo.findMaxScanSeqForTenant("ACME")).thenReturn(10L);

        PrinterScanService.DiscoveredRow row = new PrinterScanService.DiscoveredRow(
                "192.168.1.50", 9100, "ZebraLabel-Renamed", null, "RAW_9100", "ZPL", null, null, null);
        service.upsertDiscovered(raw, List.of(row));

        ArgumentCaptor<PrinterDiscovered> cap = ArgumentCaptor.forClass(PrinterDiscovered.class);
        verify(discoveredRepo).save(cap.capture());
        assertEquals(99L, cap.getValue().getId(), "UPSERT must reuse existing row id, not INSERT");
        assertEquals("ZebraLabel-Renamed", cap.getValue().getName());
        assertEquals(11L, cap.getValue().getScanSeq());
    }

    @Test
    void upsertDiscovered_rowMissingHost_skipped() {
        String raw = "agent-key";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setTenantCode("ACME");
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));

        PrinterScanService.DiscoveredRow bad = new PrinterScanService.DiscoveredRow(
                null, 9100, "no-host", null, null, null, null, null, null);
        int upserted = service.upsertDiscovered(raw, List.of(bad));
        assertEquals(0, upserted);
        verify(discoveredRepo, times(0)).save(any(PrinterDiscovered.class));
    }

    @Test
    void upsertDiscovered_emptyList_returnsZero_noSave() {
        String raw = "agent-key";
        String hash = PrinterScanService.sha256Hex(raw);
        PrinterScanAgent agent = new PrinterScanAgent();
        agent.setActive(Boolean.TRUE);
        agent.setApiKeyHash(hash);
        agent.setTenantCode("ACME");
        when(agentRepo.findByApiKeyHash(hash)).thenReturn(Optional.of(agent));

        assertEquals(0, service.upsertDiscovered(raw, List.of()));
        verify(discoveredRepo, times(0)).save(any(PrinterDiscovered.class));
    }

    // ================================================================
    // sha256Hex determinism (regression against helper swap)
    // ================================================================

    @Test
    void sha256Hex_deterministic_64charHexOutput() {
        String a = PrinterScanService.sha256Hex("hello");
        String b = PrinterScanService.sha256Hex("hello");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
        // Golden vector to catch a future swap to a different algorithm.
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", a);
    }
}
