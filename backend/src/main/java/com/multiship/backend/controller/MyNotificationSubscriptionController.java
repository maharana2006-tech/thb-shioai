package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.MyNotificationSubscriptionDTO;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.NotificationTemplateRepository;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.mail.NotificationSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A4.5 — self-serve subscription management for the current user. Drives
 * /settings/notifications. Any authenticated role can call these.
 *
 * <p>The list endpoint only returns templates where
 * {@code opt_out_allowed=true}; transactional templates never appear.
 */
@Tag(name = "My notification subscriptions",
        description = "Per-user opt-out toggles for the alert-style email templates.")
@RestController
@RequestMapping("/api/v1/me/notification-subscriptions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyNotificationSubscriptionController {

    private final NotificationTemplateRepository templateRepo;
    private final NotificationSubscriptionService subs;
    private final UserRepository userRepo;

    @Operation(summary = "List every opt-out-allowed template + whether the current user is subscribed.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<MyNotificationSubscriptionDTO>>> list(Authentication auth) {
        User me = requireMe(auth);
        List<MyNotificationSubscriptionDTO> body = templateRepo.findAll().stream()
                .filter(t -> t.isOptOutAllowed())
                .map(t -> MyNotificationSubscriptionDTO.builder()
                        .templateKey(t.getTemplateKey())
                        .description(t.getDescription())
                        .subscribed(subs.isSubscribed(me.getId(), t.getTemplateKey()))
                        .build())
                .toList();
        return ok(body, "Subscriptions retrieved");
    }

    @Operation(summary = "Set the current user's toggle for one template. 404 if the template is not opt-out-allowed.")
    @PutMapping("/{templateKey}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> set(
            @PathVariable String templateKey,
            @RequestParam boolean enabled,
            Authentication auth) {
        User me = requireMe(auth);
        var tpl = templateRepo.findById(templateKey).orElse(null);
        if (tpl == null || !tpl.isOptOutAllowed()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(404).errorCode("SUBSCRIPTION_NOT_APPLICABLE")
                            .message("Template not found or not opt-out-allowed: " + templateKey)
                            .timestamp(LocalDateTime.now()).build());
        }
        subs.setForUser(me.getId(), templateKey, enabled, auth.getName());
        return ok(Map.of("templateKey", templateKey, "enabled", enabled), "Subscription updated");
    }

    private User requireMe(Authentication auth) {
        String name = auth.getName();
        return userRepo.findByUsername(name).orElseThrow(() ->
                new IllegalStateException("Authenticated user not found in DB: " + name));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
