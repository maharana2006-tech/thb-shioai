package com.multiship.backend.controller;

import com.multiship.backend.dto.SystemSettingDTO;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.ai.OpenAiClient;
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

import java.util.List;
import java.util.Map;

/**
 * Sprint 49 Tier 0 — admin surface for {@link SystemSettingService}.
 *
 * <p>Currently exposes the OpenAI API key setting. Extends naturally as
 * new admin-managed secrets are added to {@link #KNOWN_SETTINGS}.
 *
 * <p>All endpoints require ADMIN role. Responses never include the
 * decrypted value; only masked ({@code "****" + last4}) previews.
 */
@Tag(name = "System settings", description = "Admin-managed encrypted secrets (OpenAI key, etc.)")
@RestController
@RequestMapping("/api/v1/admin/system-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SystemSettingsController {

    /**
     * Registry of admin-manageable settings. Each entry describes the
     * setting's kind (SECRET vs CHOICE), the FE render hints, and the
     * validation rules on the {@code /put} path.
     */
    private static final List<SettingSpec> KNOWN_SETTINGS = List.of(
            new SettingSpec(
                    OpenAiClient.SETTING_KEY,
                    "OpenAI API key used by AI-assist features (paste-to-fill, HS suggest). "
                            + "Overrides the OPENAI_API_KEY env var.",
                    SystemSettingDTO.Kind.SECRET,
                    null, null),
            // Site-wide Stamps.com API-flavor toggle. Overrides the property
            // default (carrier.stamps.api-flavor) for every USPS/Stamps account
            // on the platform. CHOICE kind — the FE renders a radio picker
            // and displays the current cleartext value (not a secret).
            new SettingSpec(
                    com.multiship.backend.service.carriers.StampsConnector.FLAVOR_SETTING_KEY,
                    "Stamps.com API flavor for every USPS account on the platform. "
                            + "SWSIM = legacy SOAP (Client ID must be a GUID); "
                            + "SERA = newer OAuth 2.0 REST (3-legged authorize flow, "
                            + "Client ID is an opaque string). Overrides "
                            + "carrier.stamps.api-flavor. Defaults to SWSIM.",
                    SystemSettingDTO.Kind.CHOICE,
                    List.of("SWSIM", "SERA"),
                    "SWSIM")
    );

    private final SystemSettingService service;

    @Operation(summary = "List admin-managed settings with masked values or cleartext choice values")
    @GetMapping
    public ResponseEntity<List<SystemSettingDTO>> list() {
        List<SystemSettingDTO> body = KNOWN_SETTINGS.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Upsert a setting (encrypted at rest)")
    @PutMapping("/{key}")
    public ResponseEntity<SystemSettingDTO> update(
            @PathVariable String key,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String value = body == null ? null : body.get("value");
        String actor = auth == null ? "unknown" : auth.getName();

        SettingSpec spec = findSpec(key);
        if (spec == null) {
            return ResponseEntity.notFound().build();
        }
        // CHOICE settings validate against their options — a rogue caller
        // can't seed "PIGEON_POST" as the Stamps flavor. SECRET settings
        // accept any non-blank value (validation lives on the consumer).
        if (spec.kind == SystemSettingDTO.Kind.CHOICE) {
            if (value == null || spec.options == null
                    || !spec.options.contains(value.trim())) {
                return ResponseEntity.badRequest().build();
            }
            value = value.trim();
        }
        service.setEncrypted(key, value, actor);
        return ResponseEntity.ok(toDTO(spec));
    }

    // ===== helpers =====

    private SystemSettingDTO toDTO(SettingSpec spec) {
        boolean has = service.has(spec.key);
        String masked = has ? service.maskedPreview(spec.key).orElse("(encrypted — no decrypt key)") : "";
        String current = null;
        if (spec.kind == SystemSettingDTO.Kind.CHOICE) {
            // CHOICE values are safe to reveal — the FE needs the current
            // pick to highlight the selected radio button. Fall through
            // to the default when nothing's stored yet.
            current = has ? service.getDecrypted(spec.key).orElse(spec.defaultValue)
                    : spec.defaultValue;
        }
        return new SystemSettingDTO(
                spec.key, has, masked, spec.description,
                spec.kind, spec.options, current, spec.defaultValue);
    }

    private static SettingSpec findSpec(String key) {
        return KNOWN_SETTINGS.stream()
                .filter(s -> s.key.equals(key))
                .findFirst()
                .orElse(null);
    }

    /** Registry entry — key + description + render hints. */
    private record SettingSpec(String key, String description,
                                SystemSettingDTO.Kind kind, List<String> options,
                                String defaultValue) {}
}
