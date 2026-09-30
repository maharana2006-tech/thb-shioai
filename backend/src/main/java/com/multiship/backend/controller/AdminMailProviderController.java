package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MailProviderDTO;
import com.multiship.backend.dto.MailProviderUpsertRequest;
import com.multiship.backend.dto.MailTestSendRequest;
import com.multiship.backend.model.MailProviderEntity;
import com.multiship.backend.service.mail.ConfiguredMailSender;
import com.multiship.backend.service.mail.MailConfigService;
import com.multiship.backend.service.mail.MailProviderRegistry;
import com.multiship.backend.service.mail.MailSendException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A4.1 — admin CRUD for mail providers + the "send test" endpoint used by
 * the {@code /settings/mail} page. ADMIN role required for everything.
 * Secret values NEVER appear in a response body — the "config" map for
 * each provider returns "•••" for any key the SPI declares as secret.
 */
@Tag(name = "Mail providers",
        description = "Admin CRUD for the outbound email provider registry (SMTP today; SendGrid/SES/Postmark in A4.3).")
@RestController
@RequestMapping("/api/v1/admin/mail-providers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMailProviderController {

    private final MailConfigService config;
    private final MailProviderRegistry registry;
    private final ConfiguredMailSender configuredSender;

    // ─── kinds descriptor (drives FE dropdown + form) ───────────────

    @Operation(summary = "Registered provider kinds — SMTP today; extensible.")
    @GetMapping("/kinds")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listKinds() {
        List<Map<String, Object>> body = registry.kinds().stream()
                .map(config::kindDescriptor)
                .toList();
        return ok(body, "Provider kinds retrieved");
    }

    // ─── provider CRUD ──────────────────────────────────────────────

    @Operation(summary = "List every mail_provider row with redacted config.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MailProviderDTO>>> list() {
        List<MailProviderDTO> body = config.listProviders().stream()
                .map(this::toDto)
                .toList();
        return ok(body, "Providers retrieved");
    }

    @Operation(summary = "Create or update a mail_provider row + its config KV.")
    @PostMapping
    public ResponseEntity<ApiResponse<MailProviderDTO>> upsert(
            @Valid @RequestBody MailProviderUpsertRequest req,
            Authentication auth) {
        String actor = actor(auth);
        MailProviderEntity row = config.upsertProvider(req.getId(), req.getKind(), req.getDisplayName(), actor);

        if (req.getConfig() != null) {
            var provider = registry.require(req.getKind());
            for (var e : req.getConfig().entrySet()) {
                boolean secret = provider.secretConfigKeys().contains(e.getKey());
                config.putConfig(row.getId(), e.getKey(),
                        blankToNull(e.getValue()), secret, actor);
            }
        }
        return ok(toDto(row), "Provider saved");
    }

    @Operation(summary = "Activate one provider (deactivates any current active row).")
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<MailProviderDTO>> activate(
            @PathVariable Long id, Authentication auth) {
        MailProviderEntity row = config.activate(id, actor(auth));
        return ok(toDto(row), "Provider activated");
    }

    @Operation(summary = "Delete a provider row (cascades its config).")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        config.deleteProvider(id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("SUCCESS").code(200)
                .message("Provider deleted")
                .timestamp(LocalDateTime.now())
                .build());
    }

    // ─── test send ──────────────────────────────────────────────────

    @Operation(summary = "Send a test email through the CURRENTLY ACTIVE provider. "
            + "Returns 422 if no provider is active or if the provider throws.")
    @PostMapping("/test-send")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testSend(
            @Valid @RequestBody MailTestSendRequest req) {
        String subject = blankToDefault(req.getSubject(), "shioai mail test");
        String body = blankToDefault(req.getBody(),
                "This is a test message sent from /settings/mail — you can delete it.");
        try {
            configuredSender.send(req.getTo(), subject, body);
            Map<String, Object> data = new HashMap<>();
            data.put("delivered", true);
            return ok(data, "Test email sent");
        } catch (MailSendException ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(422)
                            .errorCode("MAIL_SEND_FAILED")
                            .message(ex.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }

    // ─── helpers ────────────────────────────────────────────────────

    private MailProviderDTO toDto(MailProviderEntity row) {
        return MailProviderDTO.builder()
                .id(row.getId())
                .kind(row.getKind())
                .displayName(row.getDisplayName())
                .active(row.isActive())
                .updatedAt(row.getUpdatedAt())
                .updatedBy(row.getUpdatedBy())
                .config(config.loadConfigRedacted(row.getId()))
                .build();
    }

    private static String actor(Authentication auth) {
        return auth == null ? "system" : auth.getName();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String blankToDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS")
                .code(200)
                .message(message)
                .data(body)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
