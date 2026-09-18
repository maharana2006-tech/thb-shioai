package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PrinterDiscovered;
import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.service.PrinterScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RequiredArgsConstructor
public class PrinterScanController {

    static final String AGENT_KEY_HEADER = "X-Printer-Scan-Key";

    private final PrinterScanService scanService;

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
