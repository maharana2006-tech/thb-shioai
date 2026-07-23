package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Result of creating a shipment via the public API. */
@Data
@Builder
public class ExternalShipmentResponse {
    private Long shipmentId;
    private String reference;
    private String clientCode;
    private String carrier;
    private String service;
    private String resolvedVia;
    private boolean international;

    private String trackingNumber;
    private String trackingUrl;
    private String labelUrl;
    private String labelPdf;
    private String labelDocumentUrl;
    private BigDecimal shippingCost;
    private LocalDateTime estimatedDelivery;
    private String status;
}
