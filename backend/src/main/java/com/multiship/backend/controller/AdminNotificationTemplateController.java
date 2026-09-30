package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.NotificationTemplateDTO;
import com.multiship.backend.dto.NotificationTemplatePreviewRequest;
import com.multiship.backend.dto.NotificationTemplateUpsertRequest;
import com.multiship.backend.model.NotificationTemplateEntity;
import com.multiship.backend.repository.NotificationTemplateRepository;
import com.multiship.backend.service.mail.MailSendException;
import com.multiship.backend.service.mail.TemplateRenderer;
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
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A4.2 — admin CRUD for {@code notification_template}. Drives
 * /settings/notification-templates. Preview endpoint compiles + renders
 * without sending, so operators can iterate on templates safely.
 */
@Tag(name = "Notification templates",
        description = "Admin CRUD for Handlebars email templates used by the AUTH.* and (future) OPS.* / BILLING.* events.")
@RestController
@RequestMapping("/api/v1/admin/notification-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationTemplateController {

    private final NotificationTemplateRepository repo;
    private final TemplateRenderer renderer;

    @Operation(summary = "List all templates.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationTemplateDTO>>> list() {
        List<NotificationTemplateDTO> body = repo.findAll().stream().map(this::toDto).toList();
        return ok(body, "Templates retrieved");
    }

    @Operation(summary = "Fetch one template by key.")
    @GetMapping("/{key}")
    public ResponseEntity<ApiResponse<NotificationTemplateDTO>> get(@PathVariable String key) {
        return repo.findById(key)
                .map(row -> ok(toDto(row), "Template retrieved"))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiResponse.<NotificationTemplateDTO>builder()
                                .status("ERROR").code(404).errorCode("TEMPLATE_NOT_FOUND")
                                .message("notification_template not found: " + key)
                                .timestamp(LocalDateTime.now()).build()));
    }

    @Operation(summary = "Create or update a template by key. Path key wins over any key in the body.")
    @PutMapping("/{key}")
    public ResponseEntity<ApiResponse<NotificationTemplateDTO>> upsert(
            @PathVariable String key,
            @Valid @RequestBody NotificationTemplateUpsertRequest req,
            Authentication auth) {
        NotificationTemplateEntity row = repo.findById(key).orElseGet(() -> {
            NotificationTemplateEntity fresh = new NotificationTemplateEntity();
            fresh.setTemplateKey(key);
            return fresh;
        });
        row.setDescription(req.getDescription());
        row.setSubjectTemplate(req.getSubjectTemplate());
        row.setBodyTemplate(req.getBodyTemplate());
        row.setOptOutAllowed(req.isOptOutAllowed());
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(auth == null ? "system" : auth.getName());
        return ok(toDto(repo.save(row)), "Template saved");
    }

    @Operation(summary = "Delete a template by key. Callers that reference a deleted key will 500 with TEMPLATE_NOT_FOUND — reserve for retired events.")
    @DeleteMapping("/{key}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String key) {
        repo.deleteById(key);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .status("SUCCESS").code(200)
                .message("Template deleted")
                .timestamp(LocalDateTime.now()).build());
    }

    /**
     * Preview a rendered template without sending. Either resolves by key or
     * uses the supplied raw strings — the FE editor sends the raw strings so
     * unsaved edits can be previewed.
     */
    @Operation(summary = "Render subject + body without sending. 422 on Handlebars compile error.")
    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<Map<String, String>>> preview(
            @RequestBody NotificationTemplatePreviewRequest req) {
        String subjectSrc = req.getSubjectTemplate();
        String bodySrc = req.getBodyTemplate();
        if ((subjectSrc == null || bodySrc == null) && req.getTemplateKey() != null) {
            NotificationTemplateEntity row = repo.findById(req.getTemplateKey()).orElse(null);
            if (row != null) {
                if (subjectSrc == null) subjectSrc = row.getSubjectTemplate();
                if (bodySrc == null) bodySrc = row.getBodyTemplate();
            }
        }
        if (subjectSrc == null || bodySrc == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, String>>builder()
                            .status("ERROR").code(422).errorCode("TEMPLATE_INCOMPLETE")
                            .message("Supply subjectTemplate + bodyTemplate, or a templateKey that exists.")
                            .timestamp(LocalDateTime.now()).build());
        }
        try {
            Map<String, String> out = new HashMap<>();
            out.put("subject", renderer.render(subjectSrc, req.getVars()));
            out.put("body", renderer.render(bodySrc, req.getVars()));
            return ok(out, "Rendered");
        } catch (MailSendException ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, String>>builder()
                            .status("ERROR").code(422).errorCode("TEMPLATE_RENDER_FAILED")
                            .message(ex.getMessage())
                            .timestamp(LocalDateTime.now()).build());
        }
    }

    private NotificationTemplateDTO toDto(NotificationTemplateEntity row) {
        return NotificationTemplateDTO.builder()
                .templateKey(row.getTemplateKey())
                .description(row.getDescription())
                .subjectTemplate(row.getSubjectTemplate())
                .bodyTemplate(row.getBodyTemplate())
                .optOutAllowed(row.isOptOutAllowed())
                .updatedAt(row.getUpdatedAt())
                .updatedBy(row.getUpdatedBy())
                .build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
