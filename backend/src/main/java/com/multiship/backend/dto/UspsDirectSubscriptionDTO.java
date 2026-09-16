package com.multiship.backend.dto;

import com.multiship.backend.model.UspsDirectSubscription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Read-shape for a persisted {@link UspsDirectSubscription}. Deliberately
 * omits the HMAC secret in every form — callers who need to re-register
 * a subscription must issue a new POST (a fresh secret is minted server-
 * side). {@link #hasSecret} exposes just "is there a secret persisted"
 * so the FE can render a badge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsDirectSubscriptionDTO {

    private Long id;

    /** USPS' own subscription id (returned by USPS on POST). */
    private String uspsSubscriptionId;

    /** MID | TRACKING_NUMBERS | STID. */
    private String filterType;

    /** Scalar (MID / STID) or CSV list (tracking numbers). */
    private String filterValue;

    /** URL USPS POSTs push events to. */
    private String listenerUrl;

    /** CSV of USPS eventTypes[] subscribed to. {@code null} = every event. */
    private String eventTypes;

    /** PRODUCTION | SANDBOX. */
    private String environment;

    /** ACTIVE | DELETED. */
    private String status;

    /** True when the row has an HMAC signing secret stored. Never exposes
     *  the plaintext or a preview — a subscription's secret is write-only. */
    private Boolean hasSecret;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    /**
     * Map an entity to its read-shape DTO. Never populates the secret;
     * only reports {@link #hasSecret}.
     */
    public static UspsDirectSubscriptionDTO from(UspsDirectSubscription row) {
        if (row == null) return null;
        return UspsDirectSubscriptionDTO.builder()
                .id(row.getId())
                .uspsSubscriptionId(row.getUspsSubscriptionId())
                .filterType(row.getFilterType())
                .filterValue(row.getFilterValue())
                .listenerUrl(row.getListenerUrl())
                .eventTypes(row.getEventTypes())
                .environment(row.getEnvironment())
                .status(row.getStatus() == null ? null : row.getStatus().name())
                .hasSecret(row.getSecretEncrypted() != null && !row.getSecretEncrypted().isBlank())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .deletedAt(row.getDeletedAt())
                .build();
    }
}
