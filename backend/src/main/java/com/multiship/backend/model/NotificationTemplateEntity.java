package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A4.2 — one row per email event. Key is the natural primary key
 * (upper-case dotted convention: {@code AUTH.VERIFY_EMAIL}); templates
 * are Handlebars source strings rendered by
 * {@link com.multiship.backend.service.mail.TemplateRenderer}.
 */
@Entity
@Table(name = "notification_template")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplateEntity {

    @Id
    @Column(name = "template_key", length = 60)
    private String templateKey;

    @Column(length = 200)
    private String description;

    @Column(name = "subject_template", nullable = false, columnDefinition = "TEXT")
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    /**
     * A4.5 — when TRUE, users can opt out via /settings/notifications and
     * {@link com.multiship.backend.service.mail.NotificationService} skips
     * their sends. Transactional keys (invite, password reset) stay FALSE
     * — you can't opt out of a password reset for yourself.
     */
    @Column(name = "opt_out_allowed", nullable = false)
    private boolean optOutAllowed;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
