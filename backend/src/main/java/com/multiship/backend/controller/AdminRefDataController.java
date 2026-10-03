package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.refdata.RefDataPlatformService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** V117 — read-only admin surface over the three reference-data tables. */
@Tag(name = "Reference data",
        description = "reason_for_export + iso_currency + country_region. Admin-only, read-only.")
@RestController
@RequestMapping("/api/v1/admin/refdata")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefDataController {

    private final RefDataPlatformService svc;

    @Operation(summary = "List every reason_for_export row (sorted).")
    @GetMapping("/reasons-for-export")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reasons() {
        List<Map<String, Object>> body = svc.listReasonsForExport().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("code", r.getCode());
            m.put("label", r.getLabel());
            m.put("sortOrder", r.getSortOrder());
            return m;
        }).toList();
        return ok(body);
    }

    @Operation(summary = "List every iso_currency row.")
    @GetMapping("/currencies")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> currencies() {
        List<Map<String, Object>> body = svc.listIsoCurrencies().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("code", r.getCode());
            m.put("name", r.getName());
            return m;
        }).toList();
        return ok(body);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
