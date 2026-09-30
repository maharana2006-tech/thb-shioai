package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.NotificationDeliveryLogDTO;
import com.multiship.backend.model.NotificationDeliveryLogEntity;
import com.multiship.backend.repository.NotificationDeliveryLogRepository;
import com.multiship.backend.service.mail.MailSendException;
import com.multiship.backend.service.mail.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * A4.4 — read + retry for {@code notification_delivery_log}. Drives the
 * /settings/notification-delivery-log admin page. ADMIN role only.
 */
@Tag(name = "Notification delivery log",
        description = "Read the outbound-email dispatch journal, filter, and retry failed sends.")
@RestController
@RequestMapping("/api/v1/admin/notification-delivery-log")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationDeliveryLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationDeliveryLogRepository repo;
    private final NotificationService notifications;

    @Operation(summary = "Paginated list. All filters optional.")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String templateKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String recipient,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int clampedPage = Math.max(0, page);
        String recipientLike = (recipient == null || recipient.isBlank())
                ? null : "%" + recipient.trim().toLowerCase() + "%";

        Page<NotificationDeliveryLogEntity> pageResult = repo.search(
                blankToNull(templateKey), blankToNull(status), recipientLike,
                PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "sentAt")));

        Map<String, Object> body = new HashMap<>();
        body.put("items", pageResult.getContent().stream().map(this::toDto).toList());
        body.put("page", pageResult.getNumber());
        body.put("size", pageResult.getSize());
        body.put("totalElements", pageResult.getTotalElements());
        body.put("totalPages", pageResult.getTotalPages());
        return ok(body, "Delivery log retrieved");
    }

    @Operation(summary = "Retry a logged send. The stored subject + body are re-sent as-is "
            + "(no re-render); a fresh row is inserted with retry_of_id linking to the original.")
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(@PathVariable Long id) {
        NotificationDeliveryLogEntity row = repo.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(404).errorCode("LOG_ROW_NOT_FOUND")
                            .message("notification_delivery_log not found: " + id)
                            .timestamp(LocalDateTime.now()).build());
        }
        try {
            notifications.resend(row.getTemplateKey(), row.getRecipient(),
                    row.getSubject(), row.getBody(), row.getId());
            Map<String, Object> data = new HashMap<>();
            data.put("retriedFrom", id);
            return ok(data, "Retry attempted");
        } catch (MailSendException ex) {
            // resend() still wrote a FAILED row; surface the same 422 as /test-send.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(422).errorCode("MAIL_SEND_FAILED")
                            .message(ex.getMessage())
                            .timestamp(LocalDateTime.now()).build());
        }
    }

    private NotificationDeliveryLogDTO toDto(NotificationDeliveryLogEntity row) {
        return NotificationDeliveryLogDTO.builder()
                .id(row.getId())
                .templateKey(row.getTemplateKey())
                .recipient(row.getRecipient())
                .subject(row.getSubject())
                .body(row.getBody())
                .status(row.getStatus())
                .providerKind(row.getProviderKind())
                .providerId(row.getProviderId())
                .errorMessage(row.getErrorMessage())
                .latencyMs(row.getLatencyMs())
                .retryOfId(row.getRetryOfId())
                .sentAt(row.getSentAt())
                .build();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message(message).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
