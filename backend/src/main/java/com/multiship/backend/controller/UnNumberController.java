package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.dg.UnNumberDirectory;
import com.multiship.backend.service.dg.UnNumberEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Read-only endpoints backing the UN number autocomplete UI in the
 * dangerous goods wizard. Mirrors {@code HsCodeController}'s shape — the
 * dataset is a hand-curated starter set (see {@link UnNumberDirectory});
 * a full IATA / UN Table A integration lives behind the same interface
 * so the UI doesn't have to change when we swap datasets.
 */
@Tag(name = "Dangerous goods", description = "Autocomplete + lookup for UN numbers (IATA / UN Model Regulations Table A)")
@RestController
@RequestMapping("/api/v1/dg/un")
@RequiredArgsConstructor
public class UnNumberController {

    private final UnNumberDirectory directory;

    @Operation(summary = "Search the UN number directory — UN\\d{4} prefix or proper-shipping-name substring")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<UnNumberEntry>>> search(@RequestParam(name = "q") String q) {
        List<UnNumberEntry> matches = directory.search(q);
        return ResponseEntity.ok(ApiResponse.<List<UnNumberEntry>>builder()
                .status("success").code(200)
                .message(matches.isEmpty() ? "No matches." : matches.size() + " match(es).")
                .data(matches)
                .build());
    }

    @Operation(summary = "Fetch the directory entry for an exact UN number (case-insensitive)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{unNumber}")
    public ResponseEntity<ApiResponse<UnNumberEntry>> byNumber(@PathVariable String unNumber) {
        Optional<UnNumberEntry> entry = directory.byNumber(unNumber);
        return entry
                .map(e -> ResponseEntity.ok(ApiResponse.<UnNumberEntry>builder()
                        .status("success").code(200)
                        .message("Found.")
                        .data(e)
                        .build()))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<UnNumberEntry>builder()
                        .status("error").code(404)
                        .message(unNumber + " not in the curated directory.")
                        .data(null)
                        .build()));
    }
}
