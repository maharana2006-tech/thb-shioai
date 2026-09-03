package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Serialization-friendly wrapper around {@link
 * com.multiship.backend.service.carriers.CarrierConnector.TrackingResult}
 * for the tracking REST endpoint. Keeps the carrier-neutral shape but sheds
 * the raw provider response (which is huge and lands in a different
 * diagnostic endpoint if we ever need it).
 *
 * <p>Also carries {@code source} = LIVE | STUB | CACHE so the UI can decide
 * whether to show a "checked just now" badge (LIVE), a "no live tracking"
 * hint (STUB), or a subtle "cached" timestamp (CACHE).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TrackingResponseDTO {

    /** Carrier tracking number. */
    private String trackingNumber;

    /** Canonical carrier code (UPS | FEDEX | USPS | DHL). */
    private String carrierCode;

    /** Human-readable status ("Delivered", "In Transit", "UNKNOWN"). */
    private String status;

    /** True when the tracking API reported the shipment as delivered. */
    private Boolean delivered;

    /** Web tracking URL (always populated). */
    private String trackingUrl;

    /** "City, ST US" style location of the last scan, when the carrier reports one. */
    private String currentLocation;

    /** Carrier's estimated delivery timestamp when exposed by its API. */
    private LocalDateTime estimatedDelivery;

    /** Oldest → newest scan events. Empty when the tracking is a URL-only stub. */
    @Builder.Default
    private List<TrackingEventDTO> events = List.of();

    /**
     * LIVE | STUB | CACHE | RATE_LIMITED — see the class doc.
     * RATE_LIMITED added by audit L1: distinguishes a carrier 429 from a
     * generic outage so partners know to back off vs treat the carrier as
     * unavailable.
     */
    private String source;

    /**
     * Audit L1 — carrier-supplied Retry-After hint when {@code source =
     * RATE_LIMITED}. Null in every other source state. Partners can use
     * this to schedule the next poll; the internal FE surfaces it as a
     * countdown badge on the tracking modal.
     */
    private Integer retryAfterSeconds;

    /**
     * Audit L2 — per-batch master tracking. Parity with the external v2
     * endpoint (see PR #548). Populated for multi-package shipments,
     * empty for single-pkg / pre-Sprint-48 legacy orders.
     */
    @Builder.Default
    private List<MasterTracking> masterTrackings = List.of();

    /**
     * Audit L2 — per-piece child tracking numbers ordered by
     * sequenceNumber. Same shape as v2's ChildTracking. Empty on
     * single-pkg / legacy orders.
     */
    @Builder.Default
    private List<ChildTracking> childTrackings = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackingEventDTO {
        private LocalDateTime timestamp;
        private String status;
        private String description;
        private String location;
    }

    /** Audit L2 — one entry per shipment_batch row. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MasterTracking {
        private Integer batchSeq;
        private String carrierCode;
        private String masterTrackingNumber;
        private String masterTrackingUrl;
        private Integer packageCountInBatch;
    }

    /** Audit L2 — one entry per label_package row. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildTracking {
        private Integer sequenceNumber;
        private String trackingNumber;
        private String trackingUrl;
    }
}
