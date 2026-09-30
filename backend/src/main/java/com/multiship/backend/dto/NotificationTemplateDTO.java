package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A4.2 — serialization shape for /admin/notification-templates. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplateDTO {
    private String templateKey;
    private String description;
    private String subjectTemplate;
    private String bodyTemplate;
    /** A4.5 — when true, users can opt out from /settings/notifications. */
    private boolean optOutAllowed;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
