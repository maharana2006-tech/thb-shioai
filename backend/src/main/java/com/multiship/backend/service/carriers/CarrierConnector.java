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
    ServiceAvailability listServices(String originCountry, String accessToken, String environment);

    /**
     * The carrier's predefined PACKAGING for the origin country — the carrier
     * fixes the dimensions, weight cap and flat-rate nature (USPS Flat Rate is
     * US-only, FedEx One Rate is US-domestic, 10/25KG boxes are international).
     * Real API when a live token is given, else the built-in catalogue.
     */
    PackageAvailability listPackages(String originCountry, String accessToken, String environment);

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

    ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment);

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
    default List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
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
    default TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        return trackShipment(trackingNumber);
    }

    /**
     * Void / cancel a previously-issued label. Sprint 30. Every carrier
     * exposes a "cancel this shipment" endpoint — UPS Void Shipment,
     * FedEx Cancel Shipment, SWSIM CancelIndicium, DHL Delete Shipment.
     * Semantics differ slightly:
     * <ul>
     *   <li>Refunds the postage IF the carrier hasn't scanned the label
     *       in transit yet. Post-scan requests still succeed but no
     *       refund happens.</li>
     *   <li>Idempotent — voiding an already-voided label returns the
     *       same success response.</li>
     * </ul>
     *
     * <p>The default returns {@code NOT_SUPPORTED} so carriers we don't
     * have void wire for yet keep compiling. {@code -local-*} tokens
     * short-circuit here too — a fallback token from an unverified
     * account can't authenticate a void call, so the connector returns
     * NOT_SUPPORTED with a message.
     */
    /**
     * @param accountNumber     Sprint 49 Tier 2 — the carrier account
     *                          number the label was created under. FedEx
     *                          cancel requires it to match the label; UPS
     *                          derives it from the tracking number.
     * @param senderCountryCode Sprint 49 Tier 2 — ISO country of the
     *                          shipper on the label. FedEx cancel needs it
     *                          to route the request; other carriers ignore.
     */
    default VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                     String accountNumber, String senderCountryCode) {
        return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                "Void isn't implemented for " + getCarrierCode() + " on this instance.", null);
    }

    /**
     * Void result — success flag + status enum + human-readable
     * message + raw carrier response for the audit trail.
     *
     * @param trackingNumber Same tracking number the caller passed in.
     * @param voided         True when the carrier confirmed the void /
     *                       cancel. False when the request was rejected
     *                       (already picked up, unknown tracking, auth
     *                       failure).
     * @param status         VOIDED | ALREADY_VOIDED | NOT_SUPPORTED | ERROR.
     * @param message        Operator-facing explanation.
     * @param rawResponse    Full carrier response for debugging; null on
     *                       NOT_SUPPORTED (no call was made).
     */
    record VoidResult(
            String trackingNumber,
            boolean voided,
            String status,
            String message,
            String rawResponse
    ) {
    }

    /**
     * Address to hand off to a carrier's validation API. Sprint 31. The
     * caller-side surface — connectors accept these fields; each carrier
     * maps them into its wire format.
     */
    record AddressToValidate(
            String name,
            String company,
            String addressLine1,
            String addressLine2,
            String addressLine3,
            String city,
            String state,
            String postalCode,
            String countryCode
    ) {
    }

    /**
     * Result of an address validation call. Sprint 31.
     *
     * @param valid          True when the carrier confirmed the address is
     *                       deliverable (EXACT match, or CORRECTED with a
     *                       suggested address).
     * @param matchLevel     EXACT | CORRECTED | AMBIGUOUS | NOT_FOUND | NOT_SUPPORTED
     *                       | ERROR. Drives the UI badge.
     * @param classification RESIDENTIAL | COMMERCIAL | UNKNOWN. Set when
     *                       the carrier reports it (UPS + FedEx do; USPS
     *                       + DHL don't).
     * @param suggested      The carrier's suggested corrected address when
     *                       {@code matchLevel=CORRECTED}. Null otherwise.
     * @param warnings       Human-readable warnings (unusual state format,
     *                       missing apt number, ...). Empty when everything's
     *                       clean.
     * @param message        Operator-facing summary — one sentence.
     * @param rawResponse    Full carrier response for debugging; null on
     *                       NOT_SUPPORTED (no call was made).
     */
    record AddressValidationResult(
            boolean valid,
            String matchLevel,
            String classification,
            AddressToValidate suggested,
            List<String> warnings,
            String message,
            String rawResponse
    ) {
    }

    /**
     * Validate an address against the carrier's own address database.
     * Sprint 31 wires each connector to its real endpoint; the default
     * returns NOT_SUPPORTED so this stays additive over past sprints.
     *
     * <p>{@code environment} is the account's UPS-style env label
     * ("SANDBOX" or anything else = PRODUCTION) — required because
     * UPS issues per-env credentials AND per-env API hosts. Sandbox
     * tokens 401 against production hosts and vice versa.
     */
    default AddressValidationResult validateAddress(AddressToValidate address, String accessToken, String environment) {
        return new AddressValidationResult(
                false, "NOT_SUPPORTED", "UNKNOWN", null, List.of(),
                "Address validation isn't implemented for " + getCarrierCode() + " on this instance.",
                null);
    }

    /**
     * Estimate landed cost — duties + taxes + fees on top of freight — for
     * a cross-border shipment. Sprint 32. Every carrier that ships
     * internationally exposes some form of duties/taxes estimator:
     * <ul>
     *   <li>UPS: Rate/Shop with {@code LandedCostRequestIndicator}
     *       returns {@code EstimatedDuties} + {@code EstimatedTaxes}.</li>
     *   <li>FedEx: Rate with {@code edtRequestType=ALL} returns
     *       {@code shipmentRateDetail.dutiesAndTaxes} per commodity.</li>
     *   <li>DHL: Rate with {@code content.exportDeclaration} returns
     *       {@code detailedPriceBreakdown} entries typed
     *       {@code TAX} + {@code DUTY}.</li>
     *   <li>USPS: domestic-only; never a landed cost path.</li>
     * </ul>
     *
     * <p>Default returns {@code NOT_SUPPORTED} — USPS + any future
     * carrier that doesn't offer an estimator inherits this cleanly.
     */
    /**
     * Schedule a courier pickup. Sprint 33. Every carrier lets a shipper
     * request that a driver come collect N labelled parcels at a given
     * address on a given date within a time window:
     * <ul>
     *   <li>UPS: {@code POST /api/shipments/{v}/pickup} with Bearer.</li>
     *   <li>FedEx: {@code POST /pickup/v1/pickups} with Bearer.</li>
     *   <li>DHL: {@code POST /pickups} with Basic Auth.</li>
     *   <li>USPS/SWSIM: {@code SchedulePickup} SOAP call.</li>
     * </ul>
     * Default returns {@code NOT_SUPPORTED} so any future carrier without a
     * live pickup API stays additive.
     */
    /**
     * Close out the day's shipments — Sprint 34. Every carrier expects
     * an end-of-day manifest listing the tracking numbers that will be
     * handed to the driver at pickup. Without it, drivers may refuse to
     * scan parcels because the labels haven't been reported to the
     * carrier's central system.
     * <ul>
     *   <li>UPS: End of Day API — {@code POST /api/shipments/v1/endofday}
     *       with the tracking numbers list.</li>
     *   <li>FedEx: {@code POST /ship/v1/shipments/endofday} — returns a
     *       manifest PDF for the driver.</li>
     *   <li>USPS/SWSIM: {@code CreateScanForm} SOAP — USPS's SCAN Form
     *       barcode acknowledges every included Priority Mail / Ground
     *       Advantage label in one scan.</li>
     *   <li>DHL: no explicit endpoint — DHL manifests are implicit via
     *       the pickup request (Sprint 33). Returns NOT_SUPPORTED.</li>
     * </ul>
     */
    /**
     * Parse a push-tracking webhook payload from the carrier. Sprint 36.
     * Rather than polling {@code /tracking/live} every 60s, carriers can
     * push status updates to our webhook URL. Each carrier has its own
     * JSON / XML shape — this method normalises to a common
     * {@link TrackingWebhookEvent} record.
     *
     * <p>Returns {@code null} when the payload doesn't parse (malformed,
     * unrecognised shape). The {@link WebhookService} then persists an
     * unverified audit row without touching the tracking cache.
     */
    default TrackingWebhookEvent parseWebhookEvent(String rawPayload, java.util.Map<String, String> headers) {
        return null;
    }

    /**
     * Verify a webhook signature. Sprint 36. Each carrier signs the
     * payload with HMAC-SHA256 (or similar) using a shared secret we
     * registered with them. The signature lives in a carrier-specific
     * header; we compute the expected signature and compare in
     * constant time.
     *
     * <p>Returns {@code false} when the signature is invalid or missing
     * — the {@link WebhookService} then returns 401 to the carrier and
     * the row is persisted with {@code verified=false} for audit.
     */
    default boolean verifyWebhookSignature(String rawPayload,
                                            java.util.Map<String, String> headers,
                                            String secret) {
        return false;
    }

    /**
     * Carrier-neutral webhook event. Sprint 36.
     *
     * @param trackingNumber  The tracking number this event references.
     * @param eventType       Carrier's status enum (DL delivered,
     *                        OF out for delivery, IN in transit, ...).
     *                        Passed through — the UI displays it.
     * @param statusCode      Numeric/short status code when the carrier
     *                        gives one. Null when they don't.
     * @param occurredAt      When the scan happened.
     * @param location        "City, ST US" or similar. Null when the
     *                        carrier didn't include one (e.g. arrivals
     *                        at unstaffed depots).
     * @param delivered       True on final delivery scans (freezes
     *                        tracking polls in the timeline modal).
     * @param description     Human-readable event description.
     */
    record TrackingWebhookEvent(
            String trackingNumber,
            String eventType,
            String statusCode,
            LocalDateTime occurredAt,
            String location,
            boolean delivered,
            String description
    ) {
    }

    default CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        return new CloseOutResult(
                getCarrierCode(),
                null,
                null,
                null,
                0,
                "NOT_SUPPORTED",
                "Close-out isn't implemented for " + getCarrierCode() + " on this instance.",
                null);
    }

    /**
     * Close-out request. Sprint 34.
     *
     * @param trackingNumbers The list of tracking numbers to include on
     *                        the manifest. Never null, never empty
     *                        (caller-side check).
     * @param closeDate       The date the manifest applies to. Defaults
     *                        to today when null.
     * @param address         The ship-from address the driver will
     *                        collect from — some carriers include this
     *                        on the manifest for the driver's route.
     */
    record CloseOutRequest(
            List<String> trackingNumbers,
            java.time.LocalDate closeDate,
            AddressToValidate address
    ) {
    }

    /**
     * Result of an end-of-day close-out.
     *
     * @param carrierCode      Carrier that produced the manifest.
     * @param manifestId       Carrier's manifest identifier (UPS BOL
     *                         number, FedEx group number, SWSIM
     *                         SubmissionID). Null on ERROR / NOT_SUPPORTED.
     * @param manifestPdfUrl   Direct URL to the manifest PDF when the
     *                         carrier gives us one. Some carriers embed
     *                         the PDF inline (see next field) instead.
     * @param manifestPdfBase64 Base64-encoded manifest PDF when the
     *                          carrier returns it inline. Null when the
     *                          carrier only gives a URL.
     * @param trackingCount    How many tracking numbers were included on
     *                         this manifest.
     * @param status           MANIFESTED | ERROR | NOT_SUPPORTED.
     * @param message          Operator-facing summary sentence.
     * @param rawResponse      Full carrier response for the audit trail.
     */
    record CloseOutResult(
            String carrierCode,
            String manifestId,
            String manifestPdfUrl,
            String manifestPdfBase64,
            int trackingCount,
            String status,
            String message,
            String rawResponse
    ) {
    }

    default PickupResult schedulePickup(PickupRequest request, String accessToken, String environment) {
        return new PickupResult(
                getCarrierCode(),
                null,
                null, null, null,
                "NOT_SUPPORTED",
                "Pickup scheduling isn't implemented for " + getCarrierCode() + " on this instance.",
                null);
    }

    /**
     * Pickup request payload. Sprint 33.
     *
     * @param pickupDate         Local date on which to schedule the pickup.
     * @param pickupWindowStart  Local start time of the window (e.g. 13:00).
     * @param pickupWindowEnd    Local end time of the window (e.g. 17:00).
     * @param address            Pickup / origin address.
     * @param contactName        Name of the person the driver will look for.
     * @param contactPhone       Phone the driver can reach at pickup time.
     * @param packageCount       Number of parcels to collect.
     * @param totalWeight        Total weight across all parcels.
     * @param weightUnit         LB | KG. Null = LB.
     * @param specialInstructions Free-form notes (e.g. "ring apartment 5B").
     */
    record PickupRequest(
            java.time.LocalDate pickupDate,
            java.time.LocalTime pickupWindowStart,
            java.time.LocalTime pickupWindowEnd,
            AddressToValidate address,
            String contactName,
            String contactPhone,
            int packageCount,
            java.math.BigDecimal totalWeight,
            String weightUnit,
            String specialInstructions
    ) {
    }

    /**
     * Result of a pickup scheduling call.
     *
     * @param carrierCode         Carrier that produced the confirmation.
     * @param confirmationNumber  UPS PRN / FedEx pickupConfirmationCode /
     *                            DHL dispatchConfirmationNumber / SWSIM
     *                            ConfirmationNumber. Null on ERROR / NOT_SUPPORTED.
     * @param scheduledDate       Echoed pickup date from the carrier.
     * @param pickupWindowStart   Echoed window start.
     * @param pickupWindowEnd     Echoed window end.
     * @param status              SCHEDULED | ERROR | NOT_SUPPORTED.
     * @param message             Operator-facing summary sentence.
     * @param rawResponse         Full carrier response for the audit trail.
     */
    record PickupResult(
            String carrierCode,
            String confirmationNumber,
            java.time.LocalDate scheduledDate,
            java.time.LocalTime pickupWindowStart,
            java.time.LocalTime pickupWindowEnd,
            String status,
            String message,
            String rawResponse
    ) {
    }

    default LandedCostResult estimateLandedCost(ShipmentRequestDTO request, String accessToken, String environment) {
        return new LandedCostResult(
                getCarrierCode(),
                "NOT_SUPPORTED",
                null, null, null, null, null,
                null,
                List.of(),
                List.of("Landed cost isn't offered by " + getCarrierCode() + " on this lane."),
                "Landed cost isn't implemented for " + getCarrierCode() + " on this instance.",
                null);
    }

    /**
     * One commodity's landed cost breakdown — freight is a shipment-level
     * charge so it lives on the parent, not the line.
     */
    record LandedCostLineItem(
            String description,
            String hsCode,
            Integer quantity,
            java.math.BigDecimal declaredValue,
            java.math.BigDecimal dutyAmount,
            java.math.BigDecimal taxAmount,
            String currency
    ) {
    }

    /**
     * Landed cost estimate returned by the carrier's duty engine.
     *
     * @param carrierCode     Carrier that produced the estimate.
     * @param source          LIVE | NOT_SUPPORTED | ERROR. Drives the UI badge.
     * @param freightAmount   Freight (shipping) charge included in the quote;
     *                        matches the same carrier's rate-shop price for
     *                        the requested service.
     * @param dutyTotal       Total duties across all commodities.
     * @param taxTotal        Total taxes (VAT, GST, sales) across all
     *                        commodities.
     * @param otherTotal      Broker fees, insurance, disbursement fees —
     *                        carrier-specific.
     * @param grandTotal      freight + duty + tax + other, in {@code currency}.
     * @param currency        ISO-4217 of every amount above. Carriers may
     *                        emit multiple currencies per response; we
     *                        normalise to the shipment's declared currency
     *                        when possible.
     * @param lineItems       Per-commodity breakdown. Empty when the carrier
     *                        returned only aggregated totals.
     * @param warnings        Human-readable notes ("VAT not calculated —
     *                        recipient will pay on delivery", "duty
     *                        de-minimis exceeded, ...").
     * @param message         Operator-facing summary sentence.
     * @param rawResponse     Full carrier response for the audit trail.
     */
    record LandedCostResult(
            String carrierCode,
            String source,
            java.math.BigDecimal freightAmount,
            java.math.BigDecimal dutyTotal,
            java.math.BigDecimal taxTotal,
            java.math.BigDecimal otherTotal,
            java.math.BigDecimal grandTotal,
            String currency,
            List<LandedCostLineItem> lineItems,
            List<String> warnings,
            String message,
            String rawResponse
    ) {
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

    /**
     * One piece inside a multi-package shipment result. Sprint 48 —
     * carriers return per-piece tracking + label artifacts (FedEx
     * {@code pieceResponses[]}, UPS {@code PackageResults[]}, DHL
     * {@code packages[]}), and Stamps splits per-package by design.
     * Persisted into {@code label_package} so downstream reads (label
     * printing, webhooks, customer support) can address individual boxes.
     */
    record PackageTracking(
            int sequenceNumber,
            String trackingNumber,
            String trackingUrl,
            String labelUrl,
            String labelPdf,
            BigDecimal netCharge
    ) {
    }

    /**
     * Top-level fields carry the "master" identity of the shipment:
     * {@code trackingNumber} is what carriers surface on the shipment
     * document (FedEx piece 1's tracking, UPS ShipmentIdentificationNumber,
     * DHL shipmentTrackingNumber, Stamps piece 1's tracking). For
     * single-package shipments {@code trackingNumber} == {@code packages[0].trackingNumber}.
     *
     * <p>{@code packages} is one entry per physical box. Empty list is
     * legal for connectors that haven't been upgraded to parse per-piece
     * — downstream code falls back to the master when packages is empty.
     */
    record ShipmentResult(
            String trackingNumber,
            String trackingUrl,
            String labelUrl,
            String labelPdf,
            BigDecimal shippingCost,
            LocalDateTime estimatedDelivery,
            String rawResponse,
            List<PackageTracking> packages
    ) {
        /** Backwards-compatible constructor for callers/tests that
         *  don't provide a per-piece list — synthesizes a 1-element
         *  list from the master when trackingNumber is present, else
         *  an empty list. */
        public ShipmentResult(String trackingNumber, String trackingUrl, String labelUrl,
                              String labelPdf, BigDecimal shippingCost,
                              LocalDateTime estimatedDelivery, String rawResponse) {
            this(trackingNumber, trackingUrl, labelUrl, labelPdf, shippingCost,
                    estimatedDelivery, rawResponse,
                    trackingNumber == null ? List.of()
                            : List.of(new PackageTracking(1, trackingNumber, trackingUrl,
                                    labelUrl, labelPdf, shippingCost)));
        }
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
