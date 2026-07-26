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

    /**
     * Same idea as the 3-arg overload but also carries the target
     * {@code environment} (SANDBOX | PRODUCTION). UPS issues Consumer Keys
     * per-environment: a CIE (sandbox) key 401s against the production host,
     * so we route to the matching UPS OAuth endpoint. Default drops the
     * environment and delegates.
     */
    default String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        return getAccessToken(clientId, clientSecret, accountNumber);
    }

    ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken);

    /**
     * Rate shopping — ask the carrier what its services would cost for the
     * given shipment shape. Sprint 18 introduces the default (empty list)
     * so connectors that don't have a live Rate API yet keep compiling.
     * FedEx overrides in Sprint 18; UPS, DHL, USPS in follow-up sprints.
     *
     * <p>Return an EMPTY list rather than throwing when the request can't
     * be rated (unauthenticated, connectivity failure, carrier down). The
     * fan-out service treats empty as "no options from this carrier" — it
     * merges results across every configured carrier before returning.
     */
    default List<RateOption> getRates(ShipmentRequestDTO request, String accessToken) {
        return List.of();
    }

    boolean validateCredentials(String clientId, String clientSecret);

    TrackingResult trackShipment(String trackingNumber);

    /**
     * Same idea as the token-overload chain: adding an authenticated
     * variant that carriers can override to make a real API call, without
     * forcing the connectors that only speak public trackers to change.
     * Default falls back to the 1-arg (URL-only) implementation. Where
     * {@code accessToken} is a fallback ({@code -local-*}) — see the
     * carrier's own token flow for that convention — the caller should
     * expect the same URL-only result the default returns.
     */
    default TrackingResult trackShipment(String trackingNumber, String accessToken) {
        return trackShipment(trackingNumber);
    }

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

    /**
     * One priced service option returned by a carrier's Rate API.
     * Carrier-neutral so FedEx {@code rateReplyDetails}, UPS RatedShipment,
     * DHL {@code products}, and future carriers all serialize into the same
     * shape. The fan-out service (Sprint 19+) can then merge and sort across
     * carriers without knowing per-carrier response shapes.
     *
     * @param carrierCode        Canonical carrier code (UPS/FEDEX/USPS/DHL).
     * @param serviceCode        Carrier-specific service code (e.g. FEDEX_GROUND,
     *                           "03" for UPS Ground, P for DHL Express Worldwide).
     * @param serviceName        Human-readable service label.
     * @param totalAmount        Total charge in {@code currency}. Amount before
     *                           optional discounts / promos; carriers can also
     *                           return an ACCOUNT rate (post-discount) which the
     *                           connector should prefer.
     * @param currency           ISO-4217 code.
     * @param estimatedDelivery  Carrier's estimated arrival time; null when
     *                           not exposed.
     * @param transitDays        Approximate transit business days; null when
     *                           the carrier only exposes a delivery date.
     */
    record RateOption(
            String carrierCode,
            String serviceCode,
            String serviceName,
            java.math.BigDecimal totalAmount,
            String currency,
            LocalDateTime estimatedDelivery,
            Integer transitDays
    ) {
    }

    /**
     * One scan / status update along the shipment's journey. Carrier-neutral
     * so a FedEx {@code scanEvent}, UPS {@code ActivityDetails} entry, or DHL
     * {@code events[]} entry all serialize into the same shape. Nulls are
     * fine — not every carrier reports location or a coded status per event.
     */
    record TrackingEvent(
            LocalDateTime timestamp,
            String status,
            String description,
            String location
    ) {
    }

    record TrackingResult(
            String trackingNumber,
            String status,
            String trackingUrl,
            String currentLocation,
            LocalDateTime estimatedDelivery,
            boolean delivered,
            String rawResponse,
            /** Ordered oldest → newest. Empty when the connector only knows
             *  how to build a tracking URL (URL-only stub / no live API). */
            List<TrackingEvent> events
    ) {
        /** Convenience constructor for the pre-Sprint-12 callers — same
         *  seven fields, empty events list. Kept so no existing carrier
         *  connector needs to change its constructor call. */
        public TrackingResult(String trackingNumber, String status, String trackingUrl,
                              String currentLocation, LocalDateTime estimatedDelivery,
                              boolean delivered, String rawResponse) {
            this(trackingNumber, status, trackingUrl, currentLocation, estimatedDelivery,
                    delivered, rawResponse, List.of());
        }
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
