package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.InvoiceCopies;
import com.multiship.backend.service.InvoiceCopiesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PR-Printer-R7a — per (client, carrier) commercial-invoice copies.
 * Feeds the FE Copies-tab matrix (R7b). Print-dispatch integration
 * lands in R7c via {@link InvoiceCopiesService#resolveCopies}.
 */
@RestController
@RequestMapping("/api/v1/invoice-copies")
@Tag(name = "Invoice copies",
        description = "How many commercial-invoice copies to print per (client, carrier).")
public class InvoiceCopiesController {

    private final InvoiceCopiesService service;

    public InvoiceCopiesController(InvoiceCopiesService service) {
        this.service = service;
    }

    @Operation(summary = "List every configured rule (client=null rows are tenant-defaults)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<InvoiceCopies>>> list() {
        List<InvoiceCopies> all = service.listAll();
        return ok(all.size() + (all.size() == 1 ? " rule" : " rules"), all);
    }

    @Operation(summary = "Upsert a rule (blank client = tenant-wide default row for the carrier)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<ApiResponse<InvoiceCopies>> upsert(@RequestBody UpsertInput input) {
        InvoiceCopies saved = service.upsert(input.getClientCode(), input.getCarrierCode(), input.getCopies());
        return ok("Rule saved (" + input.getCopies() + " copies).", saved);
    }

    @Operation(summary = "Delete a rule by (client, carrier) — deleting the tenant-default row falls back to 1")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{carrierCode}")
    public ResponseEntity<ApiResponse<Void>> deleteTenantDefault(@PathVariable String carrierCode) {
        // Tenant-default variant: no client-code path segment.
        boolean removed = service.delete(null, carrierCode);
        return ok(removed ? "Tenant default removed." : "No matching rule.", null);
    }

    @Operation(summary = "Delete a per-client rule")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{carrierCode}/clients/{clientCode}")
    public ResponseEntity<ApiResponse<Void>> deleteClientRule(
            @PathVariable String carrierCode,
            @PathVariable String clientCode) {
        boolean removed = service.delete(clientCode, carrierCode);
        return ok(removed ? "Rule removed." : "No matching rule.", null);
    }

    @Data public static class UpsertInput {
        /** NULL / blank = tenant-wide default row for this carrier. */
        private String clientCode;
        private String carrierCode;
        private int copies;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().status("SUCCESS").code(200)
                .timestamp(LocalDateTime.now()).message(message).data(data).build());
    }

    private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.<Void>builder().status("ERROR").code(status.value())
                .timestamp(LocalDateTime.now()).errorCode(code.name()).message(message).build());
    }
}
