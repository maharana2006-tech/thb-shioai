package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.service.wms.WmsClient;
import com.multiship.backend.service.wms.WmsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * WMS integration — pull shippable orders from the external Warehouse
 * Management System into Multiship as PENDING orders (source = WMS).
 *
 * <p>Config-gated: with no WMS_BASE_URL / WMS_API_KEY the pull reports
 * {@code configured = false} and imports nothing.
 */
@Tag(name = "WMS", description = "Pull shippable orders from the external WMS")
@RestController
@RequestMapping("/api/v1/wms")
@RequiredArgsConstructor
public class WmsController {

    private final WmsService wmsService;

    @Operation(summary = "Is the WMS integration configured?",
            description = "Returns { configured: true|false }. False until WMS_BASE_URL + WMS_API_KEY are set.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("WMS status")
                .data(Map.of("configured", wmsService.isConfigured()))
                .build());
    }

    @Operation(summary = "Pull shippable orders from the WMS",
            description = "Fetches the WMS's current shippable orders and imports each new one as a " +
                    "PENDING order (source = WMS). Idempotent — orders already imported are skipped.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/pull")
    public ResponseEntity<ApiResponse<WmsPullResultDTO>> pull(
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        try {
            WmsPullResultDTO result = wmsService.pullShippable(username);
            String msg = !result.isConfigured()
                    ? "WMS not configured — nothing pulled."
                    : result.getImported() + " order(s) imported from WMS · "
                        + result.getSkipped() + " already present, " + result.getFailed() + " failed";
            return ResponseEntity.ok(ApiResponse.<WmsPullResultDTO>builder()
                    .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                    .message(msg).data(result).build());
        } catch (WmsClient.WmsException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.<WmsPullResultDTO>builder()
                            .status("ERROR").code(HttpStatus.BAD_GATEWAY.value()).timestamp(LocalDateTime.now())
                            .message(e.getMessage()).build());
        }
    }
}
