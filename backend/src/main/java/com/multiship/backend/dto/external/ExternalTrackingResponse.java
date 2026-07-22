package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Current tracking state for a shipment. */
@Data
@Builder
public class ExternalTrackingResponse {
    private Long shipmentId;
    private String trackingNumber;
    private String carrier;
    private String status;
    private String currentLocation;
    private LocalDateTime estimatedDelivery;
    private boolean delivered;
    private String trackingUrl;
}
