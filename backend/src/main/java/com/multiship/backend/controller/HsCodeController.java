package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.hs.HsCodeDirectory;
import com.multiship.backend.service.hs.HsCodeEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoints backing the HS code autocomplete UI. The dataset is
 * a hand-curated starter set (see {@link HsCodeDirectory}); a full WCO or
 * tariff-schedule integration lives behind the same interface so the UI
 * doesn't have to change when we swap datasets.
 */
@Tag(name = "HS Codes", description = "Autocomplete + lookup for Harmonized System tariff codes")
@RestController
@RequestMapping("/api/v1/customs/hs-codes")
@RequiredArgsConstructor
public class HsCodeController {

    private final HsCodeDirectory directory;

    @Operation(summary = "Search the HS code directory — code prefix or description substring")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<HsCodeEntry>>> search(@RequestParam(name = "q") String q) {
        List<HsCodeEntry> matches = directory.search(q);
        return ResponseEntity.ok(ApiResponse.<List<HsCodeEntry>>builder()
                .status("success").code(200)
                .message(matches.isEmpty() ? "No matches." : matches.size() + " match(es).")
                .data(matches)
                .build());
    }

}
