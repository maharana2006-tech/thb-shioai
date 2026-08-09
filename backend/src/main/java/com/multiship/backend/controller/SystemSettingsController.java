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
     * Registry of admin-manageable settings. Each entry surfaces on the
     * Settings page with its description; the actual value flow is via
     * {@link SystemSettingService}.
     */
    private static final List<Map.Entry<String, String>> KNOWN_SETTINGS = List.of(
            Map.entry(OpenAiClient.SETTING_KEY,
                    "OpenAI API key used by AI-assist features (paste-to-fill, HS suggest). "
                            + "Overrides the OPENAI_API_KEY env var.")
    );

    private final SystemSettingService service;

    @Operation(summary = "List admin-managed settings with masked values")
    @GetMapping
    public ResponseEntity<List<SystemSettingDTO>> list() {
        List<SystemSettingDTO> body = KNOWN_SETTINGS.stream().map(e -> {
            String key = e.getKey();
            boolean has = service.has(key);
            String masked = has ? service.maskedPreview(key).orElse("(encrypted — no decrypt key)") : "";
            return new SystemSettingDTO(key, has, masked, e.getValue());
        }).toList();
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

        if (!isKnown(key)) {
            return ResponseEntity.notFound().build();
        }
        service.setEncrypted(key, value, actor);
        String masked = service.maskedPreview(key).orElse("");
        return ResponseEntity.ok(new SystemSettingDTO(
                key, service.has(key), masked, descriptionFor(key)));
    }

    private static boolean isKnown(String key) {
        return KNOWN_SETTINGS.stream().anyMatch(e -> e.getKey().equals(key));
    }

    private static String descriptionFor(String key) {
        return KNOWN_SETTINGS.stream()
                .filter(e -> e.getKey().equals(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }
}
