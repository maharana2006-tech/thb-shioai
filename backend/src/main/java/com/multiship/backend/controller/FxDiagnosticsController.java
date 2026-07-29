package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.fx.FxRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only ops endpoints for the FX rate service. Lets ops verify the ECB
 * feed is reachable and up-to-date without shelling into the JVM. All routes
 * ADMIN-only — rate lookups aren't sensitive but the endpoint exists for
 * diagnostics, not for public consumption.
 */
@Tag(name = "FX diagnostics", description = "Ops-facing checks on the FX rate service")
@RestController
@RequestMapping("/api/v1/admin/fx")
@RequiredArgsConstructor
public class FxDiagnosticsController {

    private final FxRateService fxRateService;

    /**
     * Sample conversion — pass {@code from} + {@code to} + {@code amount}
     * (default 100) and see the live rate. Empty response body when the
     * requested pair isn't supported by the current snapshot (feed miss,
     * unknown currency, or startup pre-fetch).
     */
    @Operation(summary = "Convert an amount via the live FX feed — verifies the pair is supported")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/convert")
    public ResponseEntity<ApiResponse<Map<String, Object>>> convert(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false, defaultValue = "100") BigDecimal amount) {
        Optional<BigDecimal> rate = fxRateService.rate(from, to);
        Optional<BigDecimal> converted = fxRateService.convert(amount, from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from == null ? "" : from.toUpperCase());
        body.put("to", to == null ? "" : to.toUpperCase());
        body.put("amount", amount);
        body.put("supportedFrom", fxRateService.supports(from));
        body.put("supportedTo", fxRateService.supports(to));
        body.put("rate", rate.orElse(null));
        body.put("converted", converted.orElse(null));

        String msg = rate.isPresent()
                ? "Rate resolved."
                : "Rate unavailable — feed miss, unknown currency, or startup pre-fetch pending.";
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status(rate.isPresent() ? "success" : "error")
                .code(200)
                .message(msg)
                .data(body)
                .build());
    }
}
