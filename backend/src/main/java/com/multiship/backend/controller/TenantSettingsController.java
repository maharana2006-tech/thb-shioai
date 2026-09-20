package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.EnabledChannelsRequest;
import com.multiship.backend.dto.EnabledChannelsResponse;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.TenantSettingsService;
import com.multiship.backend.service.TenantSettingsService.Channel;
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
            // message for the FE toast.
            throw new IllegalArgumentException(
                    "Pick at least one shipping channel (D2C, B2B, or both).");
        }
        String actor = auth != null ? auth.getName() : null;
        settings.setEnabledChannels(tenantCode, parsed, actor);
        EnabledChannelsResponse body = EnabledChannelsResponse.builder()
                .tenantCode(tenantCode)
                .enabledChannels(parsed.stream().sorted().map(Enum::name).toList())
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
