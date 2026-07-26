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

    /** LIVE | STUB | CACHE — see the class doc. */
    private String source;

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
}
