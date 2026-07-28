package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 46 — one attempt at delivering an event to a subscribed URL.
 * Persisted for audit + retry decisions.
 */
@Entity
@Table(name = "external_webhook_delivery",
        indexes = @Index(name = "idx_ext_webhook_delivery_sub", columnList = "subscription_id"))
@Data
public class ExternalWebhookDelivery {

    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(nullable = false, length = 30)
    private String event;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    /** HTTP status code from the target; null when the request never landed. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** How many attempts (including the successful one). */
    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
