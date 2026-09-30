package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.EnabledChannelsRequest;
import com.multiship.backend.dto.EnabledChannelsResponse;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.TenantSettingsService;
import com.multiship.backend.service.TenantSettingsService.Channel;
import com.multiship.backend.service.carrier.ShipperDefaultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Admin surface for per-tenant preferences backed by
 * {@link TenantSettingsService}. First endpoint: enabled-channels
 * (D2C / B2B / both) which gates the external API + WMS pull.
 *
 * <p>ADMIN role required + tenant-scope check via
 * {@link TenantScopeEnforcer} — a tenant-scoped admin can only see /
 * modify their own tenant's row; a platform operator can address any
 * tenant.
 */
@Tag(name = "Tenant settings", description = "Per-tenant preferences (channel gate, future feature flags)")
@RestController
@RequestMapping("/api/v1/tenants/{tenantCode}/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class TenantSettingsController {

    private final TenantSettingsService settings;
    private final TenantScopeEnforcer scope;
    private final ShipperDefaultsService shipperDefaults;

    @Operation(summary = "Get enabled shipping channels for this tenant")
    @GetMapping("/enabled-channels")
    public ResponseEntity<ApiResponse<EnabledChannelsResponse>> getEnabledChannels(
            @PathVariable String tenantCode) {
        scope.requireTenantMatch(tenantCode);
        EnumSet<Channel> current = settings.getEnabledChannels(tenantCode);
        EnabledChannelsResponse body = EnabledChannelsResponse.builder()
                .tenantCode(tenantCode)
                .enabledChannels(current.stream()
                        .sorted(java.util.Comparator.comparing(Enum::name))
                        .map(Enum::name).toList())
                .isConfigured(!current.isEmpty())
                .build();
        return ResponseEntity.ok(ApiResponse.<EnabledChannelsResponse>builder()
                .status("SUCCESS")
                .code(200)
                .timestamp(LocalDateTime.now())
                .data(body)
                .build());
    }

    /**
     * Generic KV read for tenant settings. Returns the raw string
     * value or 404 if unset. Used by /settings/system for the NDS
     * fallbacks card and any future single-key preferences.
     */
    @Operation(summary = "Get a raw tenant setting by key. 404 when unset.")
    @GetMapping("/{key}")
    public ResponseEntity<ApiResponse<KvSettingResponse>> getKv(
            @PathVariable String tenantCode,
            @PathVariable String key) {
        scope.requireTenantMatch(tenantCode);
        String v = settings.getSetting(tenantCode, key).orElse(null);
        if (v == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(ApiResponse.<KvSettingResponse>builder()
                            .status("ERROR").code(404).timestamp(LocalDateTime.now())
                            .message("setting not found")
                            .build());
        }
        return ResponseEntity.ok(ApiResponse.<KvSettingResponse>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(new KvSettingResponse(tenantCode, key, v))
                .build());
    }

    @Operation(summary = "Upsert a raw tenant setting.")
    @PutMapping("/{key}")
    public ResponseEntity<ApiResponse<KvSettingResponse>> putKv(
            @PathVariable String tenantCode,
            @PathVariable String key,
            @RequestBody KvSettingRequest body,
            Authentication auth) {
        scope.requireTenantMatch(tenantCode);
        if (body == null || body.value() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "value is required");
        }
        settings.putSetting(tenantCode, key, body.value(), auth != null ? auth.getName() : null);
        return ResponseEntity.ok(ApiResponse.<KvSettingResponse>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(new KvSettingResponse(tenantCode, key, body.value()))
                .build());
    }

    public record KvSettingRequest(String value) {}
    public record KvSettingResponse(String tenantCode, String key, String value) {}

    // ─── C1 — ship-from defaults bundle ─────────────────────────────

    @Operation(summary = "Get all shipper.* settings for this tenant + the platform default they fall back to.")
    @GetMapping("/shipper-defaults")
    public ResponseEntity<ApiResponse<java.util.Map<String, ShipperDefaultsService.FieldValue>>> getShipperDefaults(
            @PathVariable String tenantCode) {
        scope.requireTenantMatch(tenantCode);
        return ResponseEntity.ok(ApiResponse.<java.util.Map<String, ShipperDefaultsService.FieldValue>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(shipperDefaults.currentValues(tenantCode))
                .build());
    }

    @Operation(summary = "Bulk upsert the 8 shipper.* keys for this tenant. "
            + "Blank strings delete the override (falls back to platform default).")
    @PutMapping("/shipper-defaults")
    public ResponseEntity<ApiResponse<java.util.Map<String, ShipperDefaultsService.FieldValue>>> putShipperDefaults(
            @PathVariable String tenantCode,
            @RequestBody java.util.Map<String, String> body,
            Authentication auth) {
        scope.requireTenantMatch(tenantCode);
        String actor = auth != null ? auth.getName() : null;
        for (String key : ShipperDefaultsService.ALL_KEYS) {
            String v = body == null ? null : body.get(key);
            if (v == null || v.isBlank()) {
                settings.deleteSetting(tenantCode, key);
            } else {
                settings.putSetting(tenantCode, key, v.trim(), actor);
            }
        }
        return ResponseEntity.ok(ApiResponse.<java.util.Map<String, ShipperDefaultsService.FieldValue>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(shipperDefaults.currentValues(tenantCode))
                .build());
    }

    @Operation(summary = "Set enabled shipping channels for this tenant. "
            + "Must include at least one of D2C, B2B.")
    @PutMapping("/enabled-channels")
    public ResponseEntity<ApiResponse<EnabledChannelsResponse>> setEnabledChannels(
            @PathVariable String tenantCode,
            @RequestBody EnabledChannelsRequest request,
            Authentication auth) {
        scope.requireTenantMatch(tenantCode);
        List<String> raw = request == null ? List.of() : Optional.ofNullable(request.getEnabledChannels()).orElse(List.of());
        EnumSet<Channel> parsed = EnumSet.noneOf(Channel.class);
        for (String token : raw) {
            Channel.parse(token).ifPresent(parsed::add);
        }
        if (parsed.isEmpty()) {
            // Same guard as the service, surfaced early with a friendlier
            // message for the FE toast. ResponseStatusException carries
            // its own HTTP status through GlobalExceptionHandler — plain
            // IllegalArgumentException fell through to the RuntimeException
            // catch-all and surfaced as 500 (walk-through discovery).
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Pick at least one shipping channel (D2C, B2B, or both).");
        }
        String actor = auth != null ? auth.getName() : null;
        settings.setEnabledChannels(tenantCode, parsed, actor);
        EnabledChannelsResponse body = EnabledChannelsResponse.builder()
                .tenantCode(tenantCode)
                .enabledChannels(parsed.stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .map(Enum::name).toList())
                .isConfigured(true)
                .build();
        return ResponseEntity.ok(ApiResponse.<EnabledChannelsResponse>builder()
                .status("SUCCESS")
                .code(200)
                .timestamp(LocalDateTime.now())
                .data(body)
                .build());
    }
}
