package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.SavedRecipientDTO;
import com.multiship.backend.service.SavedRecipientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sprint 38 — address book / saved recipients CRUD + search.
 */
@Tag(name = "Address book", description = "Saved recipients / address book (Sprint 38)")
@RestController
@RequestMapping("/api/v1/recipients")
@RequiredArgsConstructor
public class SavedRecipientController {

    private final SavedRecipientService savedRecipientService;

    @Operation(summary = "Search the address book",
            description = "Fuzzy substring match against name, company, city, or postalCode. " +
                    "customerNo=<code> scopes to that customer's book + platform-wide entries; " +
                    "omit for platform-wide only. Capped at 25 hits.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and (#customerNo == null or @accessScope.canAccessTenant(authentication, #customerNo))")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<SavedRecipientDTO>>> search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "customerNo", required = false) String customerNo) {
        ApiResponse<List<SavedRecipientDTO>> response = savedRecipientService.search(q, customerNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Fetch one saved recipient by id")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SavedRecipientDTO>> byId(@PathVariable Long id) {
        ApiResponse<SavedRecipientDTO> response = savedRecipientService.byId(id);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Save a recipient",
            description = "Idempotent — an entry with the same (name + addressLine1 + postalCode) " +
                    "hash for the same owner returns the existing row unchanged.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping
    public ResponseEntity<ApiResponse<SavedRecipientDTO>> create(
            @Valid @RequestBody SavedRecipientDTO request) {
        ApiResponse<SavedRecipientDTO> response = savedRecipientService.create(request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Update a saved recipient")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SavedRecipientDTO>> update(
            @PathVariable Long id,
            @Valid @RequestBody SavedRecipientDTO request) {
        ApiResponse<SavedRecipientDTO> response = savedRecipientService.update(id, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Delete a saved recipient")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        ApiResponse<Void> response = savedRecipientService.delete(id);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
