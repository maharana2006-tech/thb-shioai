package com.multiship.backend.service.carriers;

import com.multiship.backend.dto.ShipmentRequestDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface CarrierConnector {

    String getCarrierCode();

    String getCarrierName();

    CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber);

    String getAccessToken(String clientId, String clientSecret);

    ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken);

    boolean validateCredentials(String clientId, String clientSecret);

    TrackingResult trackShipment(String trackingNumber);

    CarrierConfiguration getConfiguration();

    record CarrierConnectionResult(
            String carrierCode,
            String carrierName,
            boolean connected,
            String accountNumber,
            String environment,
            String accessToken,
            LocalDateTime tokenExpiresAt,
            String message
    ) {
    }

    record ShipmentResult(
            String trackingNumber,
            String trackingUrl,
            String labelUrl,
            String labelPdf,
            BigDecimal shippingCost,
            LocalDateTime estimatedDelivery,
            String rawResponse
    ) {
    }

    record TrackingResult(
            String trackingNumber,
            String status,
            String trackingUrl,
            String currentLocation,
            LocalDateTime estimatedDelivery,
            boolean delivered,
            String rawResponse
    ) {
    }

    record CarrierConfiguration(
            String carrierCode,
            String carrierName,
            String baseUrl,
            String authUrl,
            String apiVersion,
            String sandboxUrl,
            String shipmentPath,
            String trackingPath,
            String tokenPath,
            String logoUrl,
            String documentationUrl,
            String connectionGuide,
            String defaultServiceType,
            String defaultPackageType,
            String labelResponseOption,
            String defaultEnvironment,
            boolean active
    ) {
    }
}
