package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.service.OrderImportService;
import com.multiship.backend.service.dtc.DtcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * D2C History — pulls pending DTC orders from the Oracle NDS view
 * (TB_SHIPX_DTC_UVW / _TEST) into Multiship as one ImportBatch per fetch,
 * mirroring the WMS pull flow. Off by default when {@code multiship.oracle.enabled}
 * is false — status endpoint reports {@code configured=false} and pull is a no-op.
 */
@Tag(name = "D2C", description = "Pull pending DTC shipments from the Oracle NDS view")
@RestController
@RequestMapping("/api/v1/d2c")
@RequiredArgsConstructor
public class DtcController {

    private final DtcService dtcService;
    private final OrderImportService orderImportService;
    private final com.multiship.backend.repository.ImportBatchRepository importBatchRepository;

    @Operation(summary = "Is the D2C (Oracle NDS view) integration configured?",
            description = "Returns { configured: true|false }. False until multiship.oracle.enabled=true and the view is reachable.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("D2C status")
                .data(Map.of("configured", dtcService.isConfigured()))
                .build());
    }

    @Operation(summary = "List D2C fetch batches",
            description = "Each 'Fetch from NDS' is one batch (source=DTC). Shown on /d2c/history.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<ImportBatchDTO>>> batches() {
        return ResponseEntity.ok(ApiResponse.<List<ImportBatchDTO>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("D2C batches")
                .data(orderImportService.dtcBatches())
                .build());
    }

    @Operation(summary = "One D2C batch with its shipment rows")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/batches/{slug}")
    public ResponseEntity<ApiResponse<ImportBatchDTO>> batch(@PathVariable String slug) {
        Long id = importBatchRepository.findBySlug(slug)
                .map(com.multiship.backend.model.ImportBatch::getId)
                .orElse(null);
        ImportBatchDTO dto = id == null ? null : orderImportService.historyDetail(id);
        if (dto == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.<ImportBatchDTO>builder()
                            .status("ERROR").code(404).timestamp(LocalDateTime.now())
                            .message("Batch not found").build());
        }
        return ResponseEntity.ok(ApiResponse.<ImportBatchDTO>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("D2C batch").data(dto).build());
    }

    @Operation(summary = "Pull pending DTC shipments from the Oracle NDS view",
            description = "Fetches the view's current rows into one import batch (source=DTC), " +
                    "validated and labelled from Bulk Mailer like a file import.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/pull")
    public ResponseEntity<ApiResponse<WmsPullResultDTO>> pull(
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails == null ? "unknown" : userDetails.getUsername();
        WmsPullResultDTO result = dtcService.pullShippable(username);
        String msg = !result.isConfigured()
                ? "D2C not configured — nothing pulled."
                : result.getImported() + " shipment(s) imported from NDS · "
                    + result.getSkipped() + " already present, " + result.getFailed() + " failed";
        return ResponseEntity.ok(ApiResponse.<WmsPullResultDTO>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(msg).data(result).build());
    }
}
