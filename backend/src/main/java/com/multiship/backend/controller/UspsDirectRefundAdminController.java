package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsVoidReconciliationStatusDTO;
import com.multiship.backend.dto.UspsVoidReconciliationSummaryDTO;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import com.multiship.backend.service.carriers.usps.UspsRefundCsvExporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * PR-D — admin surface for the USPS_DIRECT void + refund workflow.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>Generate a PS 3533 CSV of unreconciled voided-USPS shipments so
 *       the platform admin can upload it to USPS's Business Customer
 *       Gateway eVS Refund portal. USPS has no REST endpoint for the
 *       refund workflow — batch CSV upload is the only path.</li>
 *   <li>Accept the eVS Refund report USPS produces in return and drive
 *       reconciliation via
 *       {@link UspsDirectVoidReconciliationService#reconcile(java.io.InputStream)}.
 *       APPROVED rows stay VOIDED; DENIED rows flip to VOID_FAILED with
 *       an operator toast log.</li>
 * </ul>
 *
 * <p>All endpoints ADMIN-only. Error convention mirrors
 * {@link WebhookSubscriptionAdminController}: 400 on IAE (validation),
 * 404 when a queried tracking isn't found, 502 on ISE (upstream failure)
 * — the {@link GlobalExceptionHandler} handles the ISE mapping.
 */
@Tag(name = "USPS Direct refund admin",
        description = "PR-D — PS 3533 refund CSV export + eVS Refund report reconciliation")
@RestController
@RequestMapping("/api/v1/admin/usps-direct")
@RequiredArgsConstructor
public class UspsDirectRefundAdminController {

    private final UspsRefundCsvExporter refundCsvExporter;
    private final UspsDirectVoidReconciliationService reconciliationService;
    private final OrderTrackingRepository orderTrackingRepository;

    private static final DateTimeFormatter FILENAME_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ================================================================
    // GET /refund-export — stream PS 3533 CSV
    // ================================================================

    @Operation(summary = "Download the PS 3533 refund CSV for the given date range",
            description = "Streams a text/csv attachment of every VOIDED USPS shipment in the range "
                    + "that hasn't yet been reconciled against USPS's eVS Refund report. The platform "
                    + "admin uploads the CSV to the USPS Business Customer Gateway (BCG) eVS Refund portal.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/refund-export")
    public ResponseEntity<byte[]> refundExport(
            @RequestParam(name = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDateTime from = startDate != null ? startDate.atStartOfDay() : null;
        // Inclusive end-of-day so a same-day range picks up labels
        // generated any time during the endDate day.
        LocalDateTime to = endDate != null ? endDate.atTime(23, 59, 59) : null;
        byte[] csv;
        try {
            csv = refundCsvExporter.exportCsv(from, to);
        } catch (IllegalArgumentException iae) {
            // Bad date range → 400 with a JSON error body.
            return badCsv(iae.getMessage());
        }

        String filename = "ps3533-refund-"
                + FILENAME_DATE.format(LocalDate.now()) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment()
                        .filename(filename).build());
        headers.setContentLength(csv.length);
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }

    // ================================================================
    // POST /void-reconciliation/run — accept eVS Refund report upload
    // ================================================================

    @Operation(summary = "Upload an eVS Refund report and reconcile local void state",
            description = "Multipart CSV upload — one row per refund decision. APPROVED rows stay "
                    + "VOIDED and get marked RECONCILED_APPROVED; DENIED rows flip to VOID_FAILED "
                    + "with a WARN log for the operator toast; PENDING rows are left alone.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/void-reconciliation/run",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UspsVoidReconciliationSummaryDTO>> runReconciliation(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return bad("Uploaded file is empty. Attach the eVS Refund report CSV.");
        }
        try {
            UspsVoidReconciliationSummaryDTO summary =
                    reconciliationService.reconcile(file.getInputStream());
            return ResponseEntity.ok(ApiResponse.<UspsVoidReconciliationSummaryDTO>builder()
                    .status("SUCCESS").code(HttpStatus.OK.value())
                    .timestamp(LocalDateTime.now())
                    .message("Reconciled " + summary.processedRows() + " row(s).")
                    .data(summary)
                    .build());
        } catch (IllegalArgumentException iae) {
            // Malformed header / missing required column → 400.
            return bad(iae.getMessage());
        } catch (IOException ioe) {
            return bad("Failed to read uploaded CSV: " + ioe.getMessage());
        }
    }

    // ================================================================
    // GET /void-reconciliation/status?trackingNumber=... — per-tracking status
    // ================================================================

    @Operation(summary = "Look up the reconciliation status of a single tracking number",
            description = "Helps operators debug the 'order shows CANCELLED but customer says delivered' "
                    + "case: reveals whether USPS has confirmed the void, refused it, or is silent.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/void-reconciliation/status")
    public ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> statusFor(
            @RequestParam("trackingNumber") String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return bad("trackingNumber is required.");
        }
        Optional<OrderTracking> found = orderTrackingRepository
                .findByTrackingNumberIgnoreCase(trackingNumber.trim());
        if (found.isEmpty()) {
            return notFoundStatus(trackingNumber);
        }
        OrderTracking t = found.get();
        UspsVoidReconciliationStatusDTO dto = new UspsVoidReconciliationStatusDTO(
                t.getTrackingNumber(),
                t.getStatus(),
                t.getVoidReconciliationStatus(),
                t.getVoidReconciliationCheckedAt()
        );
        return ResponseEntity.ok(ApiResponse.<UspsVoidReconciliationStatusDTO>builder()
                .status("SUCCESS").code(HttpStatus.OK.value())
                .timestamp(LocalDateTime.now())
                .data(dto).build());
    }

    // ================================================================
    // Error envelope helpers
    // ================================================================

    private static <T> ResponseEntity<ApiResponse<T>> bad(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .timestamp(LocalDateTime.now())
                .message(message).errorCode(ErrorCode.VALIDATION_ERROR.name())
                .build());
    }

    private static ResponseEntity<byte[]> badCsv(String message) {
        // CSV endpoint returns bytes on success — on failure switch to
        // a JSON body so the FE / curl caller can read the reason.
        String body = "{\"status\":\"error\",\"code\":400,\"message\":\""
                + message.replace("\"", "\\\"") + "\","
                + "\"errorCode\":\"" + ErrorCode.VALIDATION_ERROR.name() + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                h, HttpStatus.BAD_REQUEST);
    }

    private static ResponseEntity<ApiResponse<UspsVoidReconciliationStatusDTO>> notFoundStatus(String trackingNumber) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<UspsVoidReconciliationStatusDTO>builder()
                        .status("error").code(HttpStatus.NOT_FOUND.value())
                        .timestamp(LocalDateTime.now())
                        .message("Tracking number not found: " + trackingNumber)
                        .errorCode(ErrorCode.ORDER_NOT_FOUND.name())
                        .build());
    }
}
