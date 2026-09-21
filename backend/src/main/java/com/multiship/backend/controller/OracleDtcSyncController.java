package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.oracle.OracleDtcSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for Oracle DTC Order Synchronization.
 *
 * Endpoints:
 *   POST /api/v1/dtc/sync/oracle       - Sync pending DTC orders from Oracle
 *   GET  /api/v1/dtc/pending-count     - Get pending order count in Oracle
 */
@RestController
@RequestMapping("/api/v1/dtc")
@RequiredArgsConstructor
public class OracleDtcSyncController {

    private final OracleDtcSyncService oracleDtcSyncService;

    /**
     * Manually trigger DTC order sync from Oracle to PostgreSQL.
     *
     * Query params:
     *   - tenantId (optional): filter by specific tenant, empty = all tenants
     *
     * @param tenantId optional tenant filter
     * @return sync result with counts
     */
    @PreAuthorize("permitAll()")
    @PostMapping("/sync/oracle")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncOracleDtcOrders(
            @RequestParam(value = "tenantId", defaultValue = "") String tenantId) {

        OracleDtcSyncService.OracleSyncResult result =
                oracleDtcSyncService.syncPendingDtcOrders(tenantId);

        Map<String, Object> data = new HashMap<>();
        data.put("fetched", result.fetched);
        data.put("imported", result.imported);
        data.put("skipped", result.skipped);
        data.put("message", result.message);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS")
                .code(200)
                .message("DTC order sync completed")
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Get count of pending DTC orders in Oracle.
     *
     * @param tenantId tenant code
     * @return count of pending orders
     */
    @PreAuthorize("permitAll()")
    @GetMapping("/pending-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingCount(
            @RequestParam String tenantId) {

        long count = oracleDtcSyncService.getPendingOrderCount(tenantId);

        Map<String, Object> data = new HashMap<>();
        data.put("tenantId", tenantId);
        data.put("pendingCount", count);

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS")
                .code(200)
                .message("Pending order count retrieved")
                .timestamp(LocalDateTime.now())
                .data(data)
                .build();

        return ResponseEntity.ok(response);
    }
}
