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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpsConnector implements CarrierConnector {

    private static final String CARRIER_CODE = "UPS";

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    /** Per-thread reason the last getAccessToken fell back — read by verify. */
    private static final ThreadLocal<String> LAST_AUTH_DETAIL = new ThreadLocal<>();

    @Override
    public String consumeAuthFailureDetail() {
        String detail = LAST_AUTH_DETAIL.get();
        LAST_AUTH_DETAIL.remove();
        return detail;
    }

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "UPS";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
        List<ServiceOffering> matrix = serviceMatrix(originCountry);
        // A live availability lookup needs a REAL OAuth token. In dev the
        // connectors run on local fallback tokens (no live UPS credentials) —
        // report the built-in model honestly rather than faking a live call.
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        if (!realToken) {
            return new ServiceAvailability(matrix, false, "not verified — no live UPS credentials");
        }
        // The account authenticated live (verified). Prefer a genuine availability
        // response; if UPS returns nothing, publish the carrier's standard service
        // catalog for this verified account (still live — backed by a verified credential).
        try {
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken, environment);
            if (!live.isEmpty()) {
                return new ServiceAvailability(live, true, "UPS Rating API (Shop)");
            }
        } catch (Exception ex) {
            log.warn("UPS availability lookup unavailable; using verified published catalog. Reason: {}", ex.getMessage());
        }
        return new ServiceAvailability(matrix, true, "verified UPS account · published service catalog");
    }

    /**
     * LIVE UPS availability via the Rating API "Shop" request (returns every
     * rated service for a lane). Real endpoint + auth; the request body and
     * RatedShipment→service mapping must be finalised against a UPS sandbox
     * (see backend/docs/CUSTOMS_CARRIER_MAPPING.md). Throws/returns empty when
     * unreachable so the caller falls back to the built-in model.
     */
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken, String environment) throws Exception {
        String baseUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxUrl()
                : carrierProperties.getUps().getApiBaseUrl();
        String url = baseUrl + "/api/rating/"
                + carrierProperties.getUps().getApiVersion() + "/Shop";
        String response = HttpClients.newBuilder().baseUrl(url).build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .body(Map.of("RateRequest", Map.of("Shipment",
                        Map.of("ShipFrom", Map.of("Address", Map.of("CountryCode",
                                originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT)))))))
                .retrieve()
                .body(String.class);
        List<ServiceOffering> out = new java.util.ArrayList<>();
        JsonNode rated = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                .path("RateResponse").path("RatedShipment");
        for (JsonNode r : rated.isArray() ? rated : objectMapper.createArrayNode().add(rated)) {
            String code = r.path("Service").path("Code").asText(null);
            if (StringUtils.hasText(code)) {
                out.add(new ServiceOffering(code, r.path("Service").path("Description").asText("UPS " + code), "BOTH"));
            }
        }
        return out;
    }

    private List<ServiceOffering> serviceMatrix(String originCountry) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        return switch (o) {
            // US/PR: domestic ground+air ladder + the Worldwide export portfolio.
            case "US", "PR" -> List.of(
                    new ServiceOffering("03", "UPS Ground", "DOMESTIC"),
                    new ServiceOffering("12", "UPS 3 Day Select", "DOMESTIC"),
                    new ServiceOffering("02", "UPS 2nd Day Air", "DOMESTIC"),
                    new ServiceOffering("01", "UPS Next Day Air", "DOMESTIC"),
                    new ServiceOffering("65", "UPS Worldwide Saver", "INTERNATIONAL"),
                    new ServiceOffering("08", "UPS Worldwide Expedited", "INTERNATIONAL"),
                    new ServiceOffering("07", "UPS Worldwide Express", "INTERNATIONAL"));
            // Europe/UK: Standard is the intra-Europe ground service; export uses Express tiers.
            case "DE", "GB", "FR", "NL", "IT", "ES", "PL", "BE" -> List.of(
                    new ServiceOffering("11", "UPS Standard", "DOMESTIC"),
                    new ServiceOffering("65", "UPS Express Saver", "INTERNATIONAL"),
                    new ServiceOffering("07", "UPS Express", "INTERNATIONAL"),
                    new ServiceOffering("54", "UPS Express Plus", "INTERNATIONAL"),
                    new ServiceOffering("08", "UPS Expedited", "INTERNATIONAL"));
            // Rest of world (Asia-Pacific, etc.): export-only, no UPS domestic ground.
            default -> List.of(
                    new ServiceOffering("65", "UPS Worldwide Saver", "INTERNATIONAL"),
                    new ServiceOffering("08", "UPS Worldwide Expedited", "INTERNATIONAL"),
                    new ServiceOffering("07", "UPS Worldwide Express", "INTERNATIONAL"),
                    new ServiceOffering("54", "UPS Worldwide Express Plus", "INTERNATIONAL"));
        };
    }

    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        // UPS packaging is a published, static catalogue (same set every origin);
        // the 10/25KG boxes are international-only. Token unused — packaging isn't
        // a live availability call.
        List<PackageOffering> pkgs = List.of(
                new PackageOffering("01", "UPS Letter", bd("12.5"), bd("9.5"), bd("0.5"), bd("1"), false, "BOTH"),
                new PackageOffering("04", "UPS Express Pak", bd("16"), bd("12.75"), bd("2"), bd("3"), false, "BOTH"),
                new PackageOffering("03", "UPS Tube", bd("38"), bd("6"), bd("6"), null, false, "BOTH"),
                new PackageOffering("2a", "UPS Small Express Box", bd("13"), bd("11"), bd("2"), null, false, "BOTH"),
                new PackageOffering("2b", "UPS Medium Express Box", bd("15"), bd("11"), bd("3"), null, false, "BOTH"),
                new PackageOffering("2c", "UPS Large Express Box", bd("18"), bd("13"), bd("3"), null, false, "BOTH"),
                new PackageOffering("25", "UPS 10KG Box", bd("16.5"), bd("13.25"), bd("10.75"), bd("22"), true, "INTERNATIONAL"),
                new PackageOffering("24", "UPS 25KG Box", bd("16.5"), bd("13.25"), bd("10.75"), bd("55"), true, "INTERNATIONAL"));
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        return realToken
                ? new PackageAvailability(pkgs, true, "verified UPS account · published packaging")
                : new PackageAvailability(pkgs, false, "not verified — no live UPS credentials");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Case/whitespace-tolerant SANDBOX check — everything else is production. */
    private static boolean isSandbox(String environment) {
        return environment != null && "SANDBOX".equalsIgnoreCase(environment.trim());
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        validateCredentials(clientId, clientSecret);
        String accessToken = getAccessToken(clientId, clientSecret, accountNumber);
        LocalDateTime tokenExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        return new CarrierConnectionResult(
                CARRIER_CODE,
                getCarrierName(),
                true,
                accountNumber,
                carrierProperties.getDefaultEnvironment(),
                accessToken,
                tokenExpiresAt,
                "UPS carrier connection established successfully."
        );
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret, null, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        return getAccessToken(clientId, clientSecret, accountNumber, null);
    }

    /**
     * UPS OAuth 2.0 (Client Credentials) requires Basic Auth on the token
     * endpoint — {@code Authorization: Basic base64(clientId:clientSecret)} —
     * with only {@code grant_type=client_credentials} in the form body. The
     * previous implementation put client_id/client_secret in the body (the
     * FedEx pattern), which UPS rejects with {@code invalid_client}; the
     * exception was swallowed and a fake {@code -local-*} token was returned,
     * so verify silently reported "credentials rejected" for CORRECT UPS keys.
     *
     * <p>The {@code x-merchant-id} header (UPS shipper number) is optional but
     * recommended — UPS attaches quota / rate-limit counters to the merchant.
     *
     * <p>Environment routing: UPS issues Consumer Keys per-environment. A CIE
     * (sandbox) key 401s with error 10401 "ClientId is Invalid" against the
     * production {@code onlinetools.ups.com} host, and a production key does
     * the same in reverse. We route to the matching endpoint based on the
     * {@code environment} argument ("SANDBOX" → wwwcie, otherwise production).
     *
     * <p>Note: the "Consumer Key" and "Consumer Secret" values from the UPS
     * Developer Portal ARE the OAuth client_id / client_secret used here.
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        // Sprint 51 BS-L2 — LAST_AUTH_DETAIL is per-thread state that the
        // caller consumes AFTER we return. If a prior request on this same
        // pool-recycled thread set the detail and the caller never
        // consumed it (e.g., success path took an early branch), the
        // stale value would leak into the current caller's read. Reset
        // at entry so each invocation starts clean; the fallback branches
        // below set fresh values that the current caller then consumes.
        LAST_AUTH_DETAIL.remove();
        String tokenUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxAuthUrl()
                : carrierProperties.getUps().getAuthUrl();
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");

            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

            RestClient restClient = HttpClients.newBuilder().baseUrl(tokenUrl).build();
            RestClient.RequestBodySpec request = restClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + basic);
            if (StringUtils.hasText(accountNumber)) {
                request = request.header("x-merchant-id", accountNumber.trim());
            }

            String response = request.body(form).retrieve().body(String.class);

            JsonNode jsonNode = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            String accessToken = jsonNode.path("access_token").asText(null);
            if (!StringUtils.hasText(accessToken)) {
                // Sprint 51 BS-L1 — redact credentials from the echoed body
                // before it lands in a persistent log store.
                String safeBody = LogRedaction.redactSecrets(response, clientId, clientSecret);
                log.warn("UPS token endpoint returned no access_token; response: {}", safeBody);
                LAST_AUTH_DETAIL.set("UPS returned no access token.");
                return buildFallbackToken(clientId, clientSecret);
            }
            LAST_AUTH_DETAIL.remove();
            return accessToken;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // UPS puts the reason ({"response":{"errors":[{"code":"...","message":"..."}]}})
            // in the response body. Surface it in the log AND to the operator so
            // verify failures are actionable (invalid ClientId vs env mismatch).
            // Sprint 51 BS-L1 — scrub credentials before logging; the UPS 401
            // body sometimes echoes the presented clientId verbatim.
            String body = ex.getResponseBodyAsString();
            int status = ex.getStatusCode().value();
            String safeBody = LogRedaction.redactSecrets(body, clientId, clientSecret);
            log.warn("UPS token request rejected (HTTP {}): {} — using local fallback token.", status, safeBody);
            LAST_AUTH_DETAIL.set(describeUpsAuthError(status, body, isSandbox(environment)));
            return buildFallbackToken(clientId, clientSecret);
        } catch (Exception ex) {
            log.warn("UPS token request failed; using local fallback token. Reason: {}", ex.getMessage());
            LAST_AUTH_DETAIL.set("could not reach the UPS OAuth endpoint (" + ex.getMessage() + ")");
            return buildFallbackToken(clientId, clientSecret);
        }
    }

    /**
     * Turn a UPS OAuth error body into an operator-facing sentence. UPS 10401
     * "ClientId is Invalid" almost always means the Client ID/Secret aren't
     * valid keys for the selected environment (a CIE sandbox key used against
     * production, or vice-versa) — call that out explicitly.
     */
    private String describeUpsAuthError(int status, String body, boolean sandbox) {
        String code = null;
        String message = null;
        try {
            JsonNode err = objectMapper.readTree(Optional.ofNullable(body).orElse("{}"))
                    .path("response").path("errors").path(0);
            code = err.path("code").asText(null);
            message = err.path("message").asText(null);
        } catch (Exception ignore) {
            // fall through to the generic message below
        }
        String env = sandbox ? "SANDBOX (wwwcie.ups.com)" : "PRODUCTION (onlinetools.ups.com)";
        if ("10401".equals(code) || (message != null && message.toLowerCase(Locale.ROOT).contains("clientid"))) {
            return "UPS rejected the Client ID (10401: ClientId is Invalid). "
                    + "Confirm the Client ID / Secret are UPS OAuth keys for the " + env
                    + " environment — a sandbox key fails against production and vice-versa.";
        }
        if (code != null || message != null) {
            return "UPS OAuth returned HTTP " + status + " (" + code + ": " + message + ") for " + env + ".";
        }
        return "UPS OAuth returned HTTP " + status + " for " + env + ".";
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        // F7 fix — recipient country is required. UPS lets you ship anywhere
        // the ShipTo party's country is set to; a blank country would pass
        // through to the UPS envelope and either error out (400 InvalidCountry)
        // or ship as an unspecified destination. Fail early with a clear
        // message so operators know what to fix on the Order.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "UPS shipment requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before generating a label.");
        }
        // FDX-I2 — boundary guard on shipper accountNumber. Pre-fix,
        // buildPaymentInformation:1755 + buildRateShopShipment:2076 used
        // firstNonBlank(request.getAccountNumber(), "") which shipped an
        // empty Shipper.Account.AccountNumber on the wire. UPS rejects
        // that with a validation error but the operator saw a cryptic
        // "400 InvalidAccount" rather than a message pointing at the
        // CarrierAccountRef row. Mirrors the FDX-2 FedEx pattern; also
        // catches the pre-FDX-I1 CarrierServiceImpl "ACCOUNT" placeholder
        // in case any legacy call site still plants it.
        if (!StringUtils.hasText(request.getAccountNumber())
                || "ACCOUNT".equalsIgnoreCase(request.getAccountNumber().trim())) {
            throw new IllegalArgumentException(
                    "UPS shipment requires the shipper account number that owns the label (order "
                            + request.getReferenceNumber() + "). The upstream account resolution "
                            + "returned blank or the \"ACCOUNT\" placeholder — check that a "
                            + "CarrierAccountRef row exists for this shipper + carrier before "
                            + "generating the label.");
        }
        try {
            Map<String, Object> payload = buildShipmentPayload(request);
            String baseUrl = isSandbox(environment)
                    ? carrierProperties.getUps().getSandboxUrl()
                    : carrierProperties.getUps().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseShipmentResult(response);
        } catch (com.multiship.backend.service.carriers.exceptions.CarrierException cex) {
            // Already typed — surface without wrapping so the caller can
            // distinguish auth / validation / rate-limit / server.
            throw cex;
        } catch (Exception ex) {
            // Sprint 49 Tier 2: no more silent fake-label fallback. Throw a
            // typed carrier exception so the caller (CarrierServiceImpl)
            // persists FAILED_CARRIER + returns 502/429/etc. instead of a
            // synthetic tracking number for a shipment that never happened.
            log.warn("UPS createShipment failed: {}", ex.getMessage());
            throw com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper
                    .map("UPS", ex, "createShipment");
        }
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("UPS client id and client secret are required.");
        }
        return true;
    }

    /**
     * URL-only tracking. UPS's Track API requires OAuth so this 1-arg
     * variant only returns a public tracking link (like FedEx in Sprint 12).
     * The authenticated variant below does the real work.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingUrl = "https://www.ups.com/track?tracknum=" + trackingNumber;
        return new TrackingResult(trackingNumber, "UNKNOWN", trackingUrl, null, null, false, null);
    }

    /**
     * UPS Track API v1 — {@code GET /api/track/v1/details/{trackingNumber}}
     * with the Bearer token. Two required headers beyond auth:
     * {@code transId} (unique per request; UPS uses it for idempotency
     * troubleshooting) and {@code transactionSrc} (client identifier).
     *
     * <p>Response shape (only the fields we care about):
     * <pre>
     * trackResponse.shipment[0].package[0].{
     *   currentStatus.description,
     *   activity[] (newest-first) — reversed to oldest-first,
     *   deliveryDate[] with type=DEL for the actual delivery date,
     * }
     * </pre>
     *
     * <p>UPS activity dates come in as YYYYMMDD + HHMMSS separately; we
     * merge to a proper LocalDateTime. Status code {@code D} =
     * Delivered; any other code passes through in the event.status field.
     *
     * <p>{@code -local-*} tokens short-circuit to the URL-only stub — same
     * convention Sprint 12 established for FedEx.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingUrl = "https://www.ups.com/track?tracknum=" + trackingNumber;
        String baseUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxUrl()
                : carrierProperties.getUps().getApiBaseUrl();
        try {
            String response = HttpClients.newBuilder().baseUrl(baseUrl).build().get()
                    .uri("/api/track/v1/details/" + trackingNumber)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .retrieve()
                    .body(String.class);

            JsonNode pkg = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .at("/trackResponse/shipment/0/package/0");

            String status = pkg.at("/currentStatus/description").asText("UNKNOWN");
            String statusCode = pkg.at("/currentStatus/code").asText(null);
            boolean delivered = "D".equalsIgnoreCase(statusCode)
                    || "DELIVERED".equalsIgnoreCase(status);

            java.util.List<TrackingEvent> events = parseUpsActivity(pkg.at("/activity"));
            String currentLocation = events.isEmpty() ? null : events.get(events.size() - 1).location();
            LocalDateTime estimatedDelivery = parseUpsDeliveryDate(pkg.at("/deliveryDate"));

            return new TrackingResult(trackingNumber, status, trackingUrl, currentLocation,
                    estimatedDelivery, delivered, response, events);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("UPS track rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return trackShipment(trackingNumber);
        } catch (Exception ex) {
            log.warn("UPS track failed for {}; falling back to URL-only. Reason: {}",
                    trackingNumber, ex.getMessage());
            return trackShipment(trackingNumber);
        }
    }

    /**
     * Parse UPS activity[] → TrackingEvent list. UPS returns newest-first;
     * we reverse for oldest-first (matches FedEx / DHL convention set in
     * Sprint 12). Empty when the field is absent or an empty array.
     */
    java.util.List<TrackingEvent> parseUpsActivity(JsonNode activity) {
        if (activity == null || !activity.isArray() || activity.isEmpty()) return java.util.List.of();
        java.util.List<TrackingEvent> events = new java.util.ArrayList<>();
        for (JsonNode a : activity) {
            LocalDateTime ts = joinUpsDateTime(
                    a.path("date").asText(null),
                    a.path("time").asText(null));
            String status = a.at("/status/code").asText(null);
            if (status == null || status.isEmpty()) status = a.at("/status/type").asText(null);
            String description = a.at("/status/description").asText("");
            String location = buildUpsLocation(a.at("/location/address"));
            events.add(new TrackingEvent(ts, status, description, location));
        }
        java.util.Collections.reverse(events);
        return java.util.List.copyOf(events);
    }

    /**
     * UPS deliveryDate[] carries typed entries — DEL for the actual delivery
     * date, RDD for rescheduled, etc. We pick the first DEL entry as the
     * estimated/actual delivery timestamp.
     */
    private LocalDateTime parseUpsDeliveryDate(JsonNode deliveryDate) {
        if (deliveryDate == null || !deliveryDate.isArray()) return null;
        for (JsonNode entry : deliveryDate) {
            if ("DEL".equalsIgnoreCase(entry.path("type").asText(""))) {
                return joinUpsDateTime(entry.path("date").asText(null), null);
            }
        }
        return null;
    }

    /**
     * UPS date/time formats are {@code YYYYMMDD} + {@code HHMMSS}. Merges to
     * a LocalDateTime; missing time defaults to 00:00:00.
     */
    static LocalDateTime joinUpsDateTime(String yyyymmdd, String hhmmss) {
        if (yyyymmdd == null || yyyymmdd.length() != 8) return null;
        try {
            int y = Integer.parseInt(yyyymmdd.substring(0, 4));
            int mo = Integer.parseInt(yyyymmdd.substring(4, 6));
            int d = Integer.parseInt(yyyymmdd.substring(6, 8));
            int hr = 0, mn = 0, sec = 0;
            if (hhmmss != null && hhmmss.length() == 6) {
                hr = Integer.parseInt(hhmmss.substring(0, 2));
                mn = Integer.parseInt(hhmmss.substring(2, 4));
                sec = Integer.parseInt(hhmmss.substring(4, 6));
            }
            return LocalDateTime.of(y, mo, d, hr, mn, sec);
        } catch (Exception ex) {
            log.debug("UPS joinUpsDateTime: unparseable date='{}' time='{}'", yyyymmdd, hhmmss);
            return null;
        }
    }

    /** Build a "City, ST US" location string from a UPS address node. */
    static String buildUpsLocation(JsonNode addr) {
        if (addr == null || addr.isMissingNode() || addr.isNull()) return null;
        String city = addr.path("city").asText("");
        String state = addr.path("stateProvince").asText("");
        String country = addr.path("country").asText("");
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) sb.append(city);
        if (!state.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(state);
        if (!country.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(country);
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * UPS Void Shipment — {@code DELETE /api/shipments/{version}/void/cancel/{tracking}}
     * with a Bearer token. Response body carries a {@code SummaryResult}
     * with a {@code Status.Code} of "1" on success. UPS refunds the
     * postage IF the label hasn't been scanned; post-scan void requests
     * still return 200 but no refund is issued (that's carrier policy,
     * not something we can gate here).
     *
     * <p>{@code -local-*} fallback tokens short-circuit to
     * {@code NOT_SUPPORTED} — the account never actually authenticated.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                    String accountNumber, String senderCountryCode) {
        // UPS cancel doesn't need accountNumber/senderCountry — the tracking
        // number alone identifies the shipment. Kept in signature for parity.
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                    "UPS void needs live credentials; the account is on a fallback token.",
                    null);
        }
        String url = "/api/shipments/" + carrierProperties.getUps().getApiVersion()
                + "/void/cancel/" + trackingNumber;
        String baseUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxUrl()
                : carrierProperties.getUps().getApiBaseUrl();
        try {
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .method(org.springframework.http.HttpMethod.DELETE)
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .retrieve()
                    .body(String.class);
            return parseUpsVoidResponse(trackingNumber, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.warn("UPS void rejected for {} (HTTP {}): {}",
                    trackingNumber, ex.getStatusCode().value(), body);
            return new VoidResult(trackingNumber, false, "ERROR",
                    "UPS void rejected: HTTP " + ex.getStatusCode().value(), body);
        } catch (Exception ex) {
            log.warn("UPS void call failed for {}: {}", trackingNumber, ex.getMessage());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "UPS void call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse UPS void response. Success = {@code VoidShipmentResponse.
     * SummaryResult.Status.Code = "1"}. Any other code is a soft failure
     * that we surface with the carrier's description.
     */
    VoidResult parseUpsVoidResponse(String trackingNumber, String response) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            JsonNode summary = root.at("/VoidShipmentResponse/SummaryResult/Status");
            String code = summary.path("Code").asText("");
            String description = summary.path("Description").asText("");
            boolean voided = "1".equals(code);
            String status = voided ? "VOIDED" : "ERROR";
            String message = voided
                    ? "UPS confirmed void." + (description.isEmpty() ? "" : " " + description)
                    : "UPS void rejected. " + description;
            return new VoidResult(trackingNumber, voided, status, message, response);
        } catch (Exception ex) {
            return new VoidResult(trackingNumber, false, "ERROR",
                    "UPS void response parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * UPS Address Validation Street Level (AVS) —
     * {@code POST /api/addressvalidation/{v}/1} with a Bearer token.
     * URL path suffix {@code /1} = request type "Address Validation Street
     * Level" (v2 has {@code /2} for basic city+state, {@code /3} for full
     * street match). Response body:
     * <pre>
     * XAVResponse.
     *   Response.ResponseStatus.Code (1 = success)
     *   ValidAddressIndicator     (presence = valid, no correction needed)
     *   AmbiguousAddressIndicator (presence = multiple matches)
     *   NoCandidatesIndicator     (presence = address not found)
     *   Candidate[] with AddressKeyFormat + AddressClassification
     * </pre>
     *
     * <p>Classification codes:
     *   0 = Unknown, 1 = Commercial, 2 = Residential.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public AddressValidationResult validateAddress(AddressToValidate address, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new AddressValidationResult(false, "NOT_SUPPORTED", "UNKNOWN", null,
                    java.util.List.of(),
                    "UPS AVS needs live credentials; the account is on a fallback token.",
                    null);
        }
        // UPS AVS host must match the env the token was minted in — a sandbox
        // (CIE) token 401s against onlinetools.ups.com and a prod token 401s
        // against wwwcie.ups.com. See getAccessToken() for the same routing.
        String baseUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxUrl()
                : carrierProperties.getUps().getApiBaseUrl();
        String url = "/api/addressvalidation/" + carrierProperties.getUps().getApiVersion() + "/1";
        try {
            Map<String, Object> body = buildUpsAvsRequest(address);
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .post()
                    .uri(url + "?regionalrequestindicator=false&maximumcandidatelistsize=5")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseUpsAvsResponse(address, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.warn("UPS AVS rejected (HTTP {}): {}", ex.getStatusCode().value(), body);
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    formatUpsError("UPS AVS", ex.getStatusCode().value(), body),
                    body);
        } catch (Exception ex) {
            log.warn("UPS AVS call failed: {}", ex.getMessage());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "UPS AVS call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Turn a UPS error response body into a human-readable message.
     * UPS wraps errors as {@code {"response":{"errors":[{"code":"...","message":"..."}]}}}.
     * Adds a hint for known "gotcha" codes (e.g. 250002 = product not subscribed on the app).
     */
    private String formatUpsError(String opName, int httpStatus, String body) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(body).orElse("{}"));
            JsonNode errors = root.path("response").path("errors");
            if (errors.isArray() && errors.size() > 0) {
                JsonNode first = errors.get(0);
                String code = first.path("code").asText("");
                String message = first.path("message").asText("");
                StringBuilder sb = new StringBuilder(opName).append(" · HTTP ").append(httpStatus);
                if (!code.isEmpty()) sb.append(" · ").append(code);
                if (!message.isEmpty()) sb.append(": ").append(message);
                if ("250002".equals(code)) {
                    sb.append(" — the OAuth token was issued but UPS did not accept it for this endpoint. "
                            + "Most common cause: the developer app that owns these credentials is not "
                            + "subscribed to the required product (Address Validation Street Level for AVS). "
                            + "Enable it on the UPS Developer Portal, or use credentials from an app that already has it.");
                } else if ("10401".equals(code)) {
                    sb.append(" — sandbox credentials called against production host (or vice versa). "
                            + "Check the carrier account's environment setting.");
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // Fall through to generic message
        }
        return opName + " rejected: HTTP " + httpStatus;
    }

    /** Build the UPS XAV request body. Only ShipTo.Address is sent. */
    private Map<String, Object> buildUpsAvsRequest(AddressToValidate a) {
        Map<String, Object> address = new LinkedHashMap<>();
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (StringUtils.hasText(a.addressLine1())) lines.add(a.addressLine1());
        if (StringUtils.hasText(a.addressLine2())) lines.add(a.addressLine2());
        if (StringUtils.hasText(a.addressLine3())) lines.add(a.addressLine3());
        address.put("AddressLine", lines);
        if (StringUtils.hasText(a.city())) address.put("PoliticalDivision2", a.city());
        if (StringUtils.hasText(a.state())) address.put("PoliticalDivision1", a.state());
        if (StringUtils.hasText(a.postalCode())) address.put("PostcodePrimaryLow", a.postalCode());
        if (StringUtils.hasText(a.countryCode())) address.put("CountryCode", a.countryCode());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("XAVRequest", Map.of(
                "AddressKeyFormat", address));
        return request;
    }

    /**
     * Parse a UPS AVS response into an AddressValidationResult.
     * Package-visible so tests can assert against canned response JSON.
     */
    AddressValidationResult parseUpsAvsResponse(AddressToValidate input, String response) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            JsonNode xav = root.path("XAVResponse");

            if (!xav.path("NoCandidatesIndicator").isMissingNode()) {
                return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                        java.util.List.of(),
                        "UPS couldn't find this address.", response);
            }

            boolean exact = !xav.path("ValidAddressIndicator").isMissingNode();
            boolean ambiguous = !xav.path("AmbiguousAddressIndicator").isMissingNode();

            JsonNode candidatesNode = xav.path("Candidate");
            java.util.List<JsonNode> candidates = new java.util.ArrayList<>();
            if (candidatesNode.isArray()) {
                candidatesNode.forEach(candidates::add);
            } else if (!candidatesNode.isMissingNode()) {
                candidates.add(candidatesNode);
            }

            AddressToValidate suggested = null;
            String classification = "UNKNOWN";
            if (!candidates.isEmpty()) {
                JsonNode first = candidates.get(0);
                suggested = readUpsCandidateAddress(first);
                classification = readUpsClassification(first);
            }

            if (exact) {
                return new AddressValidationResult(true, "EXACT", classification, null,
                        java.util.List.of(),
                        "UPS confirmed this address is deliverable.", response);
            }
            if (ambiguous) {
                return new AddressValidationResult(false, "AMBIGUOUS", classification, suggested,
                        java.util.List.of("UPS returned multiple candidate addresses; pick one to proceed."),
                        "UPS found multiple candidates.", response);
            }
            // Candidates present but no explicit valid flag → treat as
            // CORRECTED (UPS suggested a correction).
            return new AddressValidationResult(true, "CORRECTED", classification, suggested,
                    java.util.List.of("UPS suggested a corrected address; review before shipping."),
                    "UPS suggested a corrected address.", response);
        } catch (Exception ex) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "UPS AVS response parse failed: " + ex.getMessage(), response);
        }
    }

    private static AddressToValidate readUpsCandidateAddress(JsonNode candidate) {
        JsonNode key = candidate.path("AddressKeyFormat");
        java.util.List<String> lines = new java.util.ArrayList<>();
        JsonNode addrLines = key.path("AddressLine");
        if (addrLines.isArray()) addrLines.forEach(n -> lines.add(n.asText()));
        else if (addrLines.isTextual()) lines.add(addrLines.asText());
        return new AddressToValidate(
                null, null,
                lines.size() > 0 ? lines.get(0) : null,
                lines.size() > 1 ? lines.get(1) : null,
                lines.size() > 2 ? lines.get(2) : null,
                key.path("PoliticalDivision2").asText(null),
                key.path("PoliticalDivision1").asText(null),
                key.path("PostcodePrimaryLow").asText(null),
                key.path("CountryCode").asText(null));
    }

    /** UPS Classification.Code: "1" Commercial, "2" Residential, "0" Unknown. */
    private static String readUpsClassification(JsonNode candidate) {
        String code = candidate.at("/AddressClassification/Code").asText("0");
        return switch (code) {
            case "1" -> "COMMERCIAL";
            case "2" -> "RESIDENTIAL";
            default -> "UNKNOWN";
        };
    }

    /**
     * UPS Landed Cost — extends {@link #getRates} with a landed-cost
     * request. UPS accepts the flag inline on the Rate/Shop payload:
     * add {@code LandedCostRequestIndicator} + {@code CustomsValue} +
     * per-commodity {@code CustomsLineItems} and the response includes
     * {@code EstimatedDuties} / {@code EstimatedTaxes} / {@code
     * MerchandiseTotal} alongside the freight quote. Sprint 32.
     *
     * <p>The route to the endpoint reuses the Sprint 19 Rate/Shop path;
     * only the request body grows. Requires an international lane
     * (shipper country ≠ recipient country) — UPS rejects landed cost
     * requests on domestic shipments.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public LandedCostResult estimateLandedCost(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new LandedCostResult("UPS", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "UPS landed cost needs live credentials; the account is on a fallback token.",
                    null);
        }
        if (!isInternational(request)) {
            return new LandedCostResult("UPS", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    java.util.List.of(),
                    java.util.List.of("UPS landed cost is only supported for international shipments."),
                    "Not an international lane; UPS landed cost skipped.",
                    null);
        }
        try {
            Map<String, Object> shipment = buildRateShopShipment(request);
            appendLandedCostToShipment(shipment, request);
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> rateRequest = new LinkedHashMap<>();
            rateRequest.put("Request", Map.of(
                    "SubVersion", "2205",
                    "TransactionReference", Map.of("CustomerContext",
                            firstNonBlank(request.getReferenceNumber(), ""))));
            rateRequest.put("CustomerClassification", Map.of("Code", "00"));
            rateRequest.put("Shipment", shipment);
            body.put("RateRequest", rateRequest);

            String url = "/api/rating/" + carrierProperties.getUps().getApiVersion() + "/Rate";
            String baseUrl = isSandbox(environment)
                    ? carrierProperties.getUps().getSandboxUrl()
                    : carrierProperties.getUps().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseUpsLandedCostResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("UPS landed cost rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new LandedCostResult("UPS", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "UPS landed cost rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("UPS landed cost failed: {}", ex.getMessage());
            return new LandedCostResult("UPS", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "UPS landed cost call failed: " + ex.getMessage(),
                    null);
        }
    }

    /** Sprint 32 — bolt the Landed Cost blocks onto a Rate/Shop shipment map. */
    @SuppressWarnings("unchecked")
    private void appendLandedCostToShipment(Map<String, Object> shipment, ShipmentRequestDTO request) {
        Map<String, Object> ratingOptions = (Map<String, Object>) shipment.get("ShipmentRatingOptions");
        if (ratingOptions == null) {
            ratingOptions = new LinkedHashMap<>();
            shipment.put("ShipmentRatingOptions", ratingOptions);
        } else {
            ratingOptions = new LinkedHashMap<>(ratingOptions);
            shipment.put("ShipmentRatingOptions", ratingOptions);
        }
        ratingOptions.put("LandedCostRequestIndicator", "");

        String currency = firstNonBlank(request.getDeclaredValueCurrency(), "USD").toUpperCase();
        if (request.getDeclaredValue() != null) {
            shipment.put("InvoiceLineTotal", Map.of(
                    "CurrencyCode", currency,
                    "MonetaryValue", request.getDeclaredValue().toPlainString()));
        }

        if (request.getIntl() != null
                && request.getIntl().getCommodities() != null
                && !request.getIntl().getCommodities().isEmpty()) {
            java.util.List<Map<String, Object>> commodities = new java.util.ArrayList<>();
            for (com.multiship.backend.dto.CustomsCommodityDTO c : request.getIntl().getCommodities()) {
                Map<String, Object> line = new LinkedHashMap<>();
                if (StringUtils.hasText(c.getDescription())) line.put("Description", c.getDescription());
                if (StringUtils.hasText(c.getHsCode())) {
                    line.put("CommodityCode", c.getHsCode());
                }
                if (c.getUnitValue() != null) {
                    line.put("MonetaryValue", c.getUnitValue().toPlainString());
                }
                if (StringUtils.hasText(c.getCountryOfOrigin())) {
                    line.put("OriginCountryCode", c.getCountryOfOrigin());
                }
                if (c.getQuantity() != null) line.put("Quantity", String.valueOf(c.getQuantity()));
                commodities.add(line);
            }
            shipment.put("CustomsLineItem", commodities);
        }
    }

    /** True for lanes whose shipper country ≠ recipient country. */
    private static boolean isInternational(ShipmentRequestDTO r) {
        String from = r.getShipperCountryCode();
        String to = r.getRecipientCountryCode();
        return StringUtils.hasText(from) && StringUtils.hasText(to)
                && !from.trim().equalsIgnoreCase(to.trim());
    }

    /**
     * Parse a UPS Rate response with Landed Cost extensions.
     * Response shape (only fields we use):
     * <pre>
     * RateResponse.RatedShipment[0].
     *   TotalCharges.MonetaryValue          (freight)
     *   EstimatedDuties.TotalAmount.MonetaryValue
     *   EstimatedTaxes.TotalAmount.MonetaryValue
     *   TransportationCharges + ServiceOptionsCharges  (individually)
     *   EstimatedDuties.CurrencyCode
     * </pre>
     * Package-visible so tests can assert against canned JSON.
     */
    LandedCostResult parseUpsLandedCostResponse(String response) {
        try {
            JsonNode rated = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .at("/RateResponse/RatedShipment");
            if (rated.isArray()) rated = rated.get(0);
            if (rated.isMissingNode() || rated.isNull()) {
                return new LandedCostResult("UPS", "ERROR",
                        null, null, null, null, null, null,
                        java.util.List.of(), java.util.List.of(),
                        "UPS didn't return a RatedShipment.", response);
            }

            java.math.BigDecimal freight = readUpsMoney(rated.at("/TotalCharges/MonetaryValue"));
            java.math.BigDecimal duty = readUpsMoney(rated.at("/EstimatedDuties/TotalAmount/MonetaryValue"));
            java.math.BigDecimal tax = readUpsMoney(rated.at("/EstimatedTaxes/TotalAmount/MonetaryValue"));
            // UPS-15 — same fix as UPS-14 / FDX-D: return null instead of
            // silently mislabelling a missing-currency rate as USD.
            // Downstream LandedCostResult.currency is nullable; the UI
            // treats null as "no quote" surface rather than hiding it.
            String direct = rated.at("/EstimatedDuties/TotalAmount/CurrencyCode").asText(null);
            String totals = rated.at("/TotalCharges/CurrencyCode").asText(null);
            String currency = StringUtils.hasText(direct) ? direct
                    : (StringUtils.hasText(totals) ? totals : null);

            java.math.BigDecimal grand = zero();
            if (freight != null) grand = grand.add(freight);
            if (duty != null) grand = grand.add(duty);
            if (tax != null) grand = grand.add(tax);
            if (grand.signum() == 0) grand = null;

            return new LandedCostResult("UPS", "LIVE",
                    freight, duty, tax, null, grand, currency,
                    java.util.List.of(), java.util.List.of(),
                    "UPS returned a landed cost estimate.",
                    response);
        } catch (Exception ex) {
            return new LandedCostResult("UPS", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "UPS landed cost parse failed: " + ex.getMessage(),
                    response);
        }
    }

    private static java.math.BigDecimal readUpsMoney(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            try { return new java.math.BigDecimal(node.asText()); }
            catch (NumberFormatException ex) {
                log.debug("UPS readUpsMoney: non-numeric text '{}'", node.asText());
            }
        }
        return null;
    }

    private static java.math.BigDecimal zero() { return java.math.BigDecimal.ZERO; }

    /**
     * UPS Pickup Creation — {@code POST /api/shipments/v1/pickup} with a
     * Bearer token. Body carries the origin address, pickup date + window,
     * per-service piece counts, and the shipper account number for
     * billing. Response includes a PRN (Pickup Request Number) that
     * confirms the pickup and can be used to cancel later.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public PickupResult schedulePickup(PickupRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("UPS", null, null, null, null, "NOT_SUPPORTED",
                    "UPS pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        // FDX-C2 — pre-fix, buildUpsPickupRequest sent req.address().name()
        // (the shipper's CONTACT NAME) in the AccountNumber field. UPS
        // rejected that with a validation error every time. Now guarded at
        // the entry point + real account plumbed by FDX-C onto
        // PickupRequest.accountNumber().
        if (!StringUtils.hasText(request.accountNumber())) {
            return new PickupResult("UPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup needs the shipper account number that owns the labels; none was passed.",
                    null);
        }
        // UPS-12 — pickup address country is required so UPS's
        // DestinationCountryCode reflects the shipper's country instead of
        // silently defaulting to "US" (misroutes European shippers whose
        // pickup address has a blank countryCode). Address is @NotBlank
        // on PickupRequestDTO at the HTTP surface but the boundary throw
        // guarantees the same for direct-constructor callers.
        if (request.address() == null
                || !StringUtils.hasText(request.address().countryCode())) {
            return new PickupResult("UPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup needs the shipper pickup address country code "
                            + "so the DestinationCountryCode reflects the correct country; "
                            + "none was passed.",
                    null);
        }
        // UPS-18 + UPS-20 — pickup date + window are required. Pre-fix,
        // null pickupDate serialised as "" and null pickupWindowStart /
        // pickupWindowEnd serialised as "" for ReadyTime / CloseTime —
        // UPS rejects empty date/time fields with a validation error.
        // PickupRequestDTO marks pickupDate as @NotNull at the HTTP layer
        // (window fields are optional there but UPS requires them), so
        // this guard mainly covers direct-constructor callers.
        if (request.pickupDate() == null
                || request.pickupWindowStart() == null
                || request.pickupWindowEnd() == null) {
            return new PickupResult("UPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup requires pickupDate + pickupWindowStart + pickupWindowEnd. "
                            + "Missing any of these produces empty date/time fields on the wire "
                            + "which UPS rejects.",
                    null);
        }
        try {
            Map<String, Object> body = buildUpsPickupRequest(request);
            String baseUrl = isSandbox(environment)
                    ? carrierProperties.getUps().getSandboxUrl()
                    : carrierProperties.getUps().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .post()
                    .uri("/api/shipments/v1/pickup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseUpsPickupResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("UPS pickup rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new PickupResult("UPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("UPS pickup call failed: {}", ex.getMessage());
            return new PickupResult("UPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup call failed: " + ex.getMessage(), null);
        }
    }

    /** Build the UPS PickupCreationRequest body. */
    Map<String, Object> buildUpsPickupRequest(PickupRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> pcr = new LinkedHashMap<>();
        // UPS-21 — RatePickupIndicator=Y asks UPS to price the pickup and
        // return charges in the response; N schedules without rating.
        // Hardcoded to N because every pickup path in this app is fire-
        // and-forget (operator gets a confirmation number, not a bill).
        // Add per-account/per-request field if a future flow needs pickup
        // pricing back in the response.
        pcr.put("RatePickupIndicator", "N");
        // FDX-C2 — AccountNumber is the SHIPPER'S CARRIER ACCOUNT (not the
        // contact person's name; pre-fix used req.address().name() by
        // mistake). AccountCountryCode is the shipper's country from the
        // pickup address.
        pcr.put("Shipper", Map.of("Account", Map.of(
                "AccountNumber", firstNonBlank(req.accountNumber(), ""),
                "AccountCountryCode", firstNonBlank(
                        req.address() == null ? null : req.address().countryCode(), "US"))));
        Map<String, Object> pickupDateInfo = new LinkedHashMap<>();
        String date = req.pickupDate() == null ? "" : req.pickupDate().toString().replace("-", "");
        pickupDateInfo.put("CloseTime", formatUpsTime(req.pickupWindowEnd()));
        pickupDateInfo.put("ReadyTime", formatUpsTime(req.pickupWindowStart()));
        pickupDateInfo.put("PickupDate", date);
        pcr.put("PickupDateInfo", pickupDateInfo);

        AddressToValidate a = req.address();
        if (a != null) {
            Map<String, Object> address = new LinkedHashMap<>();
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (StringUtils.hasText(a.addressLine1())) lines.add(a.addressLine1());
            if (StringUtils.hasText(a.addressLine2())) lines.add(a.addressLine2());
            address.put("AddressLine", lines);
            address.put("City", firstNonBlank(a.city(), ""));
            address.put("StateProvince", firstNonBlank(a.state(), ""));
            address.put("PostalCode", firstNonBlank(a.postalCode(), ""));
            address.put("CountryCode", firstNonBlank(a.countryCode(), "US"));
            address.put("ResidentialIndicator", "N");
            Map<String, Object> contactInformation = new LinkedHashMap<>();
            contactInformation.put("CompanyName", firstNonBlank(req.contactName(), ""));
            contactInformation.put("ContactName", firstNonBlank(req.contactName(), ""));
            contactInformation.put("Phone", Map.of("Number",
                    firstNonBlank(req.contactPhone(), "")));
            pcr.put("PickupAddress", Map.of(
                    "CompanyName", firstNonBlank(req.contactName(), ""),
                    "ContactName", firstNonBlank(req.contactName(), ""),
                    "Address", address,
                    "Phone", Map.of("Number", firstNonBlank(req.contactPhone(), ""))));
        }

        // PickupPiece — total quantity + weight. FDX-F: ServiceCode
        // derived from operator's pickupServiceType selection. Pre-fix
        // hardcoded to "003" (Ground); Express-only shippers couldn't
        // schedule pickups. UPS uses "003" Ground / "007" Worldwide
        // Express — the two fleets that dispatch different drivers.
        Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("ServiceCode", mapUpsPickupServiceCode(req.pickupServiceType()));
        piece.put("Quantity", String.valueOf(Math.max(1, req.packageCount())));
        // UPS-12 — DestinationCountryCode reflects the shipper's pickup
        // address country (schedulePickup guards non-blank at entry).
        // Pre-fix defaulted to "US" via firstNonBlank(a.countryCode(), "US")
        // which misrouted European shippers whose address was somehow blank.
        // firstNonBlank retained as defence-in-depth for direct callers of
        // buildUpsPickupRequest in tests.
        piece.put("DestinationCountryCode", firstNonBlank(
                a == null ? null : a.countryCode(), ""));
        piece.put("ContainerCode", "01");
        pcr.put("PickupPiece", java.util.List.of(piece));

        String weightUnitCode = "KG".equalsIgnoreCase(req.weightUnit()) ? "KGS" : "LBS";
        pcr.put("TotalWeight", Map.of(
                "Weight", req.totalWeight() != null ? req.totalWeight().toPlainString() : "0",
                "UnitOfMeasurement", weightUnitCode));

        if (StringUtils.hasText(req.specialInstructions())) {
            pcr.put("SpecialInstruction", req.specialInstructions());
        }

        body.put("PickupCreationRequest", pcr);
        return body;
    }

    /** UPS wants HHmm (24h) times. Null → empty. */
    private static String formatUpsTime(java.time.LocalTime t) {
        if (t == null) return "";
        return String.format("%02d%02d", t.getHour(), t.getMinute());
    }

    /**
     * FDX-F — resolve UPS PickupPiece.ServiceCode from the operator's
     * pickupServiceType. Case-insensitive. Any unknown value falls to
     * "003" (Ground) — the pre-FDX-F default.
     *
     * <p>UPS ServiceCode is a two-tier fleet selector (Ground vs Air):
     * <ul>
     *   <li>{@code 003} — Ground pickup</li>
     *   <li>{@code 007} — UPS Worldwide Express (covers Next-Day Air /
     *       2nd-Day Air / Express Saver / Worldwide services)</li>
     * </ul>
     * The per-service split within Express (Next-Day vs 2nd-Day) is a
     * label-time concern, not a pickup-time one — one Express driver
     * collects all Express labels regardless of tier.
     */
    static String mapUpsPickupServiceCode(String pickupServiceType) {
        if (pickupServiceType == null) return "003";
        String v = pickupServiceType.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "EXPRESS", "INTERNATIONAL" -> "007";
            default -> "003";
        };
    }

    /**
     * Parse a UPS pickup response. Success carries
     * {@code PickupCreationResponse.PRN} — the confirmation number
     * customers use to cancel. Package-visible for tests.
     */
    PickupResult parseUpsPickupResponse(PickupRequest req, String response) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            JsonNode confirmation = root.at("/PickupCreationResponse");
            String prn = confirmation.path("PRN").asText(null);
            String status = StringUtils.hasText(prn) ? "SCHEDULED" : "ERROR";
            String message = StringUtils.hasText(prn)
                    ? "UPS confirmed pickup — PRN " + prn
                    : "UPS pickup response missing PRN.";
            return new PickupResult("UPS", prn,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    status, message, response);
        } catch (Exception ex) {
            return new PickupResult("UPS", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "UPS pickup parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * UPS End of Day — {@code POST /api/shipments/v1/endofday} with a
     * Bearer token. Body carries the list of tracking numbers to
     * manifest. Response includes a BOL (Bill of Lading) number and a
     * base64 manifest PDF the driver signs at pickup.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new CloseOutResult("UPS", null, null, null, 0, "NOT_SUPPORTED",
                    "UPS close-out needs live credentials; the account is on a fallback token.",
                    null);
        }
        java.util.List<String> tracking = request.trackingNumbers();
        if (tracking == null || tracking.isEmpty()) {
            return new CloseOutResult("UPS", null, null, null, 0, "ERROR",
                    "UPS close-out requires at least one tracking number.", null);
        }
        try {
            java.util.List<Map<String, Object>> shipments = new java.util.ArrayList<>();
            for (String t : tracking) shipments.add(Map.of("TrackingNumber", t));

            Map<String, Object> eodRequest = new LinkedHashMap<>();
            eodRequest.put("Request", Map.of(
                    "TransactionReference", Map.of("CustomerContext", "eod-" + java.util.UUID.randomUUID())));
            eodRequest.put("Shipments", shipments);

            Map<String, Object> body = Map.of("EndOfDayRequest", eodRequest);
            String baseUrl = isSandbox(environment)
                    ? carrierProperties.getUps().getSandboxUrl()
                    : carrierProperties.getUps().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .post()
                    .uri("/api/shipments/v1/endofday")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseUpsCloseOutResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("UPS end-of-day rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new CloseOutResult("UPS", null, null, null, tracking.size(), "ERROR",
                    "UPS end-of-day rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("UPS end-of-day call failed: {}", ex.getMessage());
            return new CloseOutResult("UPS", null, null, null, tracking.size(), "ERROR",
                    "UPS end-of-day call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a UPS End of Day response. Success = presence of
     * {@code EndOfDayResponse.BOLNumber}. The base64 PDF (when returned)
     * lives at {@code Manifest.ManifestImage.GraphicImage}.
     */
    CloseOutResult parseUpsCloseOutResponse(CloseOutRequest request, String response) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            JsonNode eod = root.at("/EndOfDayResponse");
            String bolNumber = eod.path("BOLNumber").asText(null);
            String pdfBase64 = eod.at("/Manifest/ManifestImage/GraphicImage").asText(null);
            int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
            String status = StringUtils.hasText(bolNumber) ? "MANIFESTED" : "ERROR";
            String message = StringUtils.hasText(bolNumber)
                    ? "UPS manifested " + count + " shipment(s) · BOL " + bolNumber
                    : "UPS end-of-day response missing BOLNumber.";
            return new CloseOutResult("UPS", bolNumber, null, pdfBase64, count,
                    status, message, response);
        } catch (Exception ex) {
            int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
            return new CloseOutResult("UPS", null, null, null, count, "ERROR",
                    "UPS end-of-day parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * UPS Track Webhook — HMAC-SHA256(body, secret) in the
     * {@code X-UPS-Signature} header, hex-encoded. UPS registers the
     * secret at webhook subscription time.
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload,
                                           java.util.Map<String, String> headers,
                                           String secret) {
        String provided = pickHeader(headers, "X-UPS-Signature");
        String expected = WebhookHmacUtil.hmacSha256Hex(rawPayload, secret);
        return provided != null && expected != null
                && WebhookHmacUtil.constantTimeEquals(provided, expected);
    }

    /**
     * Parse a UPS push-tracking webhook payload. UPS pushes:
     * <pre>
     * {
     *   "trackNumber": "1Z...",
     *   "localActivityDate": "20260726",
     *   "localActivityTime": "143000",
     *   "activityLocation": {"city": "Louisville", "stateProvince": "KY", "country": "US"},
     *   "activityStatus": {"code": "DL", "description": "Delivered", "type": "DL"}
     * }
     * </pre>
     */
    @Override
    public TrackingWebhookEvent parseWebhookEvent(String rawPayload,
                                                   java.util.Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(rawPayload).orElse("{}"));
            String tracking = root.path("trackNumber").asText(null);
            if (!StringUtils.hasText(tracking)) return null;
            LocalDateTime occurred = joinUpsDateTime(
                    root.path("localActivityDate").asText(null),
                    root.path("localActivityTime").asText(null));
            String location = buildUpsLocation(root.path("activityLocation"));
            String statusCode = root.at("/activityStatus/code").asText(null);
            String description = root.at("/activityStatus/description").asText("");
            String type = root.at("/activityStatus/type").asText(statusCode);
            boolean delivered = "DL".equalsIgnoreCase(statusCode)
                    || "DELIVERED".equalsIgnoreCase(description);
            return new TrackingWebhookEvent(tracking, type, statusCode, occurred,
                    location, delivered, description);
        } catch (Exception ex) {
            log.warn("UPS webhook parse failed: {}", ex.getMessage());
            return null;
        }
    }

    /** Case-insensitive header lookup — HTTP headers are case-agnostic. */
    private static String pickHeader(java.util.Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (var e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    @Override
    public CarrierConfiguration getConfiguration() {
        CarrierProperties.Ups ups = carrierProperties.getUps();
        return new CarrierConfiguration(
                CARRIER_CODE,
                getCarrierName(),
                ups.getApiBaseUrl(),
                ups.getAuthUrl(),
                ups.getApiVersion(),
                ups.getSandboxUrl(),
                ups.getShipmentPath(),
                ups.getTrackingPath(),
                ups.getTokenPath(),
                ups.getLogoUrl(),
                ups.getDocumentationUrl(),
                ups.getConnectionGuide(),
                ups.getDefaultServiceType(),
                ups.getDefaultPackageType(),
                ups.getLabelResponseOption(),
                carrierProperties.getDefaultEnvironment(),
                true
        );
    }

    /**
     * Full UPS Ship API 2205 shipment payload. Domestic shipments (no intl
     * block) skip {@code ShipmentServiceOptions.InternationalForms} and
     * {@code SoldTo}; international shipments get the paperless invoice +
     * importer-of-record blocks so the carrier accepts customs declarations
     * without printed paperwork.
     *
     * <p>Weight/dim units are passed through natively (UPS accepts KGS/LBS
     * and CM/IN with a unit-of-measurement hint), so a parcel entered in KG
     * lands at UPS as KG — no silent 2.2× reweigh surcharges.
     *
     * <p>Duty billing: SENDER pays freight + duties; RECIPIENT is the default
     * (Type 01 freight only, no Type 02 — UPS bills consignee); THIRD_PARTY
     * splits {@code BillShipper} (Type 01) and {@code BillThirdParty} (Type
     * 02 with the payer's account number). See UPS Ship API "Payment
     * Information" for the full type matrix.
     */
    private Map<String, Object> buildShipmentPayload(ShipmentRequestDTO request) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("Description", firstNonBlank(request.getSpecialInstructions(), "Shipment"));
        shipment.put("Shipper", buildParty(
                request.getShipperName(),
                request.getShipperPhone(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(),
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getAccountNumber(),
                request.getIntl() != null ? request.getIntl().getImporterTaxId() : null));
        // Recipient phone: prepend the country dial code when the DTO
        // carries one (Sprint 6). UPS wire format accepts "+44 20 ..." and
        // "4420..." both; we use the plus-prefixed form for readability.
        String recipientPhone = joinPhone(request.getRecipientPhoneCountryCode(), request.getRecipientPhone());
        Map<String, Object> shipTo = buildParty(
                request.getRecipientName(),
                recipientPhone,
                request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                request.getRecipientCity(), request.getRecipientState(),
                request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                null, null);
        // Line 3 (JP/CN/IN long addresses). UPS ShipTo.Address.AddressLine is
        // an array; append when non-blank so we don't emit an empty element.
        if (StringUtils.hasText(request.getRecipientAddressLine3())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
            if (address != null) {
                @SuppressWarnings("unchecked")
                java.util.List<String> lines = (java.util.List<String>) address.get("AddressLine");
                if (lines != null) lines.add(request.getRecipientAddressLine3());
            }
        }
        // ResidentialAddressIndicator is a UPS convention — the ELEMENT'S
        // PRESENCE signals residential, its value is ignored. Absence =
        // commercial (UPS default), which avoids surprise back-billing at
        // delivery when we know the recipient is a residence.
        if (Boolean.TRUE.equals(request.getRecipientResidential())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
            if (address != null) address.put("ResidentialAddressIndicator", "");
        }
        shipment.put("ShipTo", shipTo);
        shipment.put("PaymentInformation", buildPaymentInformation(request));
        shipment.put("Service", Map.of("Code", firstNonBlank(request.getServiceType(), "03")));

        // Sprint 28 — multi-package. effectivePackages() returns
        // ShipmentRequestDTO.packages when populated, else a single-
        // package synthetic list from the top-level fields.
        // Sprint 48 B11 — derive per-package declared value from CI
        // commodities' boxSeq. UPS's Package[i].PackageServiceOptions.
        // DeclaredValue is the ONLY declared-value field on the wire
        // (no shipment-level equivalent for carriage liability), so we
        // MUST populate each Package with its own value.
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> pkgList = request.effectivePackages();
        com.multiship.backend.util.DeclaredValueContextBuilder.DeclaredValueContext dvCtx =
                com.multiship.backend.util.DeclaredValueContextBuilder.build(
                        request.getIntl() != null ? request.getIntl().getCommodities() : null,
                        pkgList.size(),
                        pkgList,
                        firstNonBlank(request.getDeclaredValueCurrency(), "USD"),
                        request.getDeclaredValue());
        java.util.List<Map<String, Object>> packageBlocks = new java.util.ArrayList<>();
        for (int i = 0; i < pkgList.size(); i++) {
            packageBlocks.add(buildPackage(request, pkgList.get(i),
                    i < dvCtx.perPackage().size() ? dvCtx.perPackage().get(i) : null,
                    dvCtx.currency()));
        }
        shipment.put("Package", packageBlocks);

        // Sprint 25 — Print Return Label. UPS ReturnService.Code "8" =
        // "Print Return Label" (the paper-based variant; PDF returned to
        // us, we forward to the customer). Code "9" = Electronic Return
        // Label (UPS emails the label direct to the customer). We use 8
        // because our label PDF flow already handles operator delivery.
        if (Boolean.TRUE.equals(request.getIsReturn())) {
            shipment.put("ReturnService", Map.of("Code", "8"));
        }

        // International forms only when the request carries an intl block
        // that's ready (all required fields present).
        if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
            Map<String, Object> forms = buildInternationalForms(request);
            Map<String, Object> serviceOptions = new LinkedHashMap<>();
            serviceOptions.put("InternationalForms", forms);
            shipment.put("ShipmentServiceOptions", serviceOptions);
            // Importer of Record (SoldTo) is optional when it's the same as
            // ShipTo — SoldTo.Option = "01" tells UPS "consignee IS importer".
            // Only add a SoldTo block when the intl block names a different
            // importer identity.
            Map<String, Object> soldTo = buildSoldTo(request);
            if (soldTo != null) shipment.put("SoldTo", soldTo);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> shipmentRequest = new LinkedHashMap<>();
        shipmentRequest.put("Request", Map.of(
                "SubVersion", "2205",
                "RequestOption", "nonvalidate",
                "TransactionReference", Map.of(
                        "CustomerContext", firstNonBlank(request.getReferenceNumber(), ""))));
        shipmentRequest.put("Shipment", shipment);
        // UPS-4b — LabelImageFormat from the resolver (account default →
        // hardcoded GIF fallback). Pre-fix hardcoded to "GIF" so shippers
        // with ZPL / high-quality label printers got fuzzy rasterised
        // labels regardless of preference. Null-safe fallback to GIF
        // preserves back-compat for callers that don't populate the field.
        String labelFormat = StringUtils.hasText(request.getLabelImageFormat())
                ? request.getLabelImageFormat().trim().toUpperCase(Locale.ROOT)
                : "GIF";
        // UPS-5 — HTTPUserAgent is UPS's Label-Spec-side identifier for the
        // label-generating tool. Historical "Mozilla/4.5" was legacy cargo
        // from the pre-2010 UPS integration guides. Switched to a
        // descriptive value so support tickets can correlate labels to this
        // application. UPS accepts any non-empty string.
        shipmentRequest.put("LabelSpecification", Map.of(
                "LabelImageFormat", Map.of("Code", labelFormat),
                "HTTPUserAgent", "multiship"));
        payload.put("ShipmentRequest", shipmentRequest);
        return payload;
    }

    /**
     * UPS HazMatPackageInformation block — package-scoped hazmat wire
     * format for the Ship API. UPS scopes hazmat AT THE PACKAGE (each
     * package can declare its own chemicals), so this hangs off
     * {@code Package}, not {@code Shipment}.
     *
     * <p>Layout:
     * <pre>
     * HazMatPackageInformation {
     *   AllPackedInOneIndicator     (presence-only; empty string when all commodities in one package)
     *   OverPackedIndicator         (presence-only; empty string when using an overpack)
     *   HazMatChemicalRecord[]      (one per commodity):
     *     ChemicalRecordIdentifier  (sequence)
     *     ClassDivisionNumber       (hazard class, "9" or "4.1")
     *     IDNumber                  (UN number, "UN3480")
     *     TransportationMode        (Ground | Air | Vessel — inferred from RegulationSet)
     *     RegulationSet             (IATA | ADR | DOT)
     *     EmergencyPhone            (24/7)
     *     EmergencyContact          (24/7 name)
     *     ProperShippingName
     *     PackagingGroupType        (I | II | III)
     *     Quantity + UOM            (mass / volume per package)
     *     PackagingType             (fixed "CTN" for cartons — future work: preset lookup)
     *     PackagingTypeQuantity     (package count for this commodity)
     * }
     * </pre>
     *
     * <p>Caller is expected to have populated the DG block AND to have
     * run {@link com.multiship.backend.service.DangerousGoodsValidator} —
     * this method assumes the fields are present.
     */
    private Map<String, Object> buildUpsHazMatPackage(ShipmentRequestDTO request) {
        com.multiship.backend.dto.DangerousGoodsBlockDTO dg = request.getDangerousGoods();
        Map<String, Object> hazmat = new LinkedHashMap<>();
        // Presence-only flags; UPS wants an empty string when applicable.
        if (dg.getCommodities().size() > 1) {
            hazmat.put("AllPackedInOneIndicator", "");
        }

        String regulation = dg.getRegulationSet() == null ? "IATA"
                : dg.getRegulationSet().trim().toUpperCase(Locale.ROOT);
        String transportMode = mapTransportMode(regulation);

        java.util.List<Map<String, Object>> records = new java.util.ArrayList<>();
        int seq = 1;
        for (com.multiship.backend.dto.DangerousCommodityDTO c : dg.getCommodities()) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("ChemicalRecordIdentifier", String.valueOf(seq++));
            if (StringUtils.hasText(c.getHazardClass())) {
                record.put("ClassDivisionNumber", c.getHazardClass().trim());
            }
            if (StringUtils.hasText(c.getUnNumber())) {
                record.put("IDNumber", c.getUnNumber().trim().toUpperCase(Locale.ROOT));
            }
            record.put("TransportationMode", transportMode);
            record.put("RegulationSet", regulation);
            record.put("EmergencyPhone", firstNonBlank(dg.getEmergencyContactPhone(), ""));
            record.put("EmergencyContact", firstNonBlank(dg.getEmergencyContactName(), ""));
            if (StringUtils.hasText(c.getProperShippingName())) {
                record.put("ProperShippingName", c.getProperShippingName().trim());
            }
            if (StringUtils.hasText(c.getPackingGroup())) {
                record.put("PackagingGroupType", c.getPackingGroup().trim().toUpperCase(Locale.ROOT));
            }
            if (c.getQuantity() != null) {
                record.put("Quantity", c.getQuantity().toPlainString());
            }
            if (StringUtils.hasText(c.getQuantityUnit())) {
                record.put("UOM", c.getQuantityUnit().trim().toUpperCase(Locale.ROOT));
            }
            record.put("PackagingType", "CTN");
            record.put("PackagingTypeQuantity",
                    String.valueOf(c.getPackageCount() == null || c.getPackageCount() < 1 ? 1 : c.getPackageCount()));
            records.add(record);
        }
        hazmat.put("HazMatChemicalRecord", records);
        return hazmat;
    }

    /**
     * Map our regulation set enum to UPS's transportation mode string:
     *   IATA → Air, ADR → Ground, DOT → Ground.
     * Vessel mode is out of scope — no maritime carriers in our matrix.
     */
    private static String mapTransportMode(String regulationSet) {
        if (regulationSet == null) return "Ground";
        return switch (regulationSet.trim().toUpperCase(Locale.ROOT)) {
            case "IATA" -> "Air";
            case "ADR", "DOT" -> "Ground";
            default -> "Ground";
        };
    }

    /**
     * UPS Party (Shipper / ShipTo). AttentionName defaults to Name when
     * blank; UPS rejects Party blocks without an AttentionName on international
     * shipments even when it duplicates Name. Tax id is only relevant on
     * Shipper (EORI/VAT for the exporter).
     */
    private Map<String, Object> buildParty(String name, String phone,
                                            String line1, String line2,
                                            String city, String state,
                                            String postal, String country,
                                            String shipperNumber, String taxId) {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("Name", firstNonBlank(name, ""));
        party.put("AttentionName", firstNonBlank(name, ""));
        if (StringUtils.hasText(shipperNumber)) party.put("ShipperNumber", shipperNumber);
        if (StringUtils.hasText(taxId)) party.put("TaxIdentificationNumber", taxId);
        if (StringUtils.hasText(phone)) {
            party.put("Phone", Map.of("Number", phone));
        }
        Map<String, Object> address = new LinkedHashMap<>();
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (StringUtils.hasText(line1)) lines.add(line1);
        if (StringUtils.hasText(line2)) lines.add(line2);
        address.put("AddressLine", lines);
        address.put("City", firstNonBlank(city, ""));
        address.put("StateProvinceCode", firstNonBlank(state, ""));
        address.put("PostalCode", firstNonBlank(postal, ""));
        address.put("CountryCode", firstNonBlank(country, "US"));
        party.put("Address", address);
        return party;
    }

    /**
     * UPS Package block with unit-of-measurement hints so 1.5 KG in the
     * request lands at UPS as 1.5 KG, not 1.5 LB. Sprint 28 — takes a
     * {@link com.multiship.backend.dto.PackageDetailDTO} so multi-package
     * shipments can supply per-box weight, dims, packaging type. The DG
     * block still lives at the shipment level; the same hazmat wire is
     * duplicated onto every package (UPS wants it per-package).
     */
    /** Backwards-compat overload — no per-pkg declared value. Used by tests. */
    private Map<String, Object> buildPackage(ShipmentRequestDTO request,
                                              com.multiship.backend.dto.PackageDetailDTO p) {
        return buildPackage(request, p, null, null);
    }

    private Map<String, Object> buildPackage(ShipmentRequestDTO request,
                                              com.multiship.backend.dto.PackageDetailDTO p,
                                              java.math.BigDecimal pieceDeclaredValue,
                                              String currency) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("Description", firstNonBlank(p.getDescription(),
                firstNonBlank(request.getSpecialInstructions(), "Package")));
        // UPS-3 — PackagingType.Code "02" = Customer Supplied Package
        // (operator's own box). Sensible default when the resolver /
        // PackageMath doesn't pick a UPS-specific packaging type
        // (Letter/Pak/Tube/etc); covers the common "brown box" case.
        // Documented here so future audits don't re-flag the hardcoded
        // "02" as a silent-fallback bug.
        pkg.put("PackagingType", Map.of(
                "Code", firstNonBlank(
                        firstNonBlank(p.getPackageType(), request.getPackageType()),
                        "02")));

        String weightUnitCode = "KG".equalsIgnoreCase(
                firstNonBlank(p.getWeightUnit(), request.getWeightUnit())) ? "KGS" : "LBS";
        Map<String, Object> weight = new LinkedHashMap<>();
        weight.put("UnitOfMeasurement", Map.of("Code", weightUnitCode));
        weight.put("Weight", p.getWeight() != null ? p.getWeight().toPlainString() : "0");
        pkg.put("PackageWeight", weight);

        if (p.getLength() != null || p.getWidth() != null || p.getHeight() != null) {
            String dimUnitCode = "CM".equalsIgnoreCase(
                    firstNonBlank(p.getDimUnit(), request.getDimUnit())) ? "CM" : "IN";
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("UnitOfMeasurement", Map.of("Code", dimUnitCode));
            dims.put("Length", p.getLength() != null ? p.getLength().toPlainString() : "0");
            dims.put("Width", p.getWidth() != null ? p.getWidth().toPlainString() : "0");
            dims.put("Height", p.getHeight() != null ? p.getHeight().toPlainString() : "0");
            pkg.put("Dimensions", dims);
        }

        // Sprint 26 — HazMatPackageInformation. UPS scopes hazmat at the
        // Package level (each package can declare its own commodities),
        // so the wire block hangs off Package here, not off Shipment.
        // In multi-package shipments each package repeats the DG block —
        // per-package hazmat overrides land in a future sprint.
        if (request.getDangerousGoods() != null
                && request.getDangerousGoods().isReadyForCarrier()) {
            pkg.put("HazMatPackageInformation", buildUpsHazMatPackage(request));
        }

        // Sprint 35 — signature + insurance are per-package on UPS
        // (PackageServiceOptions block).
        // Sprint 48 B11 — per-package DeclaredValue drives both carriage
        // liability AND (when not overridden by explicit InsuredValue)
        // the loss-claim ceiling. Passed in from the outer per-package
        // loop; falls back to the shipment-level insuredValue when the
        // per-pkg value is null / zero (preserves legacy insurance behavior).
        Map<String, Object> serviceOptions = buildUpsPackageServiceOptions(request,
                pieceDeclaredValue, currency);
        if (!serviceOptions.isEmpty()) {
            pkg.put("PackageServiceOptions", serviceOptions);
        }
        return pkg;
    }

    /**
     * Sprint 35 — UPS PackageServiceOptions block.
     *
     * <p>DeliveryConfirmation.DCISType:
     * <ul>
     *   <li>{@code 1} — Delivery Confirmation (no signature).</li>
     *   <li>{@code 2} — Signature Required.</li>
     *   <li>{@code 3} — Adult Signature Required.</li>
     * </ul>
     *
     * <p>DeclaredValue insures the package for its full value; UPS
     * refunds the declared amount on loss/damage claims. Free tier is
     * $100; anything above that is billed.
     */
    /** Backwards-compat overload — no per-pkg declared value. */
    private Map<String, Object> buildUpsPackageServiceOptions(ShipmentRequestDTO request) {
        return buildUpsPackageServiceOptions(request, null, null);
    }

    private Map<String, Object> buildUpsPackageServiceOptions(ShipmentRequestDTO request,
                                                              java.math.BigDecimal pieceDeclaredValue,
                                                              String currency) {
        Map<String, Object> options = new LinkedHashMap<>();
        String sig = normaliseSignatureOption(request.getSignatureOption());
        if (sig != null) {
            String dcisType = switch (sig) {
                case "INDIRECT" -> "2";
                case "DIRECT" -> "2";
                case "ADULT" -> "3";
                default -> null;
            };
            if (dcisType != null) {
                options.put("DeliveryConfirmation", Map.of("DCISType", dcisType));
            }
        }
        // Sprint 48 B11 — resolution chain for DeclaredValue:
        //   1. per-pkg value from CI-derived context (wins when present)
        //   2. shipment-level insuredValue (legacy insurance-only orders)
        // Note: UPS's DeclaredValue drives BOTH carriage liability and
        // customs — same wire field. The customs invoice total lives
        // separately under InternationalForms.InvoiceLineTotal.
        java.math.BigDecimal effectiveDeclared = null;
        String effectiveCurrency = null;
        if (pieceDeclaredValue != null && pieceDeclaredValue.signum() > 0) {
            effectiveDeclared = pieceDeclaredValue;
            effectiveCurrency = firstNonBlank(currency,
                    firstNonBlank(request.getDeclaredValueCurrency(), "USD")).toUpperCase();
        } else if (request.getInsuredValue() != null && request.getInsuredValue().signum() > 0) {
            effectiveDeclared = request.getInsuredValue();
            effectiveCurrency = firstNonBlank(
                    firstNonBlank(request.getInsuredValueCurrency(), request.getDeclaredValueCurrency()),
                    "USD").toUpperCase();
        }
        if (effectiveDeclared != null) {
            options.put("DeclaredValue", Map.of(
                    "CurrencyCode", effectiveCurrency,
                    "MonetaryValue", effectiveDeclared.toPlainString()));
        }
        return options;
    }

    /** Normalise the DTO's freeform signatureOption to the enum values
     *  the connectors switch on. Blank / unknown → null. */
    private static String normaliseSignatureOption(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty() || "NONE".equals(v)) return null;
        return switch (v) {
            case "INDIRECT", "DIRECT", "ADULT" -> v;
            default -> null;
        };
    }

    /**
     * UPS PaymentInformation.ShipmentCharge[] — Type 01 = Transportation,
     * Type 02 = Duties+Taxes. See UPS Ship API "Payment Information".
     * <ul>
     *   <li>SENDER (default / no intl / DDP): only Type 01 (BillShipper).
     *       Duties fall to consignee unless we add Type 02 → covered below.</li>
     *   <li>DDP + SENDER: adds a Type 02 BillShipper block so UPS bills the
     *       shipper's account for duties too.</li>
     *   <li>THIRD_PARTY: Type 01 BillShipper (freight) + Type 02
     *       BillThirdParty (duties) with the payer's account number.</li>
     *   <li>RECIPIENT / DAP / DDU: Type 01 only — UPS bills consignee for
     *       duties per DAP default.</li>
     * </ul>
     */
    private Map<String, Object> buildPaymentInformation(ShipmentRequestDTO request) {
        String shipperAccount = firstNonBlank(request.getAccountNumber(), "");
        java.util.List<Map<String, Object>> charges = new java.util.ArrayList<>();
        // Freight always billed to shipper account.
        charges.add(Map.of(
                "Type", "01",
                "BillShipper", Map.of("AccountNumber", shipperAccount)));

        if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
            // F6-C — clearanceOption (per-account, resolved by
            // ShipmentDefaultsResolver from CarrierAccountRef) wins over
            // dutyBillTo (per-customs-profile). Falls back to the profile
            // value when the account has no explicit clearance set.
            String dutyBillTo = firstNonBlank(
                    request.getIntl().getClearanceOption(),
                    request.getIntl().getDutyBillTo());
            String dutyAccount = request.getIntl().getDutyAccount();
            if ("SENDER".equalsIgnoreCase(dutyBillTo)
                    || "DDP".equalsIgnoreCase(request.getIntl().getIncoterms())) {
                charges.add(Map.of(
                        "Type", "02",
                        "BillShipper", Map.of("AccountNumber", shipperAccount)));
            } else if ("THIRD_PARTY".equalsIgnoreCase(dutyBillTo) && StringUtils.hasText(dutyAccount)) {
                charges.add(Map.of(
                        "Type", "02",
                        "BillThirdParty", Map.of(
                                "AccountNumber", dutyAccount,
                                "Address", Map.of(
                                        "PostalCode", "",
                                        "CountryCode", firstNonBlank(request.getIntl().getImporterCountry(), "US")))));
            }
            // RECIPIENT / DDU / DAP: no Type 02 — UPS bills consignee by default.
        }

        Map<String, Object> paymentInformation = new LinkedHashMap<>();
        paymentInformation.put("ShipmentCharge", charges);
        return paymentInformation;
    }

    /**
     * UPS InternationalForms block. FormType "01" = Commercial Invoice —
     * the standard for cross-border commercial shipments. Enable UPS Paperless
     * Invoice on the account to have UPS transmit this electronically instead
     * of requiring printed copies attached to the parcel.
     */
    private Map<String, Object> buildInternationalForms(ShipmentRequestDTO request) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        Map<String, Object> forms = new LinkedHashMap<>();
        forms.put("FormType", "01");
        forms.put("InvoiceNumber", firstNonBlank(request.getReferenceNumber(), ""));
        // F6-E — invoice date follows the shipper's local calendar day.
        // Pre-F6-E UTC produced a 1-day-earlier date for shippers printing
        // in APAC before 08:00 local, which UPS's paperless invoice service
        // silently accepts but the destination customs office may reject.
        forms.put("InvoiceDate", com.multiship.backend.util.LabelDates.today(request.getShipperTimezone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        forms.put("PurchaseOrderNumber", firstNonBlank(request.getReferenceNumber(), ""));
        forms.put("TermsOfShipment", firstNonBlank(intl.getIncoterms(), "DAP").toUpperCase());
        // UPS-9 — map our 8-value SHIPPING_PURPOSE_ENUM to UPS's 7-value
        // ReasonForExport enum. Pre-fix, MERCHANDISE / PERSONAL_USE /
        // REPAIR_AND_RETURN silently reached UPS as unsupported strings —
        // UPS either rejected the request or defaulted to an unknown value
        // on the paperless invoice. Same fix shape as FDX-D on FedEx.
        forms.put("ReasonForExport", mapUpsReasonForExport(intl.getReasonForExport()));
        forms.put("CurrencyCode", firstNonBlank(intl.getCustomsCurrency(), "USD").toUpperCase());

        String weightUnitCode = "KG".equalsIgnoreCase(intl.getWeightUnit()) ? "KGS" : "LBS";
        java.util.List<Map<String, Object>> products = new java.util.ArrayList<>();
        for (com.multiship.backend.dto.CustomsCommodityDTO c : intl.getCommodities()) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("Description", firstNonBlank(c.getDescription(), ""));
            if (StringUtils.hasText(c.getHsCode())) product.put("CommodityCode", c.getHsCode());
            if (StringUtils.hasText(c.getSku())) product.put("PartNumber", c.getSku());
            product.put("OriginCountryCode", firstNonBlank(c.getCountryOfOrigin(), ""));
            product.put("Unit", Map.of(
                    "Number", c.getQuantity() != null ? c.getQuantity().toString() : "1",
                    "Value", c.getUnitValue() != null ? c.getUnitValue().toPlainString() : "0",
                    "UnitOfMeasurement", Map.of("Code", "EA")));
            if (c.getUnitWeight() != null) {
                product.put("ProductWeight", Map.of(
                        "UnitOfMeasurement", Map.of("Code", weightUnitCode),
                        "Weight", c.getUnitWeight().toPlainString()));
            }
            products.add(product);
        }
        forms.put("Product", products);
        return forms;
    }

    /**
     * UPS SoldTo (Importer of Record) — added only when the intl block names
     * a different importer than the consignee. When the importer is the
     * consignee UPS accepts the shipment without SoldTo (implicit).
     */
    private Map<String, Object> buildSoldTo(ShipmentRequestDTO request) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null) return null;
        boolean hasImporterIdentity = StringUtils.hasText(intl.getImporterName())
                || StringUtils.hasText(intl.getImporterCompany())
                || StringUtils.hasText(intl.getImporterAddressLine1());
        if (!hasImporterIdentity) return null;

        Map<String, Object> soldTo = new LinkedHashMap<>();
        String name = firstNonBlank(intl.getImporterCompany(), intl.getImporterName(), "");
        soldTo.put("Option", "02"); // 02 = importer differs from consignee
        soldTo.put("Name", name);
        soldTo.put("AttentionName", firstNonBlank(intl.getImporterContact(), intl.getImporterName(), name));
        if (StringUtils.hasText(intl.getImporterTaxId())) {
            soldTo.put("TaxIdentificationNumber", intl.getImporterTaxId());
        }
        if (StringUtils.hasText(intl.getImporterPhone())) {
            soldTo.put("Phone", Map.of("Number", intl.getImporterPhone()));
        }
        Map<String, Object> addr = new LinkedHashMap<>();
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (StringUtils.hasText(intl.getImporterAddressLine1())) lines.add(intl.getImporterAddressLine1());
        if (StringUtils.hasText(intl.getImporterAddressLine2())) lines.add(intl.getImporterAddressLine2());
        addr.put("AddressLine", lines);
        addr.put("City", firstNonBlank(intl.getImporterCity(), ""));
        addr.put("StateProvinceCode", firstNonBlank(intl.getImporterState(), ""));
        addr.put("PostalCode", firstNonBlank(intl.getImporterPostcode(), ""));
        addr.put("CountryCode", firstNonBlank(intl.getImporterCountry(), ""));
        soldTo.put("Address", addr);
        return soldTo;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }

    /**
     * UPS-9 — map our 8-value {@link com.multiship.backend.service.ShipmentDefaultsResolver#SHIPPING_PURPOSE_ENUM}
     * to UPS's ReasonForExport enum. UPS accepts:
     * SALE / GIFT / SAMPLE / RETURN / REPAIR / INTERCOMPANYDATA / DOCUMENTS.
     *
     * <p>Mapping rationale (mirrors FDX-D on FedEx):
     * <ul>
     *   <li>SALE, MERCHANDISE → SALE (both are commercial sale from UPS's perspective)</li>
     *   <li>GIFT → GIFT</li>
     *   <li>SAMPLE → SAMPLE</li>
     *   <li>PERSONAL_USE → SAMPLE (closest match — non-commercial personal use; UPS has no
     *       PERSONAL_EFFECTS category, and SAMPLE is the standard non-commercial fallback)</li>
     *   <li>RETURN → RETURN</li>
     *   <li>REPAIR, REPAIR_AND_RETURN → REPAIR</li>
     *   <li>DOCUMENTS → DOCUMENTS</li>
     *   <li>null / unknown → SALE + log.warn (matches pre-UPS-9 default; drift catcher for
     *       future resolver enum additions).</li>
     * </ul>
     */
    static String mapUpsReasonForExport(String reason) {
        if (reason == null) return "SALE";
        String v = reason.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "SALE", "MERCHANDISE" -> "SALE";
            case "GIFT" -> "GIFT";
            case "SAMPLE", "PERSONAL_USE" -> "SAMPLE";
            case "RETURN" -> "RETURN";
            case "REPAIR", "REPAIR_AND_RETURN" -> "REPAIR";
            case "DOCUMENTS" -> "DOCUMENTS";
            default -> {
                log.warn("UPS mapUpsReasonForExport: unrecognised reason '{}' — defaulting to SALE. "
                        + "Add an explicit mapping if this is a real resolver value.", v);
                yield "SALE";
            }
        };
    }

    /**
     * Prepend a country dial code to a phone number when both are non-blank.
     * Idempotent — if the number already starts with "+" or the dial code,
     * pass it through unchanged so we don't double-prefix.
     */
    static String joinPhone(String countryCode, String phone) {
        if (phone == null || phone.trim().isEmpty()) return "";
        String p = phone.trim();
        if (countryCode == null || countryCode.trim().isEmpty()) return p;
        String code = countryCode.trim().replaceFirst("^\\+", "");
        if (p.startsWith("+") || p.startsWith(code) || p.startsWith("00" + code)) return p;
        return "+" + code + " " + p;
    }

    private ShipmentResult parseShipmentResult(String response) throws Exception {
        JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));

        // Real UPS Ship API response nests under ShipmentResponse.ShipmentResults.
        // Legacy / stub shape had flat top-level fields — fall back to that when
        // the nested tree isn't there so existing tests keep working.
        JsonNode results = root.at("/ShipmentResponse/ShipmentResults");
        boolean realShape = !results.isMissingNode() && results.isObject();

        String trackingNumber;
        BigDecimal shippingCost;
        String labelUrl;
        String labelPdf;
        JsonNode packageResults;

        if (realShape) {
            // Master shipment ID — the customer-facing identity that ties
            // all pieces together.
            trackingNumber = results.path("ShipmentIdentificationNumber").asText(null);
            packageResults = results.path("PackageResults");
            // Prefer the shipment-level total; else piece 1's base charge.
            JsonNode totalCharge = results.path("ShipmentCharges").path("TotalCharges").path("MonetaryValue");
            shippingCost = parseUpsMonetary(totalCharge);
            // Top-level label pointers mirror piece 1 (matches how the master
            // relates to piece 1 in the FedEx parser).
            JsonNode piece0 = packageResults.isArray() && packageResults.size() > 0
                    ? packageResults.get(0) : null;
            labelUrl = piece0 == null ? null : piece0.path("ShippingLabel").path("GraphicImage").asText(null);
            labelPdf = labelUrl;
        } else {
            // Legacy flat shape (tests + fallback):
            trackingNumber = root.path("trackingNumber").asText(null);
            labelUrl = root.path("labelUrl").asText(null);
            labelPdf = root.path("labelPdf").asText(null);
            shippingCost = root.path("shippingCost").isNumber() ? root.path("shippingCost").decimalValue() : null;
            packageResults = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }

        LocalDateTime estimatedDelivery = parseDateTime(root.path("estimatedDelivery").asText(null));
        String trackingUrl = StringUtils.hasText(trackingNumber)
                ? "https://www.ups.com/track?tracknum=" + trackingNumber : null;

        // Per-piece rows — one PackageTracking per PackageResults[] entry.
        java.util.List<PackageTracking> packages = new java.util.ArrayList<>();
        if (packageResults.isArray()) {
            for (int i = 0; i < packageResults.size(); i++) {
                JsonNode pkg = packageResults.get(i);
                String pcTrack = pkg.path("TrackingNumber").asText(null);
                if (!StringUtils.hasText(pcTrack)) continue;
                String pcLabel = pkg.path("ShippingLabel").path("GraphicImage").asText(null);
                BigDecimal pcCharge = parseUpsMonetary(pkg.path("BaseServiceCharge").path("MonetaryValue"));
                packages.add(new PackageTracking(i + 1, pcTrack,
                        "https://www.ups.com/track?tracknum=" + pcTrack,
                        pcLabel, pcLabel, pcCharge));
            }
        }

        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelPdf,
                shippingCost, estimatedDelivery, response, packages);
    }

    /** UPS monetary values arrive as JSON strings ("12.34"), not numbers. */
    private static BigDecimal parseUpsMonetary(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        String text = node.asText(null);
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            log.debug("UPS parseUpsMonetary: non-numeric text '{}'", text);
            return null;
        }
    }

    // FDX-A — buildFallbackShipmentResult removed. It was dead code (no
    // caller) and produced a synthetic 1Z-prefixed tracking + labels.local
    // URL that would have masked real UPS errors if ever wired in. Kept in
    // git history for reference.

/**
     * UPS Rate Shop — POST {@code /api/rating/{version}/Shop} with a Bearer
     * token returns rates for EVERY available service on the lane in a single
     * call (versus {@code /Rate} which prices one specific service). Perfect
     * for rate shopping.
     *
     * <p>Response shape (only fields we care about):
     * <pre>
     * RateResponse.RatedShipment[]  → one entry per service level
     *   .Service.{Code, Description}
     *   .TotalCharges.{MonetaryValue, CurrencyCode}
     *   .NegotiatedRateCharges.TotalCharge.{MonetaryValue, CurrencyCode}   [account rate — prefer over TotalCharges]
     *   .GuaranteedDelivery.{BusinessDaysInTransit, DeliveryByTime}
     * </pre>
     *
     * <p>{@code NegotiatedRateCharges} is only returned when the account has
     * negotiated rates AND the {@code CustomerClassification.Code} field
     * requests them; we always ask for classification "00" (rates as
     * negotiated) so account rates come back when they exist.
     *
     * <p>Reuses the shipment payload builder for Shipper / ShipTo / Package
     * so the rate quote mirrors what the label would actually charge — same
     * residential flag, same units, same address block.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to an empty list —
     * same auth-degraded convention Sprint 12 established for tracking and
     * Sprint 18 for FedEx rating.
     */
    @Override
    public java.util.List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return java.util.List.of();
        }
        // FDX-B2 — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream in buildRateShopShipment (UPS envelope
        // has firstNonBlank(country, "US") at lines 1050, 1068, 1087), so an
        // intl rate-shop with a blank recipient country returned believable
        // US-domestic quotes and the operator shipped anyway. Same F7 guard.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "UPS rate-shop requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before rate-shopping — "
                            + "quotes without a destination silently fall to US-domestic.");
        }
        try {
            Map<String, Object> shipment = buildRateShopShipment(request);
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> rateRequest = new LinkedHashMap<>();
            rateRequest.put("Request", Map.of(
                    "SubVersion", "2205",
                    "TransactionReference", Map.of("CustomerContext",
                            firstNonBlank(request.getReferenceNumber(), ""))));
            rateRequest.put("CustomerClassification", Map.of("Code", "00"));
            rateRequest.put("Shipment", shipment);
            body.put("RateRequest", rateRequest);

            String url = "/api/rating/" + carrierProperties.getUps().getApiVersion() + "/Shop";
            String baseUrl = isSandbox(environment)
                    ? carrierProperties.getUps().getSandboxUrl()
                    : carrierProperties.getUps().getApiBaseUrl();
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl).build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("transId", java.util.UUID.randomUUID().toString())
                    .header("transactionSrc", "multiship")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseUpsRateResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("UPS rate shop rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return java.util.List.of();
        } catch (Exception ex) {
            log.warn("UPS rate shop failed; returning empty rate list. Reason: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Minimal Shipment block for the Rate API. We reuse the full shipment
     * payload builder (buildParty, buildPackage) so the rate quote is
     * against the same envelope as the label would be — no drift between
     * "what UPS quoted" and "what UPS billed".
     */
    private Map<String, Object> buildRateShopShipment(ShipmentRequestDTO request) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("Shipper", buildParty(
                request.getShipperName(), request.getShipperPhone(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(),
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getAccountNumber(), null));
        String recipientPhone = joinPhone(request.getRecipientPhoneCountryCode(), request.getRecipientPhone());
        Map<String, Object> shipTo = buildParty(
                request.getRecipientName(), recipientPhone,
                request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                request.getRecipientCity(), request.getRecipientState(),
                request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                null, null);
        if (Boolean.TRUE.equals(request.getRecipientResidential())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
            if (address != null) address.put("ResidentialAddressIndicator", "");
        }
        shipment.put("ShipTo", shipTo);
        shipment.put("ShipmentRatingOptions", Map.of("NegotiatedRatesIndicator", ""));
        // Rate-shop against every package the caller supplied so the
        // returned rate matches what the actual label would cost.
        java.util.List<Map<String, Object>> packages = new java.util.ArrayList<>();
        for (com.multiship.backend.dto.PackageDetailDTO p : request.effectivePackages()) {
            packages.add(buildPackage(request, p));
        }
        shipment.put("Package", packages);
        return shipment;
    }

    /**
     * Parse the UPS Rate response into carrier-neutral RateOptions.
     * Package-visible so tests can assert against canned response JSON.
     */
    java.util.List<RateOption> parseUpsRateResponse(String response) {
        try {
            JsonNode rated = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .at("/RateResponse/RatedShipment");
            if (rated.isMissingNode()) return java.util.List.of();
            // UPS returns a single object OR an array depending on whether
            // one or multiple services matched; normalise to iterable.
            Iterable<JsonNode> entries = rated.isArray()
                    ? rated
                    : java.util.List.of(rated);

            java.util.List<RateOption> out = new java.util.ArrayList<>();
            for (JsonNode entry : entries) {
                String serviceCode = entry.at("/Service/Code").asText(null);
                if (serviceCode == null || serviceCode.isEmpty()) continue;
                String serviceName = entry.at("/Service/Description").asText(null);
                if (serviceName == null || serviceName.isEmpty()) {
                    // UPS often omits Description; fall back to the service
                    // matrix label we already ship (via serviceCode → name).
                    serviceName = upsServiceName(serviceCode);
                }

                BigDecimal amount = readUpsAmount(entry);
                if (amount == null) continue;
                String currency = readUpsCurrency(entry);

                Integer transitDays = null;
                String txt = entry.at("/GuaranteedDelivery/BusinessDaysInTransit").asText(null);
                if (StringUtils.hasText(txt)) {
                    try { transitDays = Integer.parseInt(txt.trim()); }
                    catch (NumberFormatException ex) {
                        log.debug("UPS rate: non-numeric BusinessDaysInTransit '{}'", txt);
                    }
                }
                LocalDateTime estimatedDelivery = parseDateTime(
                        entry.at("/GuaranteedDelivery/DeliveryByTime").asText(null));

                out.add(new RateOption("UPS", serviceCode, serviceName, amount, currency,
                        estimatedDelivery, transitDays));
            }
            return java.util.List.copyOf(out);
        } catch (Exception ex) {
            log.warn("UPS rate response parse failed: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Prefer {@code NegotiatedRateCharges.TotalCharge} (post-discount) over
     * {@code TotalCharges} (rack rate) — matches how UPS actually bills.
     */
    private static BigDecimal readUpsAmount(JsonNode entry) {
        JsonNode negotiated = entry.at("/NegotiatedRateCharges/TotalCharge/MonetaryValue");
        if (negotiated.isTextual()) {
            try { return new BigDecimal(negotiated.asText()); }
            catch (NumberFormatException ex) {
                log.debug("UPS readUpsAmount: non-numeric NegotiatedRateCharges.TotalCharge '{}'", negotiated.asText());
            }
        }
        if (negotiated.isNumber()) return negotiated.decimalValue();
        JsonNode total = entry.at("/TotalCharges/MonetaryValue");
        if (total.isTextual()) {
            try { return new BigDecimal(total.asText()); }
            catch (NumberFormatException ex) {
                log.debug("UPS readUpsAmount: non-numeric TotalCharges '{}'", total.asText());
            }
        }
        if (total.isNumber()) return total.decimalValue();
        return null;
    }

    /**
     * UPS-14 — pre-fix, a missing currency in the rate response silently
     * defaulted to "USD". UPS's real responses always include currency,
     * but a parsing edge case that mislabels a GBP or EUR rate as USD
     * silently over/under-charges by the FX difference. Now returns null
     * so downstream {@link RateOption#currency} (nullable) carries the
     * miss and the rate-shop UI treats it as "no quote" — surfaces the
     * missing field instead of hiding it. Same fix as FDX-D on FedEx.
     */
    private static String readUpsCurrency(JsonNode entry) {
        String currency = entry.at("/NegotiatedRateCharges/TotalCharge/CurrencyCode").asText(null);
        if (!StringUtils.hasText(currency)) {
            currency = entry.at("/TotalCharges/CurrencyCode").asText(null);
        }
        return StringUtils.hasText(currency) ? currency : null;
    }

    /**
     * Map a UPS service code back to its human-readable name from the built-in
     * matrix. Used when the Rate response omits Service.Description.
     */
    private String upsServiceName(String code) {
        return switch (code) {
            case "01" -> "UPS Next Day Air";
            case "02" -> "UPS 2nd Day Air";
            case "03" -> "UPS Ground";
            case "07" -> "UPS Worldwide Express";
            case "08" -> "UPS Worldwide Expedited";
            case "11" -> "UPS Standard";
            case "12" -> "UPS 3 Day Select";
            case "54" -> "UPS Worldwide Express Plus";
            case "65" -> "UPS Worldwide Saver";
            default -> "UPS " + code;
        };
    }

    private String buildFallbackToken(String clientId, String clientSecret) {
        return "ups-local-" + hashShort(clientId + ":" + clientSecret + ":" + LocalDateTime.now(ZoneOffset.UTC));
    }

    private String hashShort(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ex) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ex2) {
                log.debug("UPS parseDateTime: unparseable timestamp '{}' (neither OffsetDateTime nor LocalDateTime matched)", value);
                return null;
            }
        }
    }
}
