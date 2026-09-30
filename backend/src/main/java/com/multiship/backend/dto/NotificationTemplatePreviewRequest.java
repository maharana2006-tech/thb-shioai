package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A4.2 — preview a template render without sending. Either supply a stored
 * template by {@code templateKey}, or supply raw {@code subjectTemplate} +
 * {@code bodyTemplate} strings to preview an unsaved edit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplatePreviewRequest {

    /** Preview a stored template (mutually exclusive with the two raw strings). */
    private String templateKey;

    /** Preview an unsaved subject string. */
    private String subjectTemplate;

    /** Preview an unsaved body string. */
    private String bodyTemplate;

    /** Vars to bind — typically the same shape the call site would pass at send time. */
    private Map<String, Object> vars;
}
