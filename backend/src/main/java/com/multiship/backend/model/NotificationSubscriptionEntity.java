package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A4.5 — one row per (user, template) where the user has explicitly
 * toggled. Absence of a row means "default subscribed" (industry norm
 * for transactional apps). Only checked at send time when
 * {@link NotificationTemplateEntity#isOptOutAllowed()} is TRUE.
 */
@Entity
@Table(name = "notification_subscription")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSubscriptionEntity {

    @EmbeddedId
    private NotificationSubscriptionId id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @jakarta.persistence.Embeddable
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationSubscriptionId implements Serializable {
        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "template_key", nullable = false, length = 60)
        private String templateKey;
    }
}
