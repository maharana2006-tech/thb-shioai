package com.multiship.backend.service.carriers;

import com.multiship.backend.dto.ShipmentRequestDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface CarrierConnector {

    String getCarrierCode();

    String getCarrierName();

    /**
     * The services this carrier offers FROM the given origin country — the
     * carrier "service availability" call (FedEx Service Availability, UPS Rate
     * Shop, USPS Shipping Options). Availability is lane-specific, so the list
     * differs per origin (and USPS, US-only, returns nothing from abroad).
     *
     * When a REAL (non-fallback) access token is supplied the connector calls
     * the live carrier API and reports live=true; otherwise — or if the carrier
     * is unreachable — it returns the built-in availability model and reports
     * live=false, so callers never mistake simulated data for a live response.
     */
    ServiceAvailability listServices(String originCountry, String accessToken);

    /**
     * The carrier's predefined PACKAGING for the origin country — the carrier
     * fixes the dimensions, weight cap and flat-rate nature (USPS Flat Rate is
     * US-only, FedEx One Rate is US-domestic, 10/25KG boxes are international).
     * Real API when a live token is given, else the built-in catalogue.
     */
    PackageAvailability listPackages(String originCountry, String accessToken);

    CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber);

    String getAccessToken(String clientId, String clientSecret);

    /**
     * Some carriers (UPS) want the shipper number sent as a token-request
     * header — {@code x-merchant-id} — so quotas and rate limits attach to the
     * merchant, not just the app credentials. Callers that know the shipper
     * account number should prefer this overload; the default just drops the
     * merchant id and delegates, so connectors that don't need it inherit the
     * existing behaviour.
     */
    default String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        return getAccessToken(clientId, clientSecret);
    }

    ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken);

    boolean validateCredentials(String clientId, String clientSecret);

    TrackingResult trackShipment(String trackingNumber);

    CarrierConfiguration getConfiguration();

    /** One available service level for a lane: the carrier's code, name, scope. */
    record ServiceOffering(String serviceCode, String name, String scope) {
    }

    /**
     * The result of a service-availability lookup.
     * @param offerings the services available for the lane
     * @param live      true only when the LIVE carrier API answered (not the built-in model)
     * @param via       human-readable source ("UPS Rating API" / "built-in availability — no live UPS credentials")
     */
    record ServiceAvailability(List<ServiceOffering> offerings, boolean live, String via) {
    }

    /**
     * One predefined carrier package with its carrier-fixed spec.
     * dims/maxWeight in inches/pounds; scope = DOMESTIC | INTERNATIONAL | BOTH.
     */
    record PackageOffering(String code, String name, java.math.BigDecimal length, java.math.BigDecimal width,
                           java.math.BigDecimal height, java.math.BigDecimal maxWeight, boolean flatRate, String scope) {
    }

    record PackageAvailability(List<PackageOffering> offerings, boolean live, String via) {
    }

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
