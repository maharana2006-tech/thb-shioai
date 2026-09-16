package com.multiship.backend.model;

import com.multiship.backend.config.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * USPS_DIRECT PR-B — one row per USPS Subscriptions-Tracking v3
 * subscription this platform has registered with USPS to receive push
 * tracking events. See {@code V59__usps_direct_subscriptions.sql} and
 * {@code docs/usps-direct-integration.md} §PR-B.
 *
 * <p>The {@link #secretEncrypted} column stores the HMAC-SHA256 secret
 * USPS uses to sign push notifications, envelope-encrypted at rest via
 * {@link EncryptedStringConverter} (AES-GCM base64(nonce||cipher||tag)
 * with an {@code enc:v1:} sentinel prefix — same wire format as
 * {@link User#getCarrierClientSecret()} and
 * {@link CarrierAccountRef#getClientSecret()}).
 *
 * <p>Rows are never physically deleted: a delete flips {@link #status} to
 * {@code DELETED} and stamps {@link #deletedAt} so audits can reconstruct
 * what USPS was told even if the DELETE against USPS' side eventually
 * 404s. {@link #uspsSubscriptionId} is uniquely constrained so re-using an
 * id after a soft-delete is rejected at persist-time.
 */
@Entity
@Table(name = "usps_direct_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uk_usps_subscription_id",
                columnNames = "usps_subscription_id"),
        indexes = @Index(name = "idx_usps_subscription_filter",
                columnList = "filter_type, filter_value"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsDirectSubscription {

    /** USPS' subscription filter types (from the {@code filterProperties}
     *  block of a POST body — MID, trackingNumbers[], STID). Stored as
     *  strings so the schema stays open to new USPS filter types without
     *  a follow-up migration. */
    public enum FilterType { MID, TRACKING_NUMBERS, STID }

    /** Lifecycle: {@code ACTIVE} on create; {@code DELETED} after the
     *  DELETE-against-USPS + soft-delete completes. */
    public enum Status { ACTIVE, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Subscription id USPS returned from POST
     *  {@code /subscriptions-tracking/v3/subscriptions}. Unique platform-wide. */
    @Column(name = "usps_subscription_id", nullable = false, length = 100)
    private String uspsSubscriptionId;

    /** Which filter USPS uses to select events for this subscription. */
    @Column(name = "filter_type", nullable = false, length = 20)
    private String filterType;

    /** The value for {@link #filterType} — scalar for {@code MID}/{@code STID},
     *  CSV list for {@code TRACKING_NUMBERS}. */
    @Column(name = "filter_value", nullable = false, columnDefinition = "TEXT")
    private String filterValue;

    /** URL USPS POSTs push events to. Must be HTTPS. */
    @Column(name = "listener_url", nullable = false, length = 500)
    private String listenerUrl;

    /** Envelope-encrypted HMAC secret. Never returned to callers. */
    @Column(name = "secret_encrypted", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String secretEncrypted;

    /** CSV of USPS eventTypes[] this subscription is registered for.
     *  {@code null} = every event USPS ships for the filter. */
    @Column(name = "event_types", length = 200)
    private String eventTypes;

    /** {@code PRODUCTION} (apis.usps.com) or {@code SANDBOX} (apis-tem.usps.com). */
    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Set when {@link #status} flips to {@code DELETED}. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
