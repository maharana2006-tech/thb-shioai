package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 36 — audit row for every incoming carrier webhook. Persist
 * even bad-signature or malformed rows so we have a paper trail if a
 * carrier claims they delivered a push we never processed. Bad rows
 * carry {@code verified=false}; good rows update the corresponding
 * {@link OrderTracking} and bust the tracking cache.
 */
@Entity
@Table(name = "carrier_webhook_events")
@Data
public class CarrierWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UPS | FEDEX | USPS | DHL. */
    @Column(name = "carrier_code", nullable = false, length = 20)
    private String carrierCode;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    /** Carrier's status enum: DL | OF | IN | ... */
    @Column(name = "event_type", length = 40)
    private String eventType;

    /** Numeric / short status code when the carrier provides one. */
    @Column(name = "status_code", length = 40)
    private String statusCode;

    /** When the scan happened at the carrier. */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    /** "City, ST US" — best-effort formatted from the carrier's location fields. */
    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "description", length = 1000)
    private String description;

    /** True on final delivery scans; freezes the tracking auto-refresh loop. */
    @Column(name = "delivered")
    private Boolean delivered = false;

    /** True when HMAC signature verified against the shared secret. */
    @Column(name = "verified")
    private Boolean verified = false;

    /**
     * Sprint 49 Tier 0 — true when the receiver refused the payload
     * (blank secret + no unsigned opt-in). Rejected rows are audited
     * but do NOT mutate order state; the receiver returns 401 to the caller.
     */
    @Column(name = "rejected")
    private Boolean rejected = false;

    /**
     * Sprint 49 Tier 1 — SHA-256 of (carrier || tracking || eventType ||
     * statusCode || occurredAt) or of rawPayload when those fields are
     * missing. Used to dedup replays inside a 24h window so a carrier
     * re-delivery doesn't advance order state twice.
     */
    @Column(name = "event_hash", length = 64)
    private String eventHash;

    /**
     * Sprint 49 Tier 1 — true when this row was matched to a prior
     * verified event by {@link #eventHash} within the dedup window.
     * Dedup'd rows are audited but never mutate state.
     */
    @Column(name = "duplicate")
    private Boolean duplicate = false;

    /** When our webhook receiver got the request. */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** Full carrier payload for the audit trail. */
    @Column(name = "raw_payload", columnDefinition = "text")
    private String rawPayload;
}
