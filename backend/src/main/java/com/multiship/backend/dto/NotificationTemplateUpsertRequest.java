package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A4.2 — request body for PUT /admin/notification-templates/{key}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplateUpsertRequest {

    private String description;

    @NotBlank(message = "subjectTemplate required")
    private String subjectTemplate;

    @NotBlank(message = "bodyTemplate required")
    private String bodyTemplate;
}
