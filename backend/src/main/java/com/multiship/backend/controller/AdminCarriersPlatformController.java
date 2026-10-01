package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierPlatformEntity;
import com.multiship.backend.service.carriers.platform.CarrierPlatformService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V112 — admin surface for the platform-wide {@code carriers} table.
 * Flip {@code enabled} to disable a carrier org-wide without a code
 * deploy (closes Auth Gap-6-A); flip {@code mode} between LIVE and
 * TEST as the org-wide switch (per-account env still wins for now).
 */
@Tag(name = "Carriers (platform)",
        description = "Platform-wide carrier registry — enabled, mode, family. Admin-only.")
@RestController
@RequestMapping("/api/v1/admin/carriers/platform")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCarriersPlatformController {

    private final CarrierPlatformService svc;

    @Operation(summary = "List every row in the carriers table.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        List<Map<String, Object>> body = svc.list().stream().map(AdminCarriersPlatformController::toDto).toList();
        return ok(body);
    }

    @Operation(summary = "Toggle a carrier's enabled flag and/or mode. Either field is optional "
            + "— pass only what you want to change.")
    @PutMapping("/{carrierCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable String carrierCode,
            @RequestBody UpdateRequest req) {
        CarrierPlatformEntity row = svc.updateEnabledAndMode(carrierCode, req.enabled, req.mode);
        return ok(toDto(row));
    }

    public static class UpdateRequest {
        public Boolean enabled;
        public String mode; // LIVE or TEST
    }

    private static Map<String, Object> toDto(CarrierPlatformEntity r) {
        Map<String, Object> m = new HashMap<>();
        m.put("carrierCode", r.getCarrierCode());
        m.put("displayName", r.getDisplayName());
        m.put("enabled", Boolean.TRUE.equals(r.getEnabled()));
        m.put("mode", r.getMode());
        m.put("family", r.getFamily());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
