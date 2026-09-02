package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Current tracking state for a shipment. */
@Data
@Builder
public class ExternalTrackingResponse {
    private Long shipmentId;
    /**
     * Shipment-level tracking number. Historically this is the number
     * partners have polled to determine "shipment status". For MPS
     * (multi-package) shipments this equals the first batch's master
     * tracking; {@link #masterTrackings} carries the per-batch masters
     * and {@link #childTrackings} carries every per-piece tracking so
     * integrations can group carrier tracking events correctly.
     */
    private String trackingNumber;
    private String carrier;
    private String status;
    private String currentLocation;
    private LocalDateTime estimatedDelivery;
    private boolean delivered;
    private String trackingUrl;

    /**
     * PR #548 — carrier master tracking per shipment_batch row. One entry
     * for single-request MPS shipments; N entries when the shipment was
     * split across multiple carrier calls (over-cap). Empty on pre-
     * Sprint-48 orders. Partners should treat each master as the "top-
     * of-tree" number for its batch — carrier tracking events under it
     * roll up per-piece.
     */
    private List<MasterTracking> masterTrackings;

    /**
     * PR #548 — per-piece child tracking numbers, ordered by
     * sequenceNumber. Populated for multi-package shipments; empty for
     * single-pkg / legacy orders. Carriers emit tracking events at the
     * child level; the master is the roll-up.
     */
    private List<ChildTracking> childTrackings;

    /** Nested batch-master payload — one per shipment_batch row. */
    @Data
    @Builder
    public static class MasterTracking {
        private Integer batchSeq;
        private String carrierCode;
        private String masterTrackingNumber;
        private String masterTrackingUrl;
        private Integer packageCountInBatch;
    }

    /** Nested per-piece payload — one per label_package row. */
    @Data
    @Builder
    public static class ChildTracking {
        private Integer sequenceNumber;
        private String trackingNumber;
        private String trackingUrl;
    }
}
