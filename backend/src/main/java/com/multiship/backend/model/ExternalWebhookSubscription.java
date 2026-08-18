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

    /**
     * HMAC-SHA256 secret — plaintext. Legacy column. Never returned in
     * list responses (DTO masks). Kept nullable for the R2 #336 transition:
     * new rows write the encrypted form below and NULL this out; the
     * dispatcher's {@link #resolveSecret} helper reads the encrypted form
     * first and falls back to this only for pre-#336 rows.
     */
    @Column(length = 200)
    private String secret;

    /**
     * Audit R2 #336 — envelope-encrypted secret. AES-GCM via
     * {@code CryptoService}: base64(12-byte nonce || ciphertext || 128-bit
     * tag). Always populated on new saves after #336; the plaintext
     * {@link #secret} column is NULLed at the same time. Nullable during
     * the transition so pre-migration rows with only plaintext still
     * authenticate.
     */
    @Column(name = "secret_encrypted", length = 1024)
    private String secretEncrypted;

    /**
     * Audit R2 #336 — encryption key generation id. Always 1 today;
     * future rotation adds 2/3/... and a background job re-encrypts old
     * rows so the plaintext column can be dropped without a big-bang
     * migration. Null on pre-#336 rows.
     */
    @Column(name = "secret_key_id")
    private Short secretKeyId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
