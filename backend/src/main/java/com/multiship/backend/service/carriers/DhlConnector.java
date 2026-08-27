package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DHL Express MyDHL API v2 connector. Differs from UPS/FedEx in one key way:
 * DHL doesn't do OAuth token acquisition — it wants HTTP Basic Auth
 * ({@code Authorization: Basic base64(apiKey:apiSecret)}) on EVERY call.
 * To keep the {@link CarrierConnector} interface uniform we treat
 * {@code getAccessToken} as "return the Basic Auth token string", and
 * every API method prefixes it with "Basic ".
 *
 * <p>Sandbox routing follows the same pattern as UPS/Stamps: SANDBOX
 * environments hit {@code express.api.dhl.com/mydhlapi/test}, everything
 * else hits {@code express.api.dhl.com/mydhlapi}. Credentials issued for
 * one environment don't work in the other, so route by the account's
 * environment field.
 *
 * <p>Sprint 9 scope: full shipment payload with the international customs
 * block wired in (mirrors what Sprint 3 did for FedEx). CN22/CN23 style
 * per-line commodities feed DHL's {@code exportDeclaration.lineItems}
 * array; incoterms + duty billing map onto DHL-native enums. ETD / paperless
 * flags require account-side enrolment (operations task, not code).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DhlConnector implements CarrierConnector {

    private static final String CARRIER_CODE = "DHL";

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "DHL Express";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
        List<ServiceOffering> matrix = serviceMatrix(originCountry);
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        // DHL doesn't expose a "list services" endpoint — the ProductCode
        // catalogue is a static mapping. We honour the same "verified
        // account = live" convention the other connectors use so the UI
        // treats the return the same way.
        return realToken
                ? new ServiceAvailability(matrix, true, "verified DHL account · published product catalog")
                : new ServiceAvailability(matrix, false, "not verified — no live DHL credentials");
    }

    /**
     * DHL Express product codes per lane. Products map to global letters
     * (P=Express Worldwide, T=Express Envelope, Y=Express 12:00) rather than
     * numeric codes like UPS. See DHL Express Rate Guide for the full list.
     */
    private List<ServiceOffering> serviceMatrix(String originCountry) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        return switch (o) {
            // North America: full domestic + intl portfolio.
            case "US", "PR", "CA", "MX" -> List.of(
                    new ServiceOffering("N", "DHL Domestic Express", "DOMESTIC"),
                    new ServiceOffering("P", "DHL Express Worldwide (nondoc)", "INTERNATIONAL"),
                    new ServiceOffering("D", "DHL Express Worldwide (doc)", "INTERNATIONAL"),
                    new ServiceOffering("T", "DHL Express 12:00", "INTERNATIONAL"),
                    new ServiceOffering("Y", "DHL Express 09:00", "INTERNATIONAL"),
                    new ServiceOffering("H", "DHL Economy Select", "INTERNATIONAL"));
            // Europe: intra-EU Economy Select is the ground-equivalent.
            case "DE", "GB", "FR", "NL", "IT", "ES", "PL", "BE", "AT", "SE" -> List.of(
                    new ServiceOffering("N", "DHL Domestic Express", "DOMESTIC"),
                    new ServiceOffering("H", "DHL Economy Select (intra-EU)", "INTERNATIONAL"),
                    new ServiceOffering("U", "DHL Express Worldwide EU", "INTERNATIONAL"),
                    new ServiceOffering("P", "DHL Express Worldwide", "INTERNATIONAL"),
                    new ServiceOffering("T", "DHL Express 12:00", "INTERNATIONAL"),
                    new ServiceOffering("Y", "DHL Express 09:00", "INTERNATIONAL"));
            // Rest of world (Asia-Pacific, LATAM, etc.).
            default -> List.of(
                    new ServiceOffering("P", "DHL Express Worldwide (nondoc)", "INTERNATIONAL"),
                    new ServiceOffering("D", "DHL Express Worldwide (doc)", "INTERNATIONAL"),
                    new ServiceOffering("T", "DHL Express 12:00", "INTERNATIONAL"),
                    new ServiceOffering("Y", "DHL Express 09:00", "INTERNATIONAL"));
        };
    }

    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        // DHL Express Envelope + Box lineup. Weight caps enforced by DHL —
        // exceeding = fall back to YOUR_PACKAGING (custom dimensions).
        List<PackageOffering> pkgs = List.of(
                new PackageOffering("2BP", "DHL Express Envelope", bd("32.5"), bd("22.5"), bd("2.5"), bd("1"), false, "BOTH"),
                new PackageOffering("2BX", "DHL Express Box (Small)", bd("33.7"), bd("18.2"), bd("10"), bd("15"), false, "BOTH"),
                new PackageOffering("3BX", "DHL Express Box (Medium)", bd("33.7"), bd("32"), bd("18.2"), bd("25"), false, "BOTH"),
                new PackageOffering("4BX", "DHL Express Box (Large)", bd("33.7"), bd("32.2"), bd("35"), bd("31"), false, "BOTH"));
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        return realToken
                ? new PackageAvailability(pkgs, true, "verified DHL account · published packaging")
                : new PackageAvailability(pkgs, false, "not verified — no live DHL credentials");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        validateCredentials(clientId, clientSecret);
        String token = getAccessToken(clientId, clientSecret, accountNumber);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        return new CarrierConnectionResult(CARRIER_CODE, getCarrierName(), true,
                accountNumber, carrierProperties.getDefaultEnvironment(), token, expiresAt,
                "DHL Express connection established.");
    }

    /**
     * "Get access token" for DHL means "encode Basic Auth" — DHL has no
     * OAuth token endpoint. We still call {@link #verifyCredentials} once
     * during setup to make sure the API accepts them; verify hits the
     * lightweight {@code /address-validate} endpoint (see the credential
     * check in {@link com.multiship.backend.service.AccountRefServiceImpl}
     * for the actual verification flow — this connector just returns the
     * Basic Auth string).
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret, null, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        return getAccessToken(clientId, clientSecret, accountNumber, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            return buildFallbackToken(clientId, clientSecret);
        }
        // Live verify: ping DHL's product catalogue with the Basic Auth
        // header. If DHL returns 401/403 the credentials are bad; anything
        // else (200, 400 for missing params, 429 rate-limited) means the
        // auth was accepted and we treat the credentials as real.
        String host = isSandbox(environment)
                ? carrierProperties.getDhl().getSandboxAuthUrl()
                : carrierProperties.getDhl().getAuthUrl();
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        try {
            HttpClients.newBuilder().baseUrl(host).build().get()
                    .uri("/products")
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + basic)
                    .retrieve()
                    .body(String.class);
            return basic;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                // Sprint 51 BS-L1 — scrub credentials before logging; the DHL
                // auth-reject body can echo the presented clientId verbatim.
                String safeBody = LogRedaction.redactSecrets(
                        ex.getResponseBodyAsString(), clientId, clientSecret);
                log.warn("DHL rejected credentials (HTTP {}): {}", status, safeBody);
                return buildFallbackToken(clientId, clientSecret);
            }
            // 4xx-except-auth or 5xx means the auth was accepted; take it.
            return basic;
        } catch (Exception ex) {
            log.warn("DHL credential check network failure; using local fallback token. Reason: {}", ex.getMessage());
            return buildFallbackToken(clientId, clientSecret);
        }
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        // F7 fix — recipient country is required. DHL Express is
        // international-first; a blank recipient country previously fell
        // through the buildParty helper's `firstNonBlank(country, "US")`
        // default and shipped to a US address the operator never confirmed.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "DHL Express shipment requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before generating a label.");
        }
        // FDX-I3 — boundary guard on shipper accountNumber. Pre-fix,
        // buildShipmentPayload (line ~1472) + buildRatePayload (line ~1015)
        // used firstNonBlank(request.getAccountNumber(), "") which shipped
        // an empty accounts[0].number on the wire. DHL rejects with a
        // validation error but the operator saw a cryptic 400. Mirrors
        // the FDX-2 (FedEx) and FDX-I2 (UPS) patterns; also catches the
        // pre-FDX-I1 "ACCOUNT" placeholder in case any legacy call site
        // still plants it.
        if (!StringUtils.hasText(request.getAccountNumber())
                || "ACCOUNT".equalsIgnoreCase(request.getAccountNumber().trim())) {
            throw new IllegalArgumentException(
                    "DHL Express shipment requires the shipper account number that owns the label (order "
                            + request.getReferenceNumber() + "). The upstream account resolution "
                            + "returned blank or the \"ACCOUNT\" placeholder — check that a "
                            + "CarrierAccountRef row exists for this shipper + carrier before "
                            + "generating the label.");
        }
        String host = isSandbox(environment)
                ? carrierProperties.getDhl().getSandboxUrl()
                : carrierProperties.getDhl().getApiBaseUrl();
        try {
            Map<String, Object> payload = buildShipmentPayload(request);
            String response = HttpClients.newBuilder().baseUrl(host).build().post()
                    .uri(carrierProperties.getDhl().getShipmentPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseShipmentResult(response);
        } catch (com.multiship.backend.service.carriers.exceptions.CarrierException cex) {
            throw cex;
        } catch (Exception ex) {
            // Sprint 49 Tier 2: no silent fake-label fallback. Throw typed
            // exception so downstream sees the real failure.
            log.warn("DHL createShipment failed: {}", ex.getMessage());
            throw com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper
                    .map("DHL", ex, "createShipment");
        }
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("DHL API Key and API Secret are required.");
        }
        return true;
    }

    /**
     * URL-only tracking. DHL's Track API requires Basic Auth so this 1-arg
     * variant only returns a public tracking link (like FedEx in Sprint 12
     * and UPS in Sprint 13 established). The authenticated variant below
     * does the real work.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingUrl = "https://www.dhl.com/en/express/tracking.html?AWB=" + trackingNumber;
        return new TrackingResult(trackingNumber, "UNKNOWN", trackingUrl, null, null, false, null);
    }

    /**
     * DHL MyDHL API v2 tracking — {@code GET /tracking?shipmentTrackingNumber=...}
     * with the Basic Auth token that {@code getAccessToken} returned (DHL
     * doesn't do OAuth so the accessToken IS the Base64-encoded
     * apiKey:apiSecret pair — the connector prefixes "Basic " when sending).
     *
     * <p>Response shape (only the fields we care about):
     * <pre>
     * shipments[0].{
     *   status,                        // DHL status enum: pre-transit,
     *                                  // transit, delivered, failure, unknown.
     *   estimatedTimeOfDelivery,       // ISO-8601 timestamp.
     *   events[] (newest-first)        // Reversed to oldest-first here.
     * }
     * </pre>
     *
     * <p>Events carry {@code date + time} as separate ISO-8601 fields;
     * {@code joinDhlDateTime} merges them. Location is DHL's
     * {@code serviceArea[0].description} — the airport / hub description
     * ("London Heathrow", "Louisville KY").
     *
     * <p>{@code -local-*} tokens short-circuit to the URL-only stub — same
     * convention Sprints 12 and 13 established.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingUrl = "https://www.dhl.com/en/express/tracking.html?AWB=" + trackingNumber;
        String host = isSandbox(environment)
                ? carrierProperties.getDhl().getSandboxUrl()
                : carrierProperties.getDhl().getApiBaseUrl();
        try {
            String response = HttpClients.newBuilder().baseUrl(host).build().get()
                    .uri(u -> u.path("/tracking")
                            .queryParam("shipmentTrackingNumber", trackingNumber)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .retrieve()
                    .body(String.class);

            JsonNode shipment = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .at("/shipments/0");

            String status = shipment.path("status").asText("UNKNOWN");
            boolean delivered = "delivered".equalsIgnoreCase(status);

            java.util.List<TrackingEvent> events = parseDhlEvents(shipment.at("/events"));
            String currentLocation = events.isEmpty() ? null : events.get(events.size() - 1).location();
            LocalDateTime estimatedDelivery = parseIsoDateTime(shipment.path("estimatedTimeOfDelivery").asText(null));

            // Normalize DHL's lowercase status enum to Title Case for UI display
            // so the timeline reads "Delivered" not "delivered" — matches the
            // FedEx / UPS convention from Sprints 12/13.
            String displayStatus = status == null || status.isEmpty()
                    ? "UNKNOWN"
                    : Character.toUpperCase(status.charAt(0)) + status.substring(1).toLowerCase();

            return new TrackingResult(trackingNumber, displayStatus, trackingUrl, currentLocation,
                    estimatedDelivery, delivered, response, events);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL track rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return trackShipment(trackingNumber);
        } catch (Exception ex) {
            log.warn("DHL track failed for {}; falling back to URL-only. Reason: {}",
                    trackingNumber, ex.getMessage());
            return trackShipment(trackingNumber);
        }
    }

    /**
     * Parse DHL {@code events[]} → TrackingEvent list, ordered oldest-first.
     * DHL returns newest-first; we reverse to match the Sprint 12/13
     * convention. Empty when the field is absent or an empty array.
     */
    java.util.List<TrackingEvent> parseDhlEvents(JsonNode events) {
        if (events == null || !events.isArray() || events.isEmpty()) return java.util.List.of();
        java.util.List<TrackingEvent> out = new java.util.ArrayList<>();
        for (JsonNode ev : events) {
            LocalDateTime ts = joinDhlDateTime(
                    ev.path("date").asText(null),
                    ev.path("time").asText(null));
            String description = ev.path("description").asText("");
            String status = ev.path("typeCode").asText(null);
            String location = ev.at("/serviceArea/0/description").asText(null);
            if (location == null || location.isEmpty()) {
                location = ev.at("/serviceArea/0/code").asText(null);
            }
            out.add(new TrackingEvent(ts, status, description, location));
        }
        java.util.Collections.reverse(out);
        return java.util.List.copyOf(out);
    }

    /**
     * DHL splits event timestamps into ISO date ({@code 2024-01-15}) + time
     * ({@code 14:30:00}). Merges them into a LocalDateTime; missing time
     * defaults to midnight (same lenient handling UPS's helper uses).
     */
    static LocalDateTime joinDhlDateTime(String isoDate, String isoTime) {
        if (isoDate == null || isoDate.isEmpty()) return null;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(isoDate);
            if (isoTime != null && !isoTime.isEmpty()) {
                try {
                    return d.atTime(java.time.LocalTime.parse(isoTime));
                } catch (Exception ex) {
                    // Malformed time — fall through to midnight.
                    log.debug("DHL joinDhlDateTime: unparseable time '{}' (falling back to midnight)", isoTime);
                }
            }
            return d.atStartOfDay();
        } catch (Exception ex) {
            log.debug("DHL joinDhlDateTime: unparseable date '{}'", isoDate);
            return null;
        }
    }

    /** Parse a raw ISO-8601 timestamp; returns null on any failure. */
    static LocalDateTime parseIsoDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            // First try full LocalDateTime.
            return LocalDateTime.parse(value);
        } catch (Exception first) {
            try {
                // DHL sometimes includes an offset (e.g. "2024-01-15T14:00:00Z").
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (Exception ex) {
                log.debug("DHL parseIsoDateTime: unparseable timestamp '{}' (neither LocalDateTime nor OffsetDateTime matched)", value);
                return null;
            }
        }
    }

    /**
     * DHL Delete Shipment — {@code DELETE /shipments/{shipmentTrackingNumber}}
     * with Basic Auth. DHL only allows deletion of shipments that
     * haven't been picked up yet — once the courier collects the parcel
     * the DELETE returns a 400 with a clear reason. HTTP 204 No Content
     * on success.
     *
     * <p>{@code -local-*} tokens short-circuit to {@code NOT_SUPPORTED}.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                    String accountNumber, String senderCountryCode) {
        // DHL cancel doesn't need accountNumber/senderCountry — kept for signature parity.
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                    "DHL void needs live credentials; the account is on a fallback token.",
                    null);
        }
        String host = isSandbox(environment)
                ? carrierProperties.getDhl().getSandboxUrl()
                : carrierProperties.getDhl().getApiBaseUrl();
        try {
            org.springframework.http.ResponseEntity<String> response = HttpClients.newBuilder()
                    .baseUrl(host).build()
                    .delete()
                    .uri("/shipments/" + trackingNumber)
                    .header("Authorization", "Basic " + accessToken)
                    .header("Message-Reference", java.util.UUID.randomUUID().toString())
                    .retrieve()
                    .toEntity(String.class);

            boolean voided = response.getStatusCode().is2xxSuccessful();
            String status = voided ? "VOIDED" : "ERROR";
            String message = voided
                    ? "DHL confirmed shipment deletion."
                    : "DHL delete rejected: HTTP " + response.getStatusCode().value();
            return new VoidResult(trackingNumber, voided, status, message,
                    response.getBody());
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL delete rejected for {} (HTTP {}): {}",
                    trackingNumber, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "DHL delete rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("DHL delete call failed for {}: {}", trackingNumber, ex.getMessage());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "DHL delete call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * DHL Express Address Validation —
     * {@code GET /address-validate?type=delivery&countryCode=...&postalCode=...&cityName=...}
     * with Basic Auth. DHL's endpoint is postal-level (matches the postal
     * code + city + country combo) rather than street-level; a match means
     * the destination is a valid delivery location, though DHL can't
     * confirm the specific building.
     *
     * <p>Response:
     * <pre>
     * address[] with:
     *   countryCode, postalCode, cityName, cityType (COUNTY | CITY | ...)
     *   serviceArea.code
     * warnings[]
     * </pre>
     * A non-empty {@code address[]} array = valid combination; empty +
     * warnings = NOT_FOUND.
     *
     * <p>DHL doesn't return residential/commercial classification (it
     * would need street-level data). We leave that field as UNKNOWN.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public AddressValidationResult validateAddress(AddressToValidate address, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new AddressValidationResult(false, "NOT_SUPPORTED", "UNKNOWN", null,
                    List.of(),
                    "DHL address validation needs live credentials; the account is on a fallback token.",
                    null);
        }
        try {
            org.springframework.web.util.UriComponentsBuilder uri = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/address-validate")
                    .queryParam("type", "delivery");
            if (StringUtils.hasText(address.countryCode())) uri.queryParam("countryCode", address.countryCode());
            if (StringUtils.hasText(address.postalCode())) uri.queryParam("postalCode", address.postalCode());
            if (StringUtils.hasText(address.city())) uri.queryParam("cityName", address.city());

            String response = HttpClients.newBuilder()
                    .baseUrl(carrierProperties.getDhl().getApiBaseUrl()).build()
                    .get()
                    .uri(uri.build().toUriString())
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .header("Message-Reference", java.util.UUID.randomUUID().toString())
                    .retrieve()
                    .body(String.class);
            return parseDhlAvResponse(address, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL address-validate rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            // DHL returns 404 when nothing matches — treat as NOT_FOUND, not an error.
            if (ex.getStatusCode().value() == 404) {
                return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                        List.of(),
                        "DHL couldn't find this address in its delivery network.",
                        ex.getResponseBodyAsString());
            }
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    List.of(),
                    "DHL address-validate rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("DHL address-validate call failed: {}", ex.getMessage());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    List.of(),
                    "DHL address-validate call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a DHL address-validate response. Package-visible for tests.
     * A non-empty {@code address[]} = valid combo; DHL doesn't return
     * "corrected" — the caller's exact input either matches a delivery
     * zone or doesn't.
     */
    AddressValidationResult parseDhlAvResponse(AddressToValidate input, String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode addresses = root.path("address");
            if (!addresses.isArray() || addresses.isEmpty()) {
                return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                        List.of(),
                        "DHL couldn't find this address in its delivery network.", response);
            }

            java.util.List<String> warnings = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode warningsNode = root.path("warnings");
            if (warningsNode.isArray()) warningsNode.forEach(n -> warnings.add(n.asText()));

            // DHL echoes the normalised address back — if the city differs
            // from the input, treat as CORRECTED.
            com.fasterxml.jackson.databind.JsonNode first = addresses.get(0);
            String matchedCity = first.path("cityName").asText("");
            boolean cityDiffers = StringUtils.hasText(input.city())
                    && !input.city().equalsIgnoreCase(matchedCity);
            if (cityDiffers) {
                AddressToValidate suggested = new AddressToValidate(
                        null, null, input.addressLine1(), input.addressLine2(), input.addressLine3(),
                        matchedCity, input.state(), first.path("postalCode").asText(input.postalCode()),
                        first.path("countryCode").asText(input.countryCode()));
                return new AddressValidationResult(true, "CORRECTED", "UNKNOWN", suggested,
                        warnings, "DHL normalised the city to '" + matchedCity + "'.", response);
            }
            return new AddressValidationResult(true, "EXACT", "UNKNOWN", null, warnings,
                    "DHL confirmed this is a valid delivery combination.", response);
        } catch (Exception ex) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    List.of(),
                    "DHL address-validate response parse failed: " + ex.getMessage(),
                    response);
        }
    }

    /**
     * DHL Duties + Taxes — POST /rates with
     * {@code getAllValueAddedServices=true} and a
     * {@code content.exportDeclaration} on the payload. DHL then returns
     * a {@code products[0].detailedPriceBreakdown} listing per-price
     * breakdowns keyed by {@code typeCode}: {@code SPRQT} freight,
     * {@code DUTY}, {@code TAX}, and various fees.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public LandedCostResult estimateLandedCost(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new LandedCostResult("DHL", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    "DHL landed cost needs live credentials; the account is on a fallback token.",
                    null);
        }
        if (!isInternational(request)) {
            return new LandedCostResult("DHL", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    List.of(),
                    List.of("DHL landed cost is only supported for international shipments."),
                    "Not an international lane; DHL landed cost skipped.", null);
        }
        try {
            Map<String, Object> payload = buildRatePayload(request);
            payload = new LinkedHashMap<>(payload);
            payload.put("getAllValueAddedServices", true);
            payload.put("returnStandardProductsOnly", false);
            // DHL uses the content block to price landed cost; we re-attach
            // an exportDeclaration derived from the intl block when present.
            if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> content = payload.get("customerDetails") != null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>();
                content.put("isCustomsDeclarable", true);
                content.put("declaredValue", request.getDeclaredValue());
                content.put("declaredValueCurrency", firstNonBlank(
                        request.getDeclaredValueCurrency(),
                        request.getIntl().getCustomsCurrency()).toUpperCase());
                content.put("exportDeclaration", buildExportDeclaration(request));
                payload.put("content", content);
            }

            String host = isSandbox(environment)
                    ? carrierProperties.getDhl().getSandboxUrl()
                    : carrierProperties.getDhl().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(host).build()
                    .post()
                    .uri("/rates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseDhlLandedCostResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL landed cost rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new LandedCostResult("DHL", "ERROR",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    "DHL landed cost rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("DHL landed cost failed: {}", ex.getMessage());
            return new LandedCostResult("DHL", "ERROR",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    "DHL landed cost call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a DHL /rates response with detailed price breakdown. Reads
     * {@code products[0].detailedPriceBreakdown[0].breakdown[]} entries
     * and sums by {@code typeCode}:
     * <ul>
     *   <li>{@code SPRQT} (or price without a type) → freight</li>
     *   <li>{@code DTP} / {@code DUTY} → duty</li>
     *   <li>{@code TAX} / {@code VAT} → tax</li>
     * </ul>
     * Package-visible for tests.
     */
    LandedCostResult parseDhlLandedCostResponse(String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode product = root.at("/products/0");
            if (product.isMissingNode()) {
                return new LandedCostResult("DHL", "ERROR",
                        null, null, null, null, null, null,
                        List.of(), List.of(),
                        "DHL returned no products.", response);
            }

            // DHL-3 — pre-fix silently defaulted to "USD" when the response
            // omitted priceCurrency. Real DHL responses always include
            // priceCurrency (mandatory in the /rates schema), so surfacing
            // a genuine null tells downstream "no quote" instead of
            // mislabeling a GBP/EUR rate as USD by the FX difference.
            // Same UPS-14/15 fix on UpsConnector.
            String currency = product.at("/totalPrice/0/priceCurrency").asText(null);

            java.math.BigDecimal freight = null;
            java.math.BigDecimal duty = java.math.BigDecimal.ZERO;
            java.math.BigDecimal tax = java.math.BigDecimal.ZERO;
            java.math.BigDecimal other = java.math.BigDecimal.ZERO;

            // Freight from totalPrice[] (Sprint 19 pattern — prefer BILLC).
            for (com.fasterxml.jackson.databind.JsonNode price : product.path("totalPrice")) {
                if ("BILLC".equalsIgnoreCase(price.path("typeCode").asText(""))) {
                    freight = readDhlDecimal(price.path("price"));
                    break;
                }
            }
            if (freight == null && product.path("totalPrice").isArray()
                    && product.path("totalPrice").size() > 0) {
                freight = readDhlDecimal(product.at("/totalPrice/0/price"));
            }

            // Duty + tax from detailedPriceBreakdown[].breakdown[].
            for (com.fasterxml.jackson.databind.JsonNode dpb : product.path("detailedPriceBreakdown")) {
                for (com.fasterxml.jackson.databind.JsonNode line : dpb.path("breakdown")) {
                    String type = line.path("typeCode").asText("").toUpperCase();
                    java.math.BigDecimal price = readDhlDecimal(line.path("price"));
                    if (price == null) continue;
                    if (type.contains("DUTY") || "DTP".equals(type)) {
                        duty = duty.add(price);
                    } else if (type.contains("TAX") || type.contains("VAT")) {
                        tax = tax.add(price);
                    } else if (!type.isEmpty() && !"SPRQT".equals(type)) {
                        other = other.add(price);
                    }
                }
            }

            if (duty.signum() == 0) duty = null;
            if (tax.signum() == 0) tax = null;
            if (other.signum() == 0) other = null;

            java.math.BigDecimal grand = java.math.BigDecimal.ZERO;
            if (freight != null) grand = grand.add(freight);
            if (duty != null) grand = grand.add(duty);
            if (tax != null) grand = grand.add(tax);
            if (other != null) grand = grand.add(other);
            if (grand.signum() == 0) grand = null;

            return new LandedCostResult("DHL", "LIVE",
                    freight, duty, tax, other, grand, currency,
                    List.of(), List.of(),
                    "DHL returned a landed cost estimate.", response);
        } catch (Exception ex) {
            return new LandedCostResult("DHL", "ERROR",
                    null, null, null, null, null, null,
                    List.of(), List.of(),
                    "DHL landed cost parse failed: " + ex.getMessage(), response);
        }
    }

    private static java.math.BigDecimal readDhlDecimal(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            try { return new java.math.BigDecimal(node.asText()); }
            catch (NumberFormatException ex) {
                log.debug("DHL readDhlDecimal: non-numeric text '{}'", node.asText());
            }
        }
        return null;
    }

    private static boolean isInternational(ShipmentRequestDTO r) {
        String from = r.getShipperCountryCode();
        String to = r.getRecipientCountryCode();
        return StringUtils.hasText(from) && StringUtils.hasText(to)
                && !from.trim().equalsIgnoreCase(to.trim());
    }

    /**
     * DHL Express Pickup Request — {@code POST /pickups} with Basic Auth.
     * Body carries {@code plannedPickupDateAndTime} + accounts +
     * customerDetails + shipmentDetails (piece count, weight). Response
     * includes {@code dispatchConfirmationNumbers[]}.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public PickupResult schedulePickup(PickupRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("DHL", null, null, null, null, "NOT_SUPPORTED",
                    "DHL pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        // FDX-C2 — pre-fix, buildDhlPickupRequest hardcoded the shipper
        // account "number" to empty string. DHL rejected that with a
        // validation error every time. Now guarded at the entry point +
        // real account plumbed by FDX-C onto PickupRequest.accountNumber().
        if (!StringUtils.hasText(request.accountNumber())) {
            return new PickupResult("DHL", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "DHL pickup needs the shipper account number that owns the labels; none was passed.",
                    null);
        }
        try {
            Map<String, Object> body = buildDhlPickupRequest(request);
            String host = isSandbox(environment)
                    ? carrierProperties.getDhl().getSandboxUrl()
                    : carrierProperties.getDhl().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(host).build()
                    .post()
                    .uri("/pickups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .header("Message-Reference", java.util.UUID.randomUUID().toString())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseDhlPickupResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL pickup rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new PickupResult("DHL", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "DHL pickup rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("DHL pickup call failed: {}", ex.getMessage());
            return new PickupResult("DHL", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "DHL pickup call failed: " + ex.getMessage(), null);
        }
    }

    /** Build the DHL pickup request body. */
    Map<String, Object> buildDhlPickupRequest(PickupRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plannedPickupDateAndTime", formatDhlPickupTimestamp(req));
        payload.put("closeTime", req.pickupWindowEnd() == null
                ? "17:00" : req.pickupWindowEnd().toString());
        // FDX-C2 — real shipper account (pre-fix, hardcoded empty string).
        // schedulePickup already short-circuits when blank; firstNonBlank
        // defence-in-depth for direct callers in tests.
        payload.put("accounts", List.of(Map.of(
                "typeCode", "shipper",
                "number", firstNonBlank(req.accountNumber(), ""))));

        AddressToValidate a = req.address();
        Map<String, Object> shipper = new LinkedHashMap<>();
        if (a != null) {
            Map<String, Object> postal = new LinkedHashMap<>();
            postal.put("cityName", firstNonBlank(a.city(), ""));
            postal.put("countryCode", firstNonBlank(a.countryCode(), "US"));
            postal.put("postalCode", firstNonBlank(a.postalCode(), ""));
            if (StringUtils.hasText(a.addressLine1())) postal.put("addressLine1", a.addressLine1());
            if (StringUtils.hasText(a.addressLine2())) postal.put("addressLine2", a.addressLine2());
            if (StringUtils.hasText(a.state())) postal.put("provinceCode", a.state());
            shipper.put("postalAddress", postal);
        }
        shipper.put("contactInformation", Map.of(
                "phone", firstNonBlank(req.contactPhone(), ""),
                "companyName", firstNonBlank(req.contactName(), ""),
                "fullName", firstNonBlank(req.contactName(), "")));
        payload.put("customerDetails", Map.of("shipperDetails", shipper));

        payload.put("shipmentDetails", List.of(Map.of(
                "productCode", "P",
                "packages", generateDhlPickupPackages(req))));

        if (StringUtils.hasText(req.specialInstructions())) {
            payload.put("specialInstructions", List.of(Map.of(
                    "value", req.specialInstructions(),
                    "typeCode", "TXT")));
        }
        return payload;
    }

    private static List<Map<String, Object>> generateDhlPickupPackages(PickupRequest req) {
        int count = Math.max(1, req.packageCount());
        java.math.BigDecimal perPackage = req.totalWeight() != null && count > 0
                ? req.totalWeight().divide(java.math.BigDecimal.valueOf(count),
                        2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ONE;
        List<Map<String, Object>> pkgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pkgs.add(Map.of(
                    "weight", perPackage,
                    "dimensions", Map.of("length", 30, "width", 20, "height", 10)));
        }
        return pkgs;
    }

    /**
     * FDX-E — see the FedEx-side counterpart's javadoc for full context.
     * {@code LocalDate.now()} → JVM default zone (server-dependent); switch
     * to {@code LabelDates.today(null)} → deterministic UTC so the
     * plannedPickupDateAndTime doesn't shift when the same code runs on
     * boxes in different timezones.
     */
    private static String formatDhlPickupTimestamp(PickupRequest req) {
        java.time.LocalDate date = req.pickupDate() != null
                ? req.pickupDate()
                : com.multiship.backend.util.LabelDates.today(null);
        java.time.LocalTime time = req.pickupWindowStart() != null
                ? req.pickupWindowStart() : java.time.LocalTime.of(13, 0);
        return date + "T" + time + " GMT+00:00";
    }

    /**
     * Parse a DHL pickup response. Success = a non-empty
     * {@code dispatchConfirmationNumbers[]}. Package-visible for tests.
     */
    PickupResult parseDhlPickupResponse(PickupRequest req, String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode ids = root.path("dispatchConfirmationNumbers");
            String number = null;
            if (ids.isArray() && ids.size() > 0) {
                number = ids.get(0).asText();
            }
            String status = StringUtils.hasText(number) ? "SCHEDULED" : "ERROR";
            String message = StringUtils.hasText(number)
                    ? "DHL confirmed pickup — " + number
                    : "DHL pickup response missing dispatchConfirmationNumbers.";
            return new PickupResult("DHL", number,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    status, message, response);
        } catch (Exception ex) {
            return new PickupResult("DHL", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "DHL pickup parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * DHL Push Notification — HMAC-SHA256(body, secret) in the
     * {@code X-DHL-Signature} header.
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload,
                                           java.util.Map<String, String> headers,
                                           String secret) {
        String provided = pickWebhookHeader(headers, "X-DHL-Signature");
        String expected = WebhookHmacUtil.hmacSha256Hex(rawPayload, secret);
        return provided != null && expected != null
                && WebhookHmacUtil.constantTimeEquals(provided, expected);
    }

    /**
     * Parse a DHL push-tracking webhook. DHL pushes:
     * <pre>
     * {
     *   "awb": "JD...",
     *   "event": "Delivered",
     *   "eventCode": "OK",
     *   "eventTimestamp": "2026-07-26T14:30:00Z",
     *   "location": {"cityName": "London", "countryCode": "GB"}
     * }
     * </pre>
     */
    @Override
    public TrackingWebhookEvent parseWebhookEvent(String rawPayload,
                                                   java.util.Map<String, String> headers) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(rawPayload).orElse("{}"));
            String tracking = root.path("awb").asText(null);
            if (!StringUtils.hasText(tracking)) return null;
            String eventCode = root.path("eventCode").asText(null);
            String description = root.path("event").asText("");
            LocalDateTime occurred = null;
            String rawTs = root.path("eventTimestamp").asText(null);
            if (StringUtils.hasText(rawTs)) {
                try { occurred = LocalDateTime.parse(rawTs.replace("Z", "")); }
                catch (Exception ex) {
                    log.debug("DHL trackShipment: unparseable eventTimestamp '{}'", rawTs);
                }
            }
            com.fasterxml.jackson.databind.JsonNode loc = root.path("location");
            String city = loc.path("cityName").asText("");
            String country = loc.path("countryCode").asText("");
            String location = null;
            if (!city.isEmpty() || !country.isEmpty()) {
                location = (city + (city.isEmpty() ? "" : " ") + country).trim();
            }
            boolean delivered = "OK".equalsIgnoreCase(eventCode)
                    || "DELIVERED".equalsIgnoreCase(description);
            return new TrackingWebhookEvent(tracking, eventCode, eventCode,
                    occurred, location, delivered, description);
        } catch (Exception ex) {
            log.warn("DHL webhook parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String pickWebhookHeader(java.util.Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (var e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    @Override
    public CarrierConfiguration getConfiguration() {
        CarrierProperties.Dhl dhl = carrierProperties.getDhl();
        return new CarrierConfiguration(CARRIER_CODE, getCarrierName(),
                dhl.getApiBaseUrl(), dhl.getAuthUrl(), dhl.getApiVersion(),
                dhl.getSandboxUrl(), dhl.getShipmentPath(), dhl.getTrackingPath(),
                dhl.getTokenPath(), dhl.getLogoUrl(), dhl.getDocumentationUrl(),
                dhl.getConnectionGuide(), dhl.getDefaultServiceType(),
                dhl.getDefaultPackageType(), dhl.getLabelResponseOption(),
                carrierProperties.getDefaultEnvironment(), true);
    }

    /**
     * DHL Express Ship API v2 shipment payload. Follows the "content-first"
     * shape where package + customs live inside {@code content}, while
     * {@code customerDetails} holds shipper/receiver and {@code accounts}
     * holds the billing party.
     */
    Map<String, Object> buildShipmentPayload(ShipmentRequestDTO request) {
        boolean isReturn = Boolean.TRUE.equals(request.getIsReturn());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productCode", firstNonBlank(request.getServiceType(), "P"));
        payload.put("plannedShippingDateAndTime", plannedShipDate(request.getShipperTimezone()));
        // Sprint 25 — Print Return Label. DHL Express treats return labels
        // as normal shipments with the customer as receiver and the return
        // depot as shipper (billing is on the shipper's account); we flip
        // pickup.isRequested=true so DHL Express Global Return schedules
        // collection from the customer.
        payload.put("pickup", Map.of("isRequested", isReturn));
        payload.put("outputImageProperties", Map.of(
                "encodingFormat", "pdf",
                "imageOptions", List.of(Map.of(
                        "typeCode", "label",
                        "templateName", "ECOM26_84_A4_001"))));
        payload.put("accounts", List.of(Map.of(
                "typeCode", "shipper",
                "number", firstNonBlank(request.getAccountNumber(), ""))));

        payload.put("customerDetails", Map.of(
                "shipperDetails", buildParty(
                        request.getShipperName(), request.getShipperPhone(),
                        request.getShipperAddressLine1(), request.getShipperAddressLine2(), null,
                        request.getShipperCity(), request.getShipperState(),
                        request.getShipperPostalCode(), request.getShipperCountryCode(), null),
                "receiverDetails", buildParty(
                        request.getRecipientName(),
                        com.multiship.backend.service.carriers.UpsConnector.joinPhone(
                                request.getRecipientPhoneCountryCode(), request.getRecipientPhone()),
                        request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                        request.getRecipientAddressLine3(),
                        request.getRecipientCity(), request.getRecipientState(),
                        request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                        request.getRecipientResidential())));

        payload.put("content", buildContent(request));
        return payload;
    }

    /** DHL Party — postalAddress + contactInformation grouped.
     *  Line 3 is optional and comes through as {@code addressLine3} in the
     *  postal address block; DHL supports up to three street lines. */
    private Map<String, Object> buildParty(String name, String phone,
                                            String line1, String line2, String line3,
                                            String city, String state,
                                            String postal, String country,
                                            Boolean residential) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("cityName", firstNonBlank(city, ""));
        address.put("countryCode", firstNonBlank(country, "US"));
        address.put("postalCode", firstNonBlank(postal, ""));
        List<String> streetLines = new ArrayList<>();
        if (StringUtils.hasText(line1)) streetLines.add(line1);
        if (StringUtils.hasText(line2)) streetLines.add(line2);
        if (StringUtils.hasText(line3)) streetLines.add(line3);
        address.put("addressLine1", streetLines.isEmpty() ? "" : streetLines.get(0));
        if (streetLines.size() > 1) address.put("addressLine2", streetLines.get(1));
        if (streetLines.size() > 2) address.put("addressLine3", streetLines.get(2));
        if (StringUtils.hasText(state)) address.put("provinceCode", state);
        if (Boolean.TRUE.equals(residential)) address.put("addressType", "residential");

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("phone", firstNonBlank(phone, ""));
        contact.put("companyName", firstNonBlank(name, ""));
        contact.put("fullName", firstNonBlank(name, ""));

        Map<String, Object> party = new LinkedHashMap<>();
        party.put("postalAddress", address);
        party.put("contactInformation", contact);
        return party;
    }

    /**
     * DHL {@code content} block. Domestic shipments skip
     * {@code isCustomsDeclarable} + {@code exportDeclaration}; international
     * gets the full customs invoice.
     */
    private Map<String, Object> buildContent(ShipmentRequestDTO request) {
        Map<String, Object> content = new LinkedHashMap<>();
        boolean isIntl = request.getIntl() != null && request.getIntl().isReadyForCarrier();
        content.put("isCustomsDeclarable", isIntl);
        // Sprint 48 B11 — DHL has a SINGLE shipment-level declaredValue
        // field (no per-package option); derive it as sum of CI items
        // (grouped totals still sum to the same shipment amount). Legacy
        // shipment-level request.declaredValue is the fallback when there
        // are no items.
        String dvCurrency = firstNonBlank(
                request.getDeclaredValueCurrency(),
                isIntl ? request.getIntl().getCustomsCurrency() : "USD").toUpperCase();
        com.multiship.backend.util.DeclaredValueContextBuilder.DeclaredValueContext dvCtx =
                com.multiship.backend.util.DeclaredValueContextBuilder.build(
                        isIntl ? request.getIntl().getCommodities() : null,
                        request.effectivePackages().size(),
                        request.effectivePackages(),
                        dvCurrency,
                        request.getDeclaredValue());
        if (dvCtx.shipmentTotal() != null && dvCtx.shipmentTotal().signum() > 0) {
            content.put("declaredValue", dvCtx.shipmentTotal());
            content.put("declaredValueCurrency", dvCurrency);
        }
        content.put("packages", buildPackages(request));
        content.put("unitOfMeasurement", "KG".equalsIgnoreCase(request.getWeightUnit()) ? "metric" : "imperial");
        content.put("description", firstNonBlank(request.getSpecialInstructions(), "General merchandise"));
        content.put("incoterm", firstNonBlank(
                isIntl ? request.getIntl().getIncoterms() : null, "DAP").toUpperCase());
        if (isIntl) {
            content.put("exportDeclaration", buildExportDeclaration(request));
        }
        // Sprint 27 — DHL dangerousGoods[] array. DHL Express treats DG
        // as a content-level attribute (per-shipment, not per-package),
        // so it hangs off content{} alongside packages[] and exportDeclaration.
        if (request.getDangerousGoods() != null
                && request.getDangerousGoods().isReadyForCarrier()) {
            content.put("dangerousGoods", buildDhlDangerousGoods(request));
        }

        // Sprint 35 — signature + insurance via valueAddedServices[].
        // DHL codes: SF=Signature On Delivery, SI=Signature Adult,
        // II=Shipment Insurance (with monetary value + currency).
        List<Map<String, Object>> vas = buildDhlValueAddedServices(request);
        if (!vas.isEmpty()) {
            content.put("valueAddedServices", vas);
        }
        return content;
    }

    /**
     * Sprint 35 — DHL valueAddedServices[] block. One entry per requested
     * add-on:
     * <ul>
     *   <li>{@code SF} — Signature On Delivery (indirect / direct).</li>
     *   <li>{@code SI} — Signature Adult (18+ ID).</li>
     *   <li>{@code II} — Shipment Insurance with {@code value} +
     *       {@code currency}.</li>
     * </ul>
     */
    List<Map<String, Object>> buildDhlValueAddedServices(ShipmentRequestDTO request) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sig = normaliseSignatureOption(request.getSignatureOption());
        if (sig != null) {
            String code = "ADULT".equals(sig) ? "SI" : "SF";
            out.add(Map.of("serviceCode", code));
        }
        if (request.getInsuredValue() != null && request.getInsuredValue().signum() > 0) {
            String currency = firstNonBlank(
                    firstNonBlank(request.getInsuredValueCurrency(), request.getDeclaredValueCurrency()),
                    "USD").toUpperCase();
            out.add(Map.of(
                    "serviceCode", "II",
                    "value", request.getInsuredValue(),
                    "currency", currency));
        }
        return out;
    }

    /** Normalise signatureOption enum to INDIRECT / DIRECT / ADULT; blank
     *  / unknown / NONE → null (carrier default). */
    private static String normaliseSignatureOption(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase();
        if (v.isEmpty() || "NONE".equals(v)) return null;
        return switch (v) {
            case "INDIRECT", "DIRECT", "ADULT" -> v;
            default -> null;
        };
    }

    /**
     * DHL MyDHL API v2 {@code dangerousGoods[]} array. One entry per
     * commodity. Field mapping:
     * <ul>
     *   <li>{@code contentId} — sequence for cross-referencing.</li>
     *   <li>{@code unCode} — UN number WITHOUT the {@code UN} prefix
     *       (DHL's schema wants "3480" not "UN3480").</li>
     *   <li>{@code properShippingName, hazardClass, packagingGroup} —
     *       direct passthrough (packagingGroup is spelled with an 'a',
     *       not FedEx's 'i' — 'packingGroup').</li>
     *   <li>{@code netWeight.value / .unit} — mass in KG only; DHL
     *       doesn't accept LB or L on the DG block, so we hard-map non-KG
     *       units to KG (weight equivalent for volume) with the caller's
     *       amount unchanged. Downstream FX conversion is out of scope.</li>
     * </ul>
     */
    List<Map<String, Object>> buildDhlDangerousGoods(ShipmentRequestDTO request) {
        com.multiship.backend.dto.DangerousGoodsBlockDTO dg = request.getDangerousGoods();
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 1;
        for (com.multiship.backend.dto.DangerousCommodityDTO c : dg.getCommodities()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contentId", String.valueOf(seq++));
            if (StringUtils.hasText(c.getUnNumber())) {
                // Strip UN prefix — DHL wants digits only.
                entry.put("unCode",
                        c.getUnNumber().trim().toUpperCase().replaceFirst("^UN", ""));
            }
            if (StringUtils.hasText(c.getProperShippingName())) {
                entry.put("properShippingName", c.getProperShippingName().trim());
            }
            if (StringUtils.hasText(c.getHazardClass())) {
                entry.put("hazardClass", c.getHazardClass().trim());
            }
            if (StringUtils.hasText(c.getPackingGroup())) {
                // DHL spells this differently to FedEx — packAGINGroup, not packINGgroup.
                entry.put("packagingGroup", c.getPackingGroup().trim().toUpperCase());
            }
            if (c.getQuantity() != null) {
                entry.put("netWeight", Map.of(
                        "value", c.getQuantity(),
                        "unit", "KG"));
            }
            out.add(entry);
        }
        return out;
    }

    /** DHL Package — typeCode + weight + dimensions in metric or imperial.
     *  Sprint 28 — takes a PackageDetailDTO so per-box shape overrides
     *  the shipment-level top-level fields. */
    private Map<String, Object> buildPackage(ShipmentRequestDTO request,
                                              com.multiship.backend.dto.PackageDetailDTO p) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("typeCode", firstNonBlank(
                firstNonBlank(p.getPackageType(), request.getPackageType()), "3BX"));
        pkg.put("weight", p.getWeight());
        if (p.getLength() != null && p.getWidth() != null && p.getHeight() != null) {
            pkg.put("dimensions", Map.of(
                    "length", p.getLength(),
                    "width", p.getWidth(),
                    "height", p.getHeight()));
        }
        return pkg;
    }

    /** Iterate every package on the shipment into DHL's packages[]. */
    private List<Map<String, Object>> buildPackages(ShipmentRequestDTO request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.multiship.backend.dto.PackageDetailDTO p : request.effectivePackages()) {
            out.add(buildPackage(request, p));
        }
        return out;
    }

    /**
     * DHL {@code exportDeclaration} block. Commodity lines follow a slightly
     * different shape from FedEx — {@code number} (1-based line index) is
     * required and {@code price} is per-unit (not extended). {@code exportReason}
     * uses DHL's closed enum: {@code commercial_purpose_or_sale},
     * {@code gift}, {@code personal_effects}, {@code sample}, {@code return},
     * {@code repair_or_processing}.
     */
    private Map<String, Object> buildExportDeclaration(ShipmentRequestDTO request) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        Map<String, Object> ed = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        int n = 1;
        for (com.multiship.backend.dto.CustomsCommodityDTO c : intl.getCommodities()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("number", n++);
            line.put("description", firstNonBlank(c.getDescription(), ""));
            line.put("price", c.getUnitValue() != null ? c.getUnitValue() : BigDecimal.ZERO);
            line.put("quantity", Map.of(
                    "value", c.getQuantity() != null ? c.getQuantity() : 1,
                    "unitOfMeasurement", "PCS"));
            if (StringUtils.hasText(c.getHsCode())) {
                line.put("commodityCodes", List.of(Map.of(
                        "typeCode", "outbound",
                        "value", c.getHsCode())));
            }
            if (StringUtils.hasText(c.getCountryOfOrigin())) {
                line.put("manufacturerCountry", c.getCountryOfOrigin());
            }
            if (c.getUnitWeight() != null) {
                line.put("weight", Map.of(
                        "netValue", c.getUnitWeight(),
                        "grossValue", c.getUnitWeight()));
            }
            lines.add(line);
        }
        ed.put("lineItems", lines);
        ed.put("invoice", Map.of(
                "number", firstNonBlank(request.getReferenceNumber(), "INV-" + System.currentTimeMillis()),
                // F6-E — invoice date follows the shipper's local calendar day.
                "date", com.multiship.backend.util.LabelDates.today(request.getShipperTimezone())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE)));
        ed.put("exportReason", mapExportReason(intl.getReasonForExport()));

        // Duty billing — DHL uses shipperAccountNumber for SENDER, otherwise
        // the payer's account. Absent = recipient (DHL default).
        // F6-C — clearanceOption (per-account, resolved by
        // ShipmentDefaultsResolver from CarrierAccountRef) wins over
        // dutyBillTo (per-customs-profile) when set. Both accept DHL's
        // Incoterms vocabulary (DAP / DDP / EXW / …) too — DDP means
        // sender pays duties, matching the SENDER branch.
        String dutyBillTo = firstNonBlank(intl.getClearanceOption(), intl.getDutyBillTo());
        if ("SENDER".equalsIgnoreCase(dutyBillTo)
                || "DDP".equalsIgnoreCase(dutyBillTo)
                || "DDP".equalsIgnoreCase(intl.getIncoterms())) {
            ed.put("customsDocuments", List.of(Map.of("typeCode", "INV")));
        }
        return ed;
    }

    /** OUR reason-for-export enum → DHL exportReason. */
    private static String mapExportReason(String reason) {
        if (reason == null) return "commercial_purpose_or_sale";
        return switch (reason.trim().toUpperCase()) {
            case "SALE" -> "commercial_purpose_or_sale";
            case "GIFT" -> "gift";
            case "SAMPLE" -> "sample";
            case "RETURN" -> "return";
            case "REPAIR" -> "repair_or_processing";
            case "DOCUMENTS" -> "personal_effects";
            default -> "commercial_purpose_or_sale";
        };
    }

    private ShipmentResult parseShipmentResult(String response) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            // Master AWB (shipment identity).
            String trackingNumber = root.path("shipmentTrackingNumber").asText(null);

            // Top-level label — first document of typeCode=label.
            String labelUrl = null;
            for (JsonNode doc : root.path("documents")) {
                if ("label".equalsIgnoreCase(doc.path("typeCode").asText())) {
                    labelUrl = doc.path("url").asText(null);
                    if (labelUrl == null) labelUrl = doc.path("content").asText(null);
                    break;
                }
            }
            String trackingUrl = StringUtils.hasText(trackingNumber)
                    ? "https://www.dhl.com/en/express/tracking.html?AWB=" + trackingNumber : null;

            // Per-piece rows — DHL Express returns packages[] with each
            // package's own trackingNumber. Some responses also include a
            // per-package label document array; we mirror the shipment
            // label onto every piece when the response doesn't split them.
            java.util.List<PackageTracking> packages = new java.util.ArrayList<>();
            JsonNode pkgsNode = root.path("packages");
            if (pkgsNode.isArray()) {
                for (int i = 0; i < pkgsNode.size(); i++) {
                    JsonNode pkg = pkgsNode.get(i);
                    String pcTrack = pkg.path("trackingNumber").asText(null);
                    if (!StringUtils.hasText(pcTrack)) continue;
                    // Per-package label (if the response splits documents by piece).
                    String pcLabel = null;
                    JsonNode pcDocs = pkg.path("documents");
                    if (pcDocs.isArray()) {
                        for (JsonNode doc : pcDocs) {
                            if ("label".equalsIgnoreCase(doc.path("typeCode").asText())) {
                                pcLabel = firstNonBlank(doc.path("url").asText(null), doc.path("content").asText(null));
                                break;
                            }
                        }
                    }
                    if (pcLabel == null) pcLabel = labelUrl;
                    packages.add(new PackageTracking(i + 1, pcTrack,
                            "https://www.dhl.com/en/express/tracking.html?AWB=" + pcTrack,
                            pcLabel, pcLabel, null));
                }
            }

            return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelUrl,
                    null, LocalDateTime.now(ZoneOffset.UTC).plusDays(2), response, packages);
        } catch (Exception ex) {
            log.warn("Failed to parse DHL response; treating as fallback. Reason: {}", ex.getMessage());
            return new ShipmentResult(null, null, null, null, null, null, response,
                    java.util.List.of());
        }
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : (StringUtils.hasText(b) ? b : null);
    }

    // FDX-A — buildFallbackShipmentResult removed. It was dead code (no
    // caller) and produced a synthetic JD-prefixed tracking + labels.local
    // URL that would have masked real DHL errors if ever wired in. Kept in
    // git history for reference.

/**
     * DHL Express Rate quote — POST {@code /rates} with Basic Auth returns
     * the {@code products[]} array; every entry is a priced product for the
     * lane. Perfect for rate shopping.
     *
     * <p>Request shape mirrors DHL's rating-only payload — {@code customerDetails}
     * with shipper + receiver, {@code accounts} to lock in negotiated
     * pricing, and a slim {@code packages[]} with weight + dims. No customs
     * invoice needed for rating (the {@code isCustomsDeclarable} flag alone
     * is sufficient to nudge DHL into international pricing).
     *
     * <p>Response shape (only fields we care about):
     * <pre>
     * products[]  → one entry per priced product
     *   productCode        → "P", "N", "U", "T", ...
     *   productName
     *   totalPrice[]       → array — pick BILLC (customer's billing currency)
     *     price
     *     priceCurrency
     *     typeCode         → BILLC | PULCL | STDRT
     *   deliveryCapabilities.totalTransitDays
     *   deliveryCapabilities.estimatedDeliveryDateAndTime
     * </pre>
     *
     * <p>{@code -local-*} fallback tokens short-circuit to an empty list.
     */
    @Override
    public List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return List.of();
        }
        // FDX-B3 — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream in buildParty (line ~1025 —
        // firstNonBlank(country, "US")). DHL primarily quotes intl lanes,
        // so a blank recipient country produced a nonsensical US→US rate
        // request that DHL either rejected loudly (best case) or returned
        // wrong "domestic" pricing for (worst case). Same F7 guard.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "DHL rate-shop requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before rate-shopping — "
                            + "quotes without a destination silently fall to US-domestic.");
        }
        try {
            Map<String, Object> body = buildRatePayload(request);
            String host = isSandbox(environment)
                    ? carrierProperties.getDhl().getSandboxUrl()
                    : carrierProperties.getDhl().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(host).build()
                    .post()
                    .uri("/rates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseDhlRateResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL rate quote rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return List.of();
        } catch (Exception ex) {
            log.warn("DHL rate quote failed; returning empty rate list. Reason: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Slimmed-down {@code /rates} payload (no customs invoice, no label
     * options). DHL rates require the origin+destination address and the
     * package spec; everything else is optional for rating.
     */
    Map<String, Object> buildRatePayload(ShipmentRequestDTO request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerDetails", Map.of(
                "shipperDetails", buildParty(
                        request.getShipperName(), request.getShipperPhone(),
                        request.getShipperAddressLine1(), request.getShipperAddressLine2(), null,
                        request.getShipperCity(), request.getShipperState(),
                        request.getShipperPostalCode(), request.getShipperCountryCode(), null),
                "receiverDetails", buildParty(
                        request.getRecipientName(),
                        UpsConnector.joinPhone(request.getRecipientPhoneCountryCode(), request.getRecipientPhone()),
                        request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                        request.getRecipientAddressLine3(),
                        request.getRecipientCity(), request.getRecipientState(),
                        request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                        request.getRecipientResidential())));
        payload.put("accounts", List.of(Map.of(
                "typeCode", "shipper",
                "number", firstNonBlank(request.getAccountNumber(), ""))));
        payload.put("plannedShippingDateAndTime", plannedShipDate(request.getShipperTimezone()));
        payload.put("unitOfMeasurement",
                "KG".equalsIgnoreCase(request.getWeightUnit()) ? "metric" : "imperial");
        boolean isIntl = request.getIntl() != null && request.getIntl().isReadyForCarrier();
        payload.put("isCustomsDeclarable", isIntl);
        payload.put("packages", buildPackages(request));
        return payload;
    }

    /**
     * Parse the DHL rate response into carrier-neutral RateOptions.
     * Package-visible so tests can assert against canned response JSON.
     */
    List<RateOption> parseDhlRateResponse(String response) {
        try {
            JsonNode products = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .path("products");
            if (!products.isArray()) return List.of();

            List<RateOption> out = new ArrayList<>();
            for (JsonNode product : products) {
                String productCode = product.path("productCode").asText(null);
                if (productCode == null || productCode.isEmpty()) continue;
                String productName = product.path("productName").asText(productCode);

                BigDecimal amount = pickDhlPrice(product.path("totalPrice"));
                String currency = pickDhlCurrency(product.path("totalPrice"));
                if (amount == null) continue;

                Integer transitDays = null;
                JsonNode transit = product.at("/deliveryCapabilities/totalTransitDays");
                if (transit.isNumber()) transitDays = transit.asInt();
                else if (transit.isTextual()) {
                    try { transitDays = Integer.parseInt(transit.asText().trim()); }
                    catch (NumberFormatException ex) {
                        log.debug("DHL rate: non-numeric transitDays '{}'", transit.asText());
                    }
                }

                LocalDateTime estimatedDelivery = parseDhlEstimated(
                        product.at("/deliveryCapabilities/estimatedDeliveryDateAndTime").asText(null));

                out.add(new RateOption("DHL", productCode, productName, amount, currency,
                        estimatedDelivery, transitDays));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            log.warn("DHL rate response parse failed: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Prefer {@code typeCode=BILLC} (billable / negotiated price shown in the
     * customer's billing currency) over PULCL / STDRT. Falls through to the
     * first entry when no BILLC is present.
     */
    private static BigDecimal pickDhlPrice(JsonNode totalPrice) {
        if (!totalPrice.isArray() || totalPrice.isEmpty()) return null;
        JsonNode fallback = totalPrice.get(0);
        for (JsonNode entry : totalPrice) {
            if ("BILLC".equalsIgnoreCase(entry.path("priceCurrency").isTextual()
                    ? entry.path("priceCurrency").asText() : "")) {
                // BILLC is sometimes signalled via priceCurrency, other times
                // via typeCode; treat either match as billable.
            }
            if ("BILLC".equalsIgnoreCase(entry.path("typeCode").asText(""))) {
                return readDhlAmount(entry);
            }
        }
        return readDhlAmount(fallback);
    }

    /**
     * DHL-2 — pre-fix silently defaulted to "USD" in 3 spots (empty array,
     * BILLC entry without priceCurrency, and first-entry fallback). Real
     * DHL responses always populate priceCurrency (mandatory in the /rates
     * response schema); surfacing a genuine null tells the rate-shop UI
     * "no quote" rather than mislabeling a GBP/EUR rate as USD by the FX
     * difference. Same UPS-14/15 fix on UpsConnector.
     */
    private static String pickDhlCurrency(JsonNode totalPrice) {
        if (!totalPrice.isArray() || totalPrice.isEmpty()) return null;
        for (JsonNode entry : totalPrice) {
            if ("BILLC".equalsIgnoreCase(entry.path("typeCode").asText(""))) {
                String c = entry.path("priceCurrency").asText(null);
                return StringUtils.hasText(c) ? c : null;
            }
        }
        String c = totalPrice.get(0).path("priceCurrency").asText(null);
        return StringUtils.hasText(c) ? c : null;
    }

    private static BigDecimal readDhlAmount(JsonNode entry) {
        JsonNode price = entry.path("price");
        if (price.isNumber()) return price.decimalValue();
        if (price.isTextual()) {
            try { return new BigDecimal(price.asText()); }
            catch (NumberFormatException ex) {
                log.debug("DHL readDhlAmount: non-numeric price '{}'", price.asText());
            }
        }
        return null;
    }

    /**
     * DHL {@code estimatedDeliveryDateAndTime} arrives as "2024-01-18T10:30:00 GMT+00:00"
     * — LocalDateTime.parse chokes on the trailing timezone marker, so strip
     * it before parsing. Returns null on any parse failure.
     */
    private LocalDateTime parseDhlEstimated(String value) {
        if (!StringUtils.hasText(value)) return null;
        String cleaned = value.contains(" ") ? value.substring(0, value.indexOf(' ')) : value;
        try {
            return LocalDateTime.parse(cleaned);
        } catch (Exception ex) {
            log.debug("DHL parseDhlEstimated: unparseable estimatedDelivery '{}' (cleaned to '{}')", value, cleaned);
            return null;
        }
    }

    private String buildFallbackToken(String clientId, String clientSecret) {
        return "dhl-local-" + hashShort(clientId + ":" + clientSecret + ":" + LocalDateTime.now(ZoneOffset.UTC));
    }

    private String hashShort(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) builder.append(String.format("%02x", hash[i]));
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * DHL's "planned shipping date" — next business day at 13:00 GMT+00:00.
     *
     * <p>F6-E: the DATE part follows the shipper's local calendar so an
     * evening print in Asia doesn't produce "tomorrow-in-UTC" (== the same
     * calendar day the shipper is already in). The 13:00 GMT+00:00 clock
     * portion is preserved verbatim — it's DHL's expected pickup slot
     * literal, not a real-clock time in the shipper's zone.
     */
    private static String plannedShipDate(String timezone) {
        return com.multiship.backend.util.LabelDates.todayPlus(timezone, 1)
                + "T13:00:00 GMT+00:00";
    }

    /** Environment-tolerant SANDBOX check. */
    private static boolean isSandbox(String environment) {
        return environment != null && "SANDBOX".equalsIgnoreCase(environment.trim());
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }
}
