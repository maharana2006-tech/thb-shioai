package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One purchase attempt of a manual shipment, keyed by the client's
 * {@code Idempotency-Key}. The same key replays the stored response; a key
 * still PURCHASING answers 409. Phase 0 of the Manual Shipment redesign.
 */
@Entity
@Table(name = "shipment_attempt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentAttempt {

    public static final String PURCHASING = "PURCHASING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String PARTIAL = "PARTIAL";
    public static final String ABANDONED = "ABANDONED";
    public static final String UNKNOWN = "UNKNOWN";

    @Id
    @Column(name = "attempt_key", nullable = false)
    private UUID attemptKey;

    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    @Column(name = "actor", length = 200)
    private String actor;

    @Column(name = "request_json", columnDefinition = "text", nullable = false)
    private String requestJson;

    @Column(name = "response_json", columnDefinition = "text")
    private String responseJson;

    @Column(name = "carrier_request_id", length = 128)
    private String carrierRequestId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
