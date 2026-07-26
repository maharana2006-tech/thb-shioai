package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.RateCacheService;
import com.multiship.backend.service.RateCacheService.CacheStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint 39 — rate cache observability + admin invalidation.
 * Operators can bust the cache for a specific carrier (or everything)
 * when carrier prices change; stats endpoint helps confirm cache is
 * doing work.
 */
@Tag(name = "Rate cache", description = "Observability + invalidation for the rate-shop cache")
@RestController
@RequestMapping("/api/v1/rate-cache")
@RequiredArgsConstructor
public class RateCacheController {

    private final RateCacheService rateCacheService;

    @Operation(summary = "Cache stats — hit/miss counters + size")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<CacheStats>> stats() {
        CacheStats stats = rateCacheService.stats();
        return ResponseEntity.ok(ApiResponse.<CacheStats>builder()
                .status("success").code(200)
                .message(stats.size() + " entries · " + stats.hits() + " hits / "
                        + stats.misses() + " misses")
                .data(stats).build());
    }

    @Operation(summary = "Invalidate cache entries",
            description = "Optional carrier param scopes the bust to one carrier's entries. " +
                    "Omit for a full clear. Returns the number of entries removed.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/invalidate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> invalidate(
            @RequestParam(name = "carrier", required = false) String carrier) {
        int removed = rateCacheService.invalidate(carrier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", removed);
        body.put("carrier", carrier);
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("success").code(200)
                .message("Removed " + removed + " entries"
                        + (carrier == null ? " (all carriers)" : " for " + carrier))
                .data(body).build());
    }
}
