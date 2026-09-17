package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.Printer;
import com.multiship.backend.model.PrinterAssignment;
import com.multiship.backend.service.printing.PrinterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public PrinterController(PrinterService printers) {
        this.printers = printers;
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
