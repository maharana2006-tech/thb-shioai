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
            + "Blank strings delete the override (falls back to platform default). "
            + "An all-empty body clears every override. Otherwise every REQUIRED_KEY "
            + "must be populated — a half-configured tenant is refused with 422.")
    @PutMapping("/shipper-defaults")
    public ResponseEntity<ApiResponse<java.util.Map<String, ShipperDefaultsService.FieldValue>>> putShipperDefaults(
            @PathVariable String tenantCode,
            @RequestBody java.util.Map<String, String> body,
            Authentication auth) {
        scope.requireTenantMatch(tenantCode);
        validateShipperDefaultsBody(body);
        String actor = auth != null ? auth.getName() : null;
        for (String key : ShipperDefaultsService.ALL_KEYS) {
            String v = body == null ? null : body.get(key);
            if (v == null || v.isBlank()) {
                settings.deleteSetting(tenantCode, key);
            } else {
                // BUG-027 — normalise country to upper-case ISO-2 so stored
                // values stay canonical regardless of operator typing.
                String normalised = ShipperDefaultsService.KEY_COUNTRY_CODE.equals(key)
                        ? v.trim().toUpperCase(java.util.Locale.ROOT)
                        : v.trim();
                settings.putSetting(tenantCode, key, normalised, actor);
            }
        }
        return ResponseEntity.ok(ApiResponse.<java.util.Map<String, ShipperDefaultsService.FieldValue>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(shipperDefaults.currentValues(tenantCode))
                .build());
    }

    /** ISO-3166-1 alpha-2: exactly two ASCII letters. */
    private static final java.util.regex.Pattern ISO2_COUNTRY =
            java.util.regex.Pattern.compile("^[A-Za-z]{2}$");

    /**
     * BUG-027 (QA 2026-10-23) — pre-fix the shipper-defaults save endpoint
     * accepted an all-empty body, a name-only body, and country code
     * "USA" (3 letters), all returning 200 with the stored-as-typed
     * values. The incomplete tenants silently failed at shipping time
     * instead; the ISO-2 field accepted anything.
     *
     * <p>Rules:
     * <ol>
     *   <li>All-empty body → OK (clear every override; a legitimate
     *       "revert to platform defaults" flow).</li>
     *   <li>Otherwise every {@link ShipperDefaultsService#REQUIRED_KEYS}
     *       field must be populated — refuse half-configured tenants.</li>
     *   <li>Country code, if present, must be 2 ASCII letters.</li>
     * </ol>
     */
    static void validateShipperDefaultsBody(java.util.Map<String, String> body) {
        java.util.Map<String, String> source = body == null ? java.util.Map.of() : body;
        java.util.List<String> missing = new java.util.ArrayList<>();
        int populatedRequired = 0;
        for (String key : ShipperDefaultsService.REQUIRED_KEYS) {
            String v = source.get(key);
            if (v == null || v.isBlank()) {
                missing.add(key);
            } else {
                populatedRequired++;
            }
        }
        if (populatedRequired > 0 && !missing.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Set every required ship-from field together, or clear all. Missing: "
                            + String.join(", ", missing));
        }
        String country = source.get(ShipperDefaultsService.KEY_COUNTRY_CODE);
        if (country != null && !country.isBlank() && !ISO2_COUNTRY.matcher(country.trim()).matches()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Country code must be the 2-letter ISO code (e.g. US, GB, DE); got '"
                            + country.trim() + "'.");
        }
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
