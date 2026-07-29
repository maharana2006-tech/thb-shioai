package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 46 — per-API-key subscription to a platform event. When the
 * dispatcher fires a matching event, the runner POSTs the JSON payload
 * to {@link #url} with an HMAC-SHA256 signature header keyed by
 * {@link #secret}.
 *
 * <p>Delivery uses the same pattern as Sprint 45's scheduled report
 * webhook delivery (RestClient POST + retry with exponential backoff).
 * The delivery result is captured on {@link ExternalWebhookDelivery}.
 */
@Entity
@Table(name = "external_webhook_subscription",
        indexes = @Index(name = "idx_ext_webhook_key_event", columnList = "api_key_id, event"))
@Data
public class ExternalWebhookSubscription {

    public enum EventType { LABEL_GENERATED, TRACKING_UPDATED, EXCEPTION, RULE_BLOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The API key that owns this subscription; scopes events to that key's client. */
    @Column(name = "api_key_id", nullable = false)
    private Long apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventType event;

    @Column(nullable = false, length = 500)
    private String url;

    /** HMAC-SHA256 secret. Never returned in list responses; masked in the DTO. */
    @Column(nullable = false, length = 200)
    private String secret;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
