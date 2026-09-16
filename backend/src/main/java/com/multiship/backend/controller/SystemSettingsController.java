package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.SystemSettingDTO;
import com.multiship.backend.dto.UspsProviderReadinessDTO;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.UspsProviderReadinessService;
import com.multiship.backend.service.ai.OpenAiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                    "SWSIM"),
            // USPS_DIRECT integration state machine. Three-valued so the operator
            // can flip a platform-wide switch: STAMPS_COM (legacy — every USPS
            // account uses Stamps.com) → PROVISIONING_USPS_DIRECT (seed OAuth
            // consumer creds + per-account EPS/CRID/MID identifiers) → USPS_DIRECT
            // (every USPS account uses developers.usps.com OAuth 2.0 direct).
            // Direct STAMPS_COM ↔ USPS_DIRECT jumps are rejected by the transition
            // guard on the PUT path.
            new SettingSpec(
                    UspsProviderReadinessService.USPS_PROVIDER_KEY,
                    "USPS integration provider (3-value state machine). "
                            + "STAMPS_COM = legacy Stamps.com path (every USPS account uses Stamps). "
                            + "PROVISIONING_USPS_DIRECT = onboarding transitional state — "
                            + "seed USPS_PLATFORM_CLIENT_ID + USPS_PLATFORM_CLIENT_SECRET, "
                            + "fill every USPS account's EPS/CRID/MID identifiers. "
                            + "USPS_DIRECT = direct developers.usps.com OAuth 2.0 integration. "
                            + "Direct STAMPS_COM ↔ USPS_DIRECT transitions are blocked; go via "
                            + "PROVISIONING_USPS_DIRECT so readiness is verified first.",
                    SystemSettingDTO.Kind.CHOICE,
                    List.of("STAMPS_COM", "PROVISIONING_USPS_DIRECT", "USPS_DIRECT"),
                    "STAMPS_COM"),
            // USPS OAuth 2.0 consumer key issued by the platform's app registration
            // at developers.usps.com. Used by the USPS_DIRECT connector to obtain
            // an access token. SECRET — the FE only ever sees the masked value.
            new SettingSpec(
                    UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY,
                    "USPS OAuth 2.0 Consumer Key from platform's app at developers.usps.com.",
                    SystemSettingDTO.Kind.SECRET,
                    null, null),
            new SettingSpec(
                    UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY,
                    "USPS OAuth 2.0 Consumer Secret.",
                    SystemSettingDTO.Kind.SECRET,
                    null, null)
    );

    /**
     * Legal transitions for the USPS_PROVIDER state machine. Every pair
     * NOT in this set is rejected 400 (direct STAMPS_COM ↔ USPS_DIRECT
     * skips validation of readiness). Reflexive transitions (X → X) are
     * always allowed and short-circuited in the guard.
     */
    private static final Set<String> LEGAL_USPS_TRANSITIONS = Set.of(
            "STAMPS_COM->PROVISIONING_USPS_DIRECT",
            "PROVISIONING_USPS_DIRECT->STAMPS_COM",
            "PROVISIONING_USPS_DIRECT->USPS_DIRECT",
            "USPS_DIRECT->PROVISIONING_USPS_DIRECT"
    );

    private final SystemSettingService service;
    private final UspsProviderReadinessService uspsReadinessService;

    @Operation(summary = "List admin-managed settings with masked values or cleartext choice values")
    @GetMapping
    public ResponseEntity<List<SystemSettingDTO>> list() {
        List<SystemSettingDTO> body = KNOWN_SETTINGS.stream().map(this::toDTO).toList();
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Upsert a setting (encrypted at rest)")
    @PutMapping("/{key}")
    public ResponseEntity<?> update(
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

            // USPS_PROVIDER state-machine guard. Rejects illegal transitions
            // outright and blocks PROVISIONING → USPS_DIRECT when the
            // readiness check fails.
            if (UspsProviderReadinessService.USPS_PROVIDER_KEY.equals(key)) {
                ResponseEntity<?> guardResponse = guardUspsProviderTransition(value);
                if (guardResponse != null) {
                    return guardResponse;
                }
            }
        }
        service.setEncrypted(key, value, actor);
        return ResponseEntity.ok(toDTO(spec));
    }

    @Operation(summary = "Readiness snapshot for the USPS_PROVIDER transition to USPS_DIRECT",
            description = "Reports platform OAuth credential presence and per-account "
                    + "EPS/CRID/MID coverage. overallReady=true is required for the "
                    + "PROVISIONING_USPS_DIRECT → USPS_DIRECT transition to be accepted.")
    @GetMapping("/USPS_PROVIDER/readiness")
    public ResponseEntity<ApiResponse<UspsProviderReadinessDTO>> uspsProviderReadiness() {
        UspsProviderReadinessDTO snapshot = uspsReadinessService.check();
        return ResponseEntity.ok(ApiResponse.<UspsProviderReadinessDTO>builder()
                .status("SUCCESS")
                .code(200)
                .timestamp(LocalDateTime.now())
                .message(snapshot.isOverallReady()
                        ? "USPS_DIRECT is ready to activate."
                        : "USPS_DIRECT is not yet ready.")
                .data(snapshot)
                .build());
    }

    // ===== helpers =====

    /**
     * Guard the USPS_PROVIDER state machine. Returns {@code null} to allow
     * the update, or a non-2xx ResponseEntity to short-circuit it.
     */
    private ResponseEntity<?> guardUspsProviderTransition(String targetValue) {
        String current = service.getDecrypted(UspsProviderReadinessService.USPS_PROVIDER_KEY)
                .filter(StringUtils::hasText)
                .orElse(UspsProviderReadinessService.DEFAULT_PROVIDER);

        // Reflexive transition — always allowed, keeps idempotency.
        if (current.equals(targetValue)) {
            return null;
        }

        String pair = current + "->" + targetValue;
        if (!LEGAL_USPS_TRANSITIONS.contains(pair)) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "must transit via PROVISIONING_USPS_DIRECT"));
        }

        // Gate PROVISIONING_USPS_DIRECT → USPS_DIRECT on readiness.
        if (UspsProviderReadinessService.TARGET_PROVIDER.equals(targetValue)) {
            UspsProviderReadinessDTO snapshot = uspsReadinessService.check();
            if (!snapshot.isOverallReady()) {
                return ResponseEntity.status(409).body(snapshot);
            }
        }
        return null;
    }

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
