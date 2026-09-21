package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.Printer;
import com.multiship.backend.model.PrinterAssignment;
import com.multiship.backend.model.PrinterTag;
import com.multiship.backend.model.PrinterTestHistory;
import com.multiship.backend.repository.PrinterTestHistoryRepository;
import com.multiship.backend.service.PrinterTagService;
import com.multiship.backend.service.printing.PrinterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/** Printer management and client-to-printer assignments. Reads for operators; changes for admins. */
@RestController
@RequestMapping("/api/v1/printers")
@Tag(name = "Printers", description = "Network printers and which client's documents go to each")
public class PrinterController {

    private final PrinterService printers;
    /** PR-Printer-R8a — free-form tags for grouping registered printers. */
    private final PrinterTagService tags;
    /** PR-Printer-R9.5a — rolling test-print log per printer. */
    private final PrinterTestHistoryRepository testHistory;

    /** Cap on ?limit=N to keep a runaway request from returning
     *  megabytes of test messages. Default is 10 (feeds the FE panel). */
    private static final int MAX_TEST_HISTORY_LIMIT = 100;

    public PrinterController(PrinterService printers, PrinterTagService tags,
                             PrinterTestHistoryRepository testHistory) {
        this.printers = printers;
        this.tags = tags;
        this.testHistory = testHistory;
    }

    @Operation(summary = "List printers")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Printer>>> list() {
        List<Printer> all = printers.list();
        return ok(all.size() + (all.size() == 1 ? " printer" : " printers"), all);
    }

    @Operation(summary = "Register a printer")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<Printer>> create(@RequestBody PrinterService.PrinterInput input) {
        Printer p = printers.create(input);
        return ok("Printer " + p.getName() + " added.", p);
    }

    @Operation(summary = "Update a printer")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Printer>> update(@PathVariable Long id, @RequestBody PrinterService.PrinterInput input) {
        Printer p = printers.update(id, input);
        return ok("Printer " + p.getName() + " saved.", p);
    }

    @Operation(summary = "Delete a printer (its assignments go with it)")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        printers.delete(id);
        return ok("Printer deleted.", null);
    }

    @Operation(summary = "Print a test job and record the result on the printer")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<Printer>> test(@PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        Printer p = printers.test(id, user == null ? null : user.getUsername());
        return ok(p.getLastTestMessage(), p);
    }

    /** What the editor sends to "Detect printer" — nothing is saved. */
    public record ProbeRequest(String host, Integer port, String queuePath) { }

    @Operation(summary = "Ask a printer what it prints, before saving it",
            description = "IPP Get-Printer-Attributes on port 631: model and supported formats, plus the "
                    + "connection and format the app suggests. Read-only — nothing prints. Data is null when "
                    + "the printer doesn't answer IPP (common for older label printers).")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/probe")
    public ResponseEntity<ApiResponse<PrinterService.Capabilities>> probe(@RequestBody ProbeRequest req) {
        if (req == null || req.host() == null || req.host().isBlank()) {
            throw new PrinterService.PrinterValidationException("Enter the printer's IP address or hostname first.");
        }
        // The same address rules as saving: no probing the server itself or its services.
        var refused = com.multiship.backend.service.printing.PrinterAddressGuard.refusal(
                req.host().trim(), req.port() == null ? 631 : req.port());
        if (refused.isPresent()) throw new PrinterService.PrinterValidationException(refused.get());
        return printers.probe(req.host(), req.port(), req.queuePath())
                .map(c -> ok(c.summary(), c))
                .orElseGet(() -> ok("The printer didn't answer an IPP query, so its formats are unknown. "
                        + "Label printers often don't — if this is one, choose Network port (9100) and ZPL.", null));
    }

    @Operation(summary = "List client printer assignments (client null = default)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/assignments")
    public ResponseEntity<ApiResponse<List<PrinterAssignment>>> assignments() {
        List<PrinterAssignment> all = printers.listAssignments();
        return ok(all.size() + (all.size() == 1 ? " assignment" : " assignments"), all);
    }

    @Operation(summary = "Set the printer for a client and document type (blank client = default)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/assignments")
    public ResponseEntity<ApiResponse<PrinterAssignment>> upsertAssignment(@RequestBody PrinterService.AssignmentInput input) {
        PrinterAssignment a = printers.upsertAssignment(input);
        return ok("Printer assigned.", a);
    }

    @Operation(summary = "Remove a printer assignment")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/assignments/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAssignment(@PathVariable Long id) {
        printers.deleteAssignment(id);
        return ok("Assignment removed.", null);
    }

    // ================================================================
    // Queue depth (R11)
    // ================================================================

    @Operation(summary = "Poll a printer's queue depth",
            description = "PR-R11 — { inFlight: our concurrent send() count, ippQueue: printer's IPP Get-Jobs count (IPP only), "
                    + "ippQueueError: string when IPP polling fails or is Not-supported (RAW_9100) }.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{id}/queue-depth")
    public ResponseEntity<ApiResponse<PrinterService.QueueDepth>> queueDepth(@PathVariable Long id) {
        return ok("queue depth", printers.queueDepth(id));
    }

    // ================================================================
    // Printer tags (R8a)
    // ================================================================

    @Operation(summary = "List tags on a printer")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{id}/tags")
    public ResponseEntity<ApiResponse<List<PrinterTag>>> listTags(@PathVariable Long id) {
        return ok("tags", tags.listForPrinter(id));
    }

    @Operation(summary = "Replace all tags on a printer (bulk).",
            description = "Send the full desired set — the service diffs against current tags. "
                    + "Empty array clears all tags. Duplicates + case variants are normalised (lowercase).")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/tags")
    public ResponseEntity<ApiResponse<List<PrinterTag>>> replaceTags(
            @PathVariable Long id,
            @RequestBody TagsInput input) {
        List<String> desired = input == null ? List.of() : (input.getTags() == null ? List.of() : input.getTags());
        List<PrinterTag> saved = tags.replaceAllForPrinter(id, desired);
        return ok(saved.size() + (saved.size() == 1 ? " tag" : " tags"), saved);
    }

    @Operation(summary = "Distinct tags across every printer — feeds the FE autocomplete")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/tags/distinct")
    public ResponseEntity<ApiResponse<List<String>>> distinctTags() {
        return ok("distinct tags", tags.distinctTags());
    }

    // ================================================================
    // Test-print history (R9.5a)
    // ================================================================

    @Operation(summary = "Rolling test-print history for a printer (newest first)",
            description = "Feeds the FE PrinterDetailsPanel > Test history section. Default limit 10, capped at 100.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{id}/test-history")
    public ResponseEntity<ApiResponse<List<PrinterTestHistory>>> testHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_TEST_HISTORY_LIMIT));
        Pageable page = PageRequest.of(0, capped);
        List<PrinterTestHistory> rows = testHistory.findByPrinterIdOrderByTestedAtDesc(id, page);
        return ok(rows.size() + (rows.size() == 1 ? " attempt" : " attempts"), rows);
    }

    /** Wire input for {@link #replaceTags}. */
    public static class TagsInput {
        private List<String> tags;
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badTag(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(PrinterService.PrinterValidationException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(PrinterService.PrinterValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> missing(NoSuchElementException ex) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(org.springframework.dao.DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                "That client already has a printer for this document type; edit the existing assignment.");
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
