package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PrinterDiscovered;
import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.service.PrinterDiscoveryEventPublisher;
import com.multiship.backend.service.PrinterScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * PR-Printer-P1.5 — REST surface for the printer LAN scan feature.
 * See {@code docs/printer-auto-detect-design.md}. Two audiences:
 * <ul>
 *   <li>Admin (JWT + `hasRole('ADMIN')`): enroll / list / revoke agents,
 *       trigger scan-now, view latest snapshot.</li>
 *   <li>Agent (raw key in {@code X-Printer-Scan-Key} header): long-poll
 *       for scan-now nudges, POST discovered rows. NO JWT — the agent
 *       runs on customer infra with no user session.</li>
 * </ul>
 *
 * <p>Agent endpoints are marked {@code permitAll} in SecurityConfig
 * because the header-based key IS the credential; JWT filter would
 * reject the anonymous request otherwise.
 */
@Slf4j
@Tag(name = "Printer scan agent",
        description = "Enrollment + ingestion for the multiship-lan-scanner Docker agent (per-tenant printer discovery).")
@RestController
@RequestMapping("/api/v1")
public class PrinterScanController {

    static final String AGENT_KEY_HEADER = "X-Printer-Scan-Key";

    private final PrinterScanService scanService;
    /** PR-Printer-R2 — SSE pub-sub for the live discovery stream. */
    private final PrinterDiscoveryEventPublisher discoveryPublisher;
    /**
     * PR-Printer-P4c — the version string agents compare against on
     * their hourly update-check. Bump this via env var / properties
     * whenever a new {@code ghcr.io/{owner}/multiship-lan-scanner} tag
     * is published; agents currently running an older version will
     * {@code System.exit(0)} on the next check and their restart
     * mechanism (Watchtower / cron / systemd — see runbook §6) pulls
     * + relaunches.
     */
    private final String latestAgentVersion;

    public PrinterScanController(
            PrinterScanService scanService,
            PrinterDiscoveryEventPublisher discoveryPublisher,
            @Value("${printer.scan-agent.latest-version:0.1.0}") String latestAgentVersion) {
        this.scanService = scanService;
        this.discoveryPublisher = discoveryPublisher;
        this.latestAgentVersion = latestAgentVersion;
    }

    // ================================================================
    // Admin surface (JWT + hasRole('ADMIN'))
    // ================================================================

    @Operation(summary = "Enroll a new printer scan agent",
            description = "Returns the raw agent key ONCE. Admin must paste it into the agent's env (MULTISHIP_AGENT_KEY). Lost keys require rotate.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/tenants/{tenantCode}/printer-scan-agents")
    public ResponseEntity<ApiResponse<EnrollResponse>> enrollAgent(
            @PathVariable String tenantCode,
            @RequestBody EnrollRequest req,
            @AuthenticationPrincipal UserDetails admin) {
        try {
            PrinterScanService.EnrollResult result = scanService.enrollAgent(
                    tenantCode, req.getAgentId(), req.getHostname(),
                    admin != null ? admin.getUsername() : null);
            EnrollResponse body = new EnrollResponse();
            body.agentRowId = result.agentRowId();
            body.rawKey = result.rawKey();
            return ok(body);
        } catch (IllegalStateException conflict) {
            return conflict(conflict.getMessage());
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage());
        }
    }

    @Operation(summary = "List active scan agents for a tenant")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/tenants/{tenantCode}/printer-scan-agents")
    public ResponseEntity<ApiResponse<List<PrinterScanAgent>>> listAgents(
            @PathVariable String tenantCode) {
        return ok(scanService.listAgentsForTenant(tenantCode));
    }

    @Operation(summary = "Request a fresh scan (nudges all active agents for the tenant)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/tenants/{tenantCode}/printers/scan-now")
    public ResponseEntity<ApiResponse<ScanNowResponse>> scanNow(@PathVariable String tenantCode) {
        int nudged = scanService.scanNow(tenantCode);
        ScanNowResponse body = new ScanNowResponse();
        body.agentsNudged = nudged;
        return ok(body);
    }

    @Operation(summary = "Latest scan snapshot for the tenant (feeds the FE picker)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/tenants/{tenantCode}/printers/discovered/latest")
    public ResponseEntity<ApiResponse<List<PrinterDiscovered>>> latestDiscovered(
            @PathVariable String tenantCode) {
        return ok(scanService.latestForTenant(tenantCode));
    }

    @Operation(summary = "Server-sent events stream of newly discovered printers",
            description = "PR-Printer-R2 — long-lived HTTP connection. Server pushes an "
                    + "`event: discovered` frame every time an agent POSTs a new row for this "
                    + "tenant. Includes an initial `event: hello` on connect and periodic "
                    + "`: keepalive` comments every 30s (proxy-friendly). Emitter times out "
                    + "after 30min — the SPA is expected to auto-reconnect via EventSource.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/tenants/{tenantCode}/printers/discovered/stream",
            produces = "text/event-stream")
    public SseEmitter streamDiscovered(@PathVariable String tenantCode) {
        return discoveryPublisher.subscribe(tenantCode);
    }

    @Operation(summary = "Revoke an enrolled scan agent",
            description = "Flips active=false + sets revoked_at. The agent's next long-poll returns 401. "
                    + "Re-enrolling with the same agentId reuses the row and returns a fresh key.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/tenants/{tenantCode}/printer-scan-agents/{id}")
    public ResponseEntity<ApiResponse<RevokeResponse>> revokeAgent(
            @PathVariable String tenantCode,
            @PathVariable long id) {
        try {
            boolean revoked = scanService.revokeAgent(tenantCode, id);
            RevokeResponse body = new RevokeResponse();
            body.revoked = revoked;
            return ok(body);
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage());
        }
    }

    @Operation(summary = "Unrevoke (reactivate) a previously-revoked scan agent",
            description = "Flips active=true + clears revoked_at. The row's original api_key_hash is untouched, "
                    + "so the customer's agent — still holding the pre-revoke key — resumes on its next poll. "
                    + "USE ONLY for accidental revokes; for a leaked key, re-enroll instead (issues a new key).")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/tenants/{tenantCode}/printer-scan-agents/{id}/reactivate")
    public ResponseEntity<ApiResponse<UnrevokeResponse>> unrevokeAgent(
            @PathVariable String tenantCode,
            @PathVariable long id) {
        try {
            boolean reactivated = scanService.unrevokeAgent(tenantCode, id);
            UnrevokeResponse body = new UnrevokeResponse();
            body.reactivated = reactivated;
            return ok(body);
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage());
        }
    }

    @Operation(summary = "List revoked scan agents for a tenant (newest-revoked first)",
            description = "Feeds the FE Scanners tab (R4) so ops can spot-check + unrevoke accidental clicks.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/tenants/{tenantCode}/printer-scan-agents/revoked")
    public ResponseEntity<ApiResponse<List<PrinterScanAgent>>> listRevokedAgents(
            @PathVariable String tenantCode) {
        return ok(scanService.listRevokedAgentsForTenant(tenantCode));
    }

    // ================================================================
    // Agent surface (X-Printer-Scan-Key header, permitAll in SecurityConfig)
    // ================================================================

    @Operation(summary = "Agent long-poll: was a scan requested since the last poll?",
            description = "Returns { scanRequested: true } once per admin nudge; the agent runs the scan and POSTs to /printers/discovered.")
    @GetMapping("/printer-scan-agents/poll")
    public ResponseEntity<ApiResponse<PollResponse>> pollScanRequest(HttpServletRequest request) {
        String key = request.getHeader(AGENT_KEY_HEADER);
        try {
            boolean requested = scanService.pollScanRequest(key);
            PollResponse body = new PollResponse();
            body.scanRequested = requested;
            return ok(body);
        } catch (IllegalStateException auth) {
            return unauthorized(auth.getMessage());
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage());
        }
    }

    @Operation(summary = "Agent's hourly update check",
            description = "Public (no auth) — returns the latest published agent version. "
                    + "Agents call this every ~60min and System.exit(0) on mismatch; the customer's "
                    + "restart mechanism (Watchtower / cron / systemd) then pulls + relaunches. "
                    + "See docs/printer-scan-agent-runbook.md §6.")
    @GetMapping("/printer-scan-agents/latest-version")
    public ResponseEntity<ApiResponse<LatestVersionResponse>> latestVersion() {
        LatestVersionResponse body = new LatestVersionResponse();
        body.version = latestAgentVersion;
        return ok(body);
    }

    @Operation(summary = "Agent posts discovered printers")
    @PostMapping("/printers/discovered")
    public ResponseEntity<ApiResponse<DiscoveredResponse>> postDiscovered(
            @RequestBody List<PrinterScanService.DiscoveredRow> rows,
            HttpServletRequest request) {
        String key = request.getHeader(AGENT_KEY_HEADER);
        try {
            int upserted = scanService.upsertDiscovered(key, rows);
            DiscoveredResponse body = new DiscoveredResponse();
            body.upserted = upserted;
            return ok(body);
        } catch (IllegalStateException auth) {
            return unauthorized(auth.getMessage());
        } catch (IllegalArgumentException bad) {
            return badRequest(bad.getMessage());
        }
    }

    // ================================================================
    // DTOs
    // ================================================================

    @Data public static class EnrollRequest {
        private String agentId;
        private String hostname;
    }

    @Data public static class EnrollResponse {
        private Long agentRowId;
        private String rawKey;
    }

    @Data public static class ScanNowResponse {
        private int agentsNudged;
    }

    @Data public static class PollResponse {
        private boolean scanRequested;
    }

    @Data public static class DiscoveredResponse {
        private int upserted;
    }

    @Data public static class RevokeResponse {
        /** {@code true} if the row transitioned from active→revoked; {@code false}
         *  if the row didn't exist or was already revoked (idempotent). */
        private boolean revoked;
    }

    @Data public static class UnrevokeResponse {
        /** {@code true} if the row transitioned from revoked→active; {@code false}
         *  if the row didn't exist or was already active (idempotent — safe to
         *  double-click). */
        private boolean reactivated;
    }

    @Data public static class LatestVersionResponse {
        /** Semver-ish string matching the {@code lan-scanner-v*} tag suffix
         *  (without the {@code lan-scanner-v} prefix). Agents string-compare
         *  against their baked-in {@code ScanAgent.VERSION}. */
        private String version;
    }

    // ================================================================
    // ApiResponse envelope helpers
    // ================================================================

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(msg).errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> conflict(String msg) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.CONFLICT.value())
                .message(msg).errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> unauthorized(String msg) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.UNAUTHORIZED.value())
                .message(msg).errorCode(ErrorCode.INVALID_CREDENTIALS.name()).build());
    }
}
