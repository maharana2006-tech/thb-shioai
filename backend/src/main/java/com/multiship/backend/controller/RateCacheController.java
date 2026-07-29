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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sprint 39 — rate cache observability. Stats endpoint helps confirm
 * the cache is doing work. Programmatic invalidation stays on
 * {@code RateCacheService.invalidate(...)} for tests + future admin
 * surfaces.
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

}
