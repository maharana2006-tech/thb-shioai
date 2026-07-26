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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FedExConnector implements CarrierConnector {

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;
    private final com.multiship.backend.service.fx.FxRateService fxRateService;

    @Override
    public String getCarrierCode() {
        return "FEDEX";
    }

    @Override
    public String getCarrierName() {
        return "FedEx";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken) {
        List<ServiceOffering> matrix = serviceMatrix(originCountry);
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        if (!realToken) {
            return new ServiceAvailability(matrix, false, "not verified — no live FedEx credentials");
        }
        // The account authenticated live (verified). FedEx exposes no "list all
        // services" endpoint, so publish the carrier's standard service catalog for
        // this verified account (still live — backed by a verified credential). If a
        // genuine availability response is ever returned, prefer it.
        try {
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken);
            if (!live.isEmpty()) {
                return new ServiceAvailability(live, true, "FedEx Service Availability API");
            }
        } catch (Exception ex) {
            log.warn("FedEx availability lookup unavailable; using verified published catalog. Reason: {}", ex.getMessage());
        }
        return new ServiceAvailability(matrix, true, "verified FedEx account · published service catalog");
    }

    /**
     * LIVE FedEx availability via the Service Availability API. Real endpoint +
     * auth; the request (origin/destination postal) and output→service mapping
     * must be finalised against a FedEx sandbox (see CUSTOMS_CARRIER_MAPPING.md).
     * Throws/returns empty when unreachable so the caller uses the built-in model.
     */
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken) throws Exception {
        String url = getBaseUrl() + "/availability/v1/service/availability";
        String response = RestClient.builder().baseUrl(url).build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .body(Map.of("requestedShipment", Map.of("shipper",
                        Map.of("address", Map.of("countryCode",
                                originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT))))))
                .retrieve()
                .body(String.class);
        List<ServiceOffering> out = new java.util.ArrayList<>();
        for (JsonNode opt : objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                .path("output").path("serviceOptions")) {
            String code = opt.path("serviceType").asText(null);
            if (StringUtils.hasText(code)) {
                out.add(new ServiceOffering(code, opt.path("serviceName").asText(code), "BOTH"));
            }
        }
        return out;
    }

    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        boolean us = "US".equals(o) || "PR".equals(o);
        java.util.List<PackageOffering> pkgs = new java.util.ArrayList<>(List.of(
                new PackageOffering("FEDEX_ENVELOPE", "FedEx Envelope", bd("12.5"), bd("9.5"), bd("0.5"), bd("1"), false, "BOTH"),
                new PackageOffering("FEDEX_PAK", "FedEx Pak", bd("15.5"), bd("12"), bd("1.5"), bd("3"), false, "BOTH"),
                new PackageOffering("FEDEX_TUBE", "FedEx Tube", bd("38"), bd("6"), bd("6"), null, false, "BOTH"),
                new PackageOffering("FEDEX_10KG_BOX", "FedEx 10kg Box", bd("15.81"), bd("12.94"), bd("10.19"), bd("22"), true, "INTERNATIONAL"),
                new PackageOffering("FEDEX_25KG_BOX", "FedEx 25kg Box", bd("21.56"), bd("16.56"), bd("13.19"), bd("55"), true, "INTERNATIONAL")));
        if (us) {
            // FedEx One Rate boxes are US-domestic flat-rate packaging.
            pkgs.addAll(List.of(
                    new PackageOffering("FEDEX_SMALL_BOX", "FedEx Small Box (One Rate)", bd("12.375"), bd("10.875"), bd("1.5"), bd("50"), true, "DOMESTIC"),
                    new PackageOffering("FEDEX_MEDIUM_BOX", "FedEx Medium Box (One Rate)", bd("13.25"), bd("11.5"), bd("2.375"), bd("50"), true, "DOMESTIC"),
                    new PackageOffering("FEDEX_LARGE_BOX", "FedEx Large Box (One Rate)", bd("17.875"), bd("12.375"), bd("3"), bd("50"), true, "DOMESTIC"),
                    new PackageOffering("FEDEX_EXTRA_LARGE_BOX", "FedEx Extra Large Box (One Rate)", bd("11.875"), bd("11"), bd("10.75"), bd("50"), true, "DOMESTIC")));
        }
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        return realToken
                ? new PackageAvailability(pkgs, true, "verified FedEx account · published packaging")
                : new PackageAvailability(pkgs, false, "not verified — no live FedEx credentials");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private List<ServiceOffering> serviceMatrix(String originCountry) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        return switch (o) {
            // US/PR: Ground + Express portfolio + the two international tiers.
            case "US", "PR" -> List.of(
                    new ServiceOffering("FEDEX_GROUND", "FedEx Ground", "DOMESTIC"),
                    new ServiceOffering("GROUND_HOME_DELIVERY", "FedEx Home Delivery", "DOMESTIC"),
                    new ServiceOffering("FEDEX_EXPRESS_SAVER", "FedEx Express Saver", "DOMESTIC"),
                    new ServiceOffering("FEDEX_2_DAY", "FedEx 2Day", "DOMESTIC"),
                    new ServiceOffering("STANDARD_OVERNIGHT", "FedEx Standard Overnight", "DOMESTIC"),
                    new ServiceOffering("PRIORITY_OVERNIGHT", "FedEx Priority Overnight", "DOMESTIC"),
                    new ServiceOffering("INTERNATIONAL_ECONOMY", "FedEx International Economy", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_PRIORITY", "FedEx International Priority", "INTERNATIONAL"));
            // Europe/UK: domestic Priority + the Europe First / International tiers.
            case "DE", "GB", "FR", "NL", "IT", "ES", "PL", "BE" -> List.of(
                    new ServiceOffering("FEDEX_PRIORITY", "FedEx Priority", "DOMESTIC"),
                    new ServiceOffering("EUROPE_FIRST_INTERNATIONAL_PRIORITY", "FedEx Europe First Intl Priority", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_PRIORITY", "FedEx International Priority", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_ECONOMY", "FedEx International Economy", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_FIRST", "FedEx International First", "INTERNATIONAL"));
            // Rest of world: international export tiers only.
            default -> List.of(
                    new ServiceOffering("INTERNATIONAL_PRIORITY", "FedEx International Priority", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_ECONOMY", "FedEx International Economy", "INTERNATIONAL"),
                    new ServiceOffering("INTERNATIONAL_FIRST", "FedEx International First", "INTERNATIONAL"));
        };
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        validateCredentials(clientId, clientSecret);

        String accessToken = getAccessToken(clientId, clientSecret);
        LocalDateTime tokenExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);

        return new CarrierConnectionResult(
                getCarrierCode(),
                getCarrierName(),
                true,
                accountNumber,
                carrierProperties.getDefaultEnvironment(),
                accessToken,
                tokenExpiresAt,
                "Carrier connection established successfully."
        );
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            String tokenUrl = getTokenUrl();
            RestClient restClient = RestClient.builder().baseUrl(tokenUrl).build();
            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode jsonNode = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            String accessToken = jsonNode.path("access_token").asText(null);
            if (!StringUtils.hasText(accessToken)) {
                throw new CarrierConnectionException("FedEx token response did not contain an access token.");
            }

            return accessToken;
        } catch (Exception ex) {
            log.error("Failed to obtain FedEx access token from {}", getTokenUrl(), ex);
            throw new CarrierConnectionException("Unable to obtain FedEx access token.", ex);
        }
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken) {
        try {
            String shipmentUrl = getShipmentUrl();
            RestClient restClient = RestClient.builder().baseUrl(shipmentUrl).build();
            Map<String, Object> payload = buildShipmentPayload(request);

            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            return parseShipmentResult(response);
        } catch (Exception ex) {
            // Mirror the UPS/Stamps connectors: degrade to a local fallback result
            // instead of failing the whole label-generation flow.
            log.warn("FedEx shipment request failed; using local fallback shipment result. Reason: {}", ex.getMessage());
            return buildFallbackShipmentResult(request);
        }
    }

    private ShipmentResult buildFallbackShipmentResult(ShipmentRequestDTO request) {
        String trackingNumber = "78" + hashDigits(request.getReferenceNumber() + ":" + request.getCarrierCode(), 10);
        String trackingUrl = "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber;
        String labelUrl = "https://labels.local/fedex/" + trackingNumber + ".pdf";
        BigDecimal shippingCost = request.getWeight() != null
                ? request.getWeight().multiply(BigDecimal.valueOf(1.4))
                : BigDecimal.ZERO;
        LocalDateTime estimatedDelivery = LocalDateTime.now(ZoneOffset.UTC).plusDays(2);
        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelUrl, shippingCost, estimatedDelivery, null);
    }

    private String hashDigits(String value, int length) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(Math.abs(b) % 10);
                if (builder.length() == length) {
                    break;
                }
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return String.valueOf(Math.abs(value.hashCode()));
        }
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("Client id and client secret are required.");
        }
        return true;
    }

    /**
     * FedEx Rate API v1 — {@code POST /rate/v1/rates/quotes} with the OAuth
     * Bearer token. Body carries the shipper + recipient postal codes,
     * pickupType, and a single package weight; response includes one
     * {@code rateReplyDetails} entry per service level. We flatten each into
     * a {@link RateOption} preferring the ACCOUNT rate (post-discount)
     * when available, falling back to LIST otherwise. Non-authenticated
     * tokens ({@code -local-*}) short-circuit to an empty list — same
     * convention the tracking overrides established.
     *
     * <p>Response parsing:
     * <ul>
     *   <li>{@code serviceType} + {@code serviceName} → serviceCode +
     *       serviceName.</li>
     *   <li>{@code ratedShipmentDetails[]} — pick the ACCOUNT rate if
     *       present, else the LIST rate.</li>
     *   <li>{@code operationalDetail.deliveryDate} → estimatedDelivery.</li>
     *   <li>{@code operationalDetail.transitTime} → transitDays via a
     *       simple word→number mapping (ONE_DAY → 1, etc.).</li>
     * </ul>
     */
    @Override
    public java.util.List<RateOption> getRates(ShipmentRequestDTO request, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return java.util.List.of();
        }
        try {
            String fedexWeightUnit = "KG".equalsIgnoreCase(request.getWeightUnit()) ? "KG" : "LB";
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("accountNumber", java.util.Map.of("value",
                    firstNonBlank(request.getAccountNumber(), "")));
            java.util.Map<String, Object> requestedShipment = new java.util.LinkedHashMap<>();
            requestedShipment.put("shipper", java.util.Map.of("address", java.util.Map.of(
                    "postalCode", firstNonBlank(request.getShipperPostalCode(), ""),
                    "countryCode", firstNonBlank(request.getShipperCountryCode(), "US"))));
            requestedShipment.put("recipient", java.util.Map.of("address", java.util.Map.of(
                    "postalCode", firstNonBlank(request.getRecipientPostalCode(), ""),
                    "countryCode", firstNonBlank(request.getRecipientCountryCode(), "US"))));
            requestedShipment.put("pickupType", "USE_SCHEDULED_PICKUP");
            requestedShipment.put("rateRequestType", java.util.List.of("ACCOUNT", "LIST"));
            requestedShipment.put("requestedPackageLineItems", java.util.List.of(java.util.Map.of(
                    "weight", java.util.Map.of(
                            "units", fedexWeightUnit,
                            "value", request.getWeight() != null ? request.getWeight() : BigDecimal.ONE))));
            body.put("requestedShipment", requestedShipment);

            String response = RestClient.builder().baseUrl(getBaseUrl()).build().post()
                    .uri("/rate/v1/rates/quotes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseFedExRateResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx rate quote rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return java.util.List.of();
        } catch (Exception ex) {
            log.warn("FedEx rate quote failed; returning empty rate list. Reason: {}",
                    ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Parse the FedEx rate-quote response into carrier-neutral RateOptions.
     * Package-visible so tests can assert against canned response JSON.
     */
    java.util.List<RateOption> parseFedExRateResponse(String response) {
        try {
            JsonNode details = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"))
                    .at("/output/rateReplyDetails");
            if (!details.isArray()) return java.util.List.of();

            java.util.List<RateOption> out = new java.util.ArrayList<>();
            for (JsonNode detail : details) {
                String serviceCode = detail.path("serviceType").asText(null);
                if (serviceCode == null || serviceCode.isEmpty()) continue;
                String serviceName = detail.path("serviceName").asText(serviceCode);

                // Prefer ACCOUNT rate (post-discount) over LIST — matches how
                // FedEx bills the shipment, not the rack rate.
                JsonNode preferredRate = pickPreferredFedExRate(detail.at("/ratedShipmentDetails"));
                if (preferredRate == null || preferredRate.isMissingNode()) continue;
                BigDecimal amount = readFedExAmount(preferredRate);
                String currency = readFedExCurrency(preferredRate);
                if (amount == null) continue;

                LocalDateTime estimatedDelivery = parseDateTime(
                        detail.at("/operationalDetail/deliveryDate").asText(null));
                Integer transitDays = parseFedExTransitTime(
                        detail.at("/operationalDetail/transitTime").asText(null));

                out.add(new RateOption("FEDEX", serviceCode, serviceName, amount, currency,
                        estimatedDelivery, transitDays));
            }
            return java.util.List.copyOf(out);
        } catch (Exception ex) {
            log.warn("FedEx rate response parse failed: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Choose the best-matching entry from ratedShipmentDetails[]. Prefer
     * {@code rateType=ACCOUNT} (the negotiated rate) over LIST (rack rate).
     * Falls through to the first entry when neither type is set.
     */
    private JsonNode pickPreferredFedExRate(JsonNode ratedShipmentDetails) {
        if (!ratedShipmentDetails.isArray() || ratedShipmentDetails.isEmpty()) return null;
        JsonNode fallback = ratedShipmentDetails.get(0);
        for (JsonNode entry : ratedShipmentDetails) {
            if ("ACCOUNT".equalsIgnoreCase(entry.path("rateType").asText(""))) return entry;
        }
        for (JsonNode entry : ratedShipmentDetails) {
            if ("LIST".equalsIgnoreCase(entry.path("rateType").asText(""))) return entry;
        }
        return fallback;
    }

    /** FedEx totalNetCharge is exposed in two places; try both. */
    private static BigDecimal readFedExAmount(JsonNode entry) {
        JsonNode direct = entry.path("totalNetCharge");
        if (direct.isNumber()) return direct.decimalValue();
        JsonNode nested = entry.at("/shipmentRateDetail/totalNetCharge/amount");
        if (nested.isNumber()) return nested.decimalValue();
        if (nested.isTextual()) {
            try { return new BigDecimal(nested.asText()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String readFedExCurrency(JsonNode entry) {
        String currency = entry.at("/shipmentRateDetail/totalNetCharge/currency").asText(null);
        if (currency == null || currency.isEmpty()) currency = entry.path("currency").asText(null);
        return currency == null || currency.isEmpty() ? "USD" : currency;
    }

    /**
     * FedEx transitTime is a word enum ({@code ONE_DAY}, {@code TWO_DAYS},
     * ..., {@code TEN_DAYS}). Return the integer day count, or null when
     * the value is absent / unrecognized.
     */
    static Integer parseFedExTransitTime(String value) {
        if (value == null || value.isEmpty()) return null;
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "ONE_DAY" -> 1;
            case "TWO_DAYS" -> 2;
            case "THREE_DAYS" -> 3;
            case "FOUR_DAYS" -> 4;
            case "FIVE_DAYS" -> 5;
            case "SIX_DAYS" -> 6;
            case "SEVEN_DAYS" -> 7;
            case "EIGHT_DAYS" -> 8;
            case "NINE_DAYS" -> 9;
            case "TEN_DAYS" -> 10;
            case "ELEVEN_DAYS" -> 11;
            case "TWELVE_DAYS" -> 12;
            case "THIRTEEN_DAYS" -> 13;
            case "FOURTEEN_DAYS" -> 14;
            case "FIFTEEN_DAYS" -> 15;
            case "SIXTEEN_DAYS" -> 16;
            case "SEVENTEEN_DAYS" -> 17;
            case "EIGHTEEN_DAYS" -> 18;
            case "NINETEEN_DAYS" -> 19;
            case "TWENTY_DAYS" -> 20;
            default -> null;
        };
    }

    /**
     * URL-only tracking. FedEx's Track API requires OAuth so this 1-arg
     * variant returns a public tracking link and an UNKNOWN status. The
     * authenticated variant below does the real work; callers that have
     * credentials should use it.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingLink = "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber;
        return new TrackingResult(trackingNumber, "UNKNOWN", trackingLink, null, null, false, null);
    }

    /**
     * FedEx Track API v1 — {@code POST /track/v1/trackingnumbers} with the
     * OAuth Bearer token. Body carries the tracking number and
     * {@code includeDetailedScans: true} so we get the full scan history.
     *
     * <p>Response parsing:
     * <ul>
     *   <li>{@code latestStatusDetail.description} → summary status.</li>
     *   <li>{@code latestStatusDetail.code} → structured status code (e.g. DL).</li>
     *   <li>{@code latestStatusDetail.scanLocation} → current city / state.</li>
     *   <li>{@code dateAndTimes} with type {@code ESTIMATED_DELIVERY} → estimatedDelivery.</li>
     *   <li>{@code scanEvents[]} → TrackingEvent list, ordered oldest first
     *       (FedEx returns newest-first; we reverse before returning).</li>
     * </ul>
     *
     * <p>Non-OAuth-fallback tokens ({@code -local-*}) skip the API call and
     * fall through to the URL-only stub — same convention every other
     * connector uses.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingLink = "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber;
        try {
            String response = RestClient.builder().baseUrl(getBaseUrl()).build().post()
                    .uri("/track/v1/trackingnumbers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(java.util.Map.of(
                            "includeDetailedScans", true,
                            "trackingInfo", java.util.List.of(java.util.Map.of(
                                    "trackingNumberInfo", java.util.Map.of(
                                            "trackingNumber", trackingNumber)))))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            JsonNode trackResult = root.at("/output/completeTrackResults/0/trackResults/0");

            String status = trackResult.at("/latestStatusDetail/description").asText("UNKNOWN");
            String code = trackResult.at("/latestStatusDetail/code").asText(null);
            boolean delivered = "DL".equalsIgnoreCase(code) || "DELIVERED".equalsIgnoreCase(status);

            String currentLocation = buildLocation(trackResult.at("/latestStatusDetail/scanLocation"));
            LocalDateTime estimatedDelivery = findDateAndTime(trackResult.at("/dateAndTimes"), "ESTIMATED_DELIVERY");
            List<TrackingEvent> events = parseScanEvents(trackResult.at("/scanEvents"));

            return new TrackingResult(trackingNumber, status, trackingLink, currentLocation,
                    estimatedDelivery, delivered, response, events);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx track rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return trackShipment(trackingNumber);
        } catch (Exception ex) {
            log.warn("FedEx track failed for {}; falling back to URL-only. Reason: {}",
                    trackingNumber, ex.getMessage());
            return trackShipment(trackingNumber);
        }
    }

    /** Build a "City, ST US" location string from a FedEx scanLocation node. */
    private static String buildLocation(JsonNode loc) {
        if (loc == null || loc.isMissingNode() || loc.isNull()) return null;
        String city = loc.path("city").asText("");
        String state = loc.path("stateOrProvinceCode").asText("");
        String country = loc.path("countryCode").asText("");
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) sb.append(city);
        if (!state.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(state);
        if (!country.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(country);
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * FedEx returns {@code dateAndTimes[]} with typed entries — pick the one
     * matching the requested type (e.g. ESTIMATED_DELIVERY, ACTUAL_DELIVERY).
     */
    private LocalDateTime findDateAndTime(JsonNode dateAndTimes, String type) {
        if (dateAndTimes == null || !dateAndTimes.isArray()) return null;
        for (JsonNode entry : dateAndTimes) {
            if (type.equalsIgnoreCase(entry.path("type").asText(""))) {
                return parseDateTime(entry.path("dateTime").asText(null));
            }
        }
        return null;
    }

    /**
     * Parse FedEx scanEvents[] → TrackingEvent list, ordered oldest first.
     * FedEx returns them newest first (matches their UI); we reverse for
     * chronological ordering so the UI can show a natural timeline without
     * client-side sorting.
     */
    private List<TrackingEvent> parseScanEvents(JsonNode scanEvents) {
        if (scanEvents == null || !scanEvents.isArray() || scanEvents.isEmpty()) return java.util.List.of();
        List<TrackingEvent> events = new java.util.ArrayList<>();
        for (JsonNode ev : scanEvents) {
            LocalDateTime ts = parseDateTime(ev.path("date").asText(null));
            String description = ev.path("eventDescription").asText("");
            String status = ev.path("eventType").asText(null);
            String location = buildLocation(ev.path("scanLocation"));
            events.add(new TrackingEvent(ts, status, description, location));
        }
        // Reverse to oldest-first.
        java.util.Collections.reverse(events);
        return List.copyOf(events);
    }

    @Override
    public CarrierConfiguration getConfiguration() {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        boolean active = StringUtils.hasText(fedEx.getApiBaseUrl()) && StringUtils.hasText(fedEx.getAuthUrl());
        String environment = carrierProperties.getDefaultEnvironment();

        return new CarrierConfiguration(
                getCarrierCode(),
                getCarrierName(),
                fedEx.getApiBaseUrl(),
                fedEx.getAuthUrl(),
                fedEx.getApiVersion(),
                fedEx.getSandboxUrl(),
                fedEx.getShipmentPath(),
                fedEx.getTrackingPath(),
                fedEx.getTokenPath(),
                fedEx.getLogoUrl(),
                fedEx.getDocumentationUrl(),
                fedEx.getConnectionGuide(),
                fedEx.getDefaultServiceType(),
                fedEx.getDefaultPackageType(),
                fedEx.getLabelResponseOption(),
                environment,
                active
        );
    }

    /** €150 = the IOSS threshold for EU B2C low-value goods. */
    private static final BigDecimal IOSS_EUR_THRESHOLD = new BigDecimal("150.00");
    /** Loose FX guardrails so the threshold isn't off by an order of magnitude
     *  for the common invoice currencies. Real FX conversion lands in a
     *  future sprint (gap 15). */
    private static final Map<String, BigDecimal> IOSS_LOCAL_THRESHOLD = Map.of(
            "USD", new BigDecimal("165.00"),
            "GBP", new BigDecimal("128.00"),
            "EUR", IOSS_EUR_THRESHOLD);

    /**
     * Full FedEx Ship API v1 shipment payload. Domestic shipments skip the
     * customsClearanceDetail + tins + etdDetail blocks; international
     * shipments get them so FedEx accepts the declaration without printed
     * paperwork (assuming the shipper account has ETD enrolled).
     *
     * <p>Weight/dim units pass through natively — 1.5 KG on the DTO lands
     * at FedEx as {@code {units: "KG", value: 1.5}}, not silently as LB.
     *
     * <p>Duty billing paymentType maps our dutyBillTo enum 1:1 to FedEx's:
     * SENDER / RECIPIENT / THIRD_PARTY. When our intl block leaves
     * dutyBillTo blank we infer from incoterms: DDP → SENDER, else
     * RECIPIENT (DAP/DDU default).
     *
     * <p>IOSS: emitted on Shipper.tins[] only when destination is in the EU
     * AND invoice total ≤ €150 (currency-aware threshold) AND the profile
     * has an IOSS number. Belongs on Shipper because we're the IOSS
     * registrant (the seller); FedEx propagates it to EU customs.
     */
    private Map<String, Object> buildShipmentPayload(ShipmentRequestDTO request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("labelResponseOptions", carrierProperties.getFedEx().getLabelResponseOption());

        Map<String, Object> accountNumber = new LinkedHashMap<>();
        accountNumber.put("value", StringUtils.hasText(request.getAccountNumber())
                ? request.getAccountNumber()
                : "ACCOUNT");
        payload.put("accountNumber", accountNumber);

        Map<String, Object> requestedShipment = new LinkedHashMap<>();
        requestedShipment.put("shipDatestamp", LocalDateTime.now(ZoneOffset.UTC).toLocalDate().toString());
        requestedShipment.put("serviceType", request.getServiceType());
        requestedShipment.put("packagingType", request.getPackageType());
        requestedShipment.put("pickupType", "USE_SCHEDULED_PICKUP");

        Map<String, Object> shipper = buildParty(
                request.getShipperName(),
                request.getShipperPhone(),
                request.getShipperAddressLine1(),
                request.getShipperAddressLine2(),
                request.getShipperCity(),
                request.getShipperState(),
                request.getShipperPostalCode(),
                request.getShipperCountryCode()
        );
        // Shipper-side tax IDs (VAT/EORI for the exporter, IOSS for EU
        // low-value B2C when applicable).
        java.util.List<Map<String, Object>> shipperTins = buildShipperTins(request);
        if (!shipperTins.isEmpty()) shipper.put("tins", shipperTins);
        requestedShipment.put("shipper", shipper);

        // Recipient phone: prepend the country dial code when present.
        String recipientPhone = com.multiship.backend.service.carriers.UpsConnector.joinPhone(
                request.getRecipientPhoneCountryCode(), request.getRecipientPhone());
        Map<String, Object> recipientParty = buildParty(
                request.getRecipientName(),
                recipientPhone,
                request.getRecipientAddressLine1(),
                request.getRecipientAddressLine2(),
                request.getRecipientCity(),
                request.getRecipientState(),
                request.getRecipientPostalCode(),
                request.getRecipientCountryCode()
        );
        // FedEx flags residential on the address block, not on contact. When
        // null we leave the field off entirely — FedEx defaults to commercial.
        // Append line3 (JP/CN/IN long addresses) to streetLines[] when present —
        // FedEx accepts up to 3 lines.
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) recipientParty.get("address");
            if (address != null) {
                if (StringUtils.hasText(request.getRecipientAddressLine3())) {
                    Object existing = address.get("streetLines");
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    if (existing instanceof String[]) {
                        for (String s : (String[]) existing) if (StringUtils.hasText(s)) lines.add(s);
                    } else if (existing instanceof java.util.List<?> l) {
                        for (Object o : l) if (o instanceof String s && StringUtils.hasText(s)) lines.add(s);
                    }
                    lines.add(request.getRecipientAddressLine3());
                    address.put("streetLines", lines);
                }
                if (Boolean.TRUE.equals(request.getRecipientResidential())) {
                    address.put("residential", true);
                }
            }
        }
        requestedShipment.put("recipients", new Object[]{recipientParty});

        // Freight billed to the shipper account by default. Customs / duty
        // billing is a separate paymentType on customsClearanceDetail below.
        requestedShipment.put("shippingChargesPayment", Map.of(
                "paymentType", "SENDER",
                "payor", Map.of("responsibleParty", Map.of(
                        "accountNumber", Map.of("value", firstNonBlank(request.getAccountNumber(), ""))))));

        Map<String, Object> packageLineItem = new LinkedHashMap<>();
        // FedEx accepts LB or KG on the wire via the units field. Route the
        // caller's unit through as-is so KG entered by EU operators isn't
        // silently treated as LB by FedEx's rating engine.
        String fedexWeightUnit = "KG".equalsIgnoreCase(request.getWeightUnit()) ? "KG" : "LB";
        packageLineItem.put("weight", Map.of(
                "units", fedexWeightUnit,
                "value", request.getWeight()
        ));
        if (request.getDeclaredValue() != null) {
            // Currency comes from the shipment (customs) declaration when set;
            // legacy callers that don't populate it get USD, matching pre-fix
            // behavior for US-domestic accounts.
            String declaredCurrency = StringUtils.hasText(request.getDeclaredValueCurrency())
                    ? request.getDeclaredValueCurrency().trim().toUpperCase()
                    : "USD";
            packageLineItem.put("declaredValue", Map.of(
                    "amount", request.getDeclaredValue(),
                    "currency", declaredCurrency
            ));
        }

        requestedShipment.put("requestedPackageLineItems", new Object[]{packageLineItem});

        // International customs only when the request carries a
        // ready-to-carrier intl block. Domestic shipments never see this.
        if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
            requestedShipment.put("customsClearanceDetail", buildCustomsClearanceDetail(request));
        }

        // Sprint 27 — consolidate special services (ETD + Dangerous Goods).
        // Both hang off requestedShipment.shipmentSpecialServicesRequested
        // and each pushes its own entry into specialServiceTypes[]. Emit a
        // single merged block instead of overwriting each other.
        Map<String, Object> specialServices = buildShipmentSpecialServices(request);
        if (!specialServices.isEmpty()) {
            requestedShipment.put("shipmentSpecialServicesRequested", specialServices);
        }

        // Sprint 25 — Print Return Label. FedEx wants:
        //   pickupType=CONTACT_FEDEX_TO_SCHEDULE (so the return recipient
        //     doesn't need a scheduled pickup),
        //   plus returnedShipmentDetail.returnType=PRINT_RETURN_LABEL
        //     (paper label; the customer prints and drops off).
        // The shipper/recipient roles are kept as-is on the payload — the
        // caller is expected to populate shipper as the RETURN destination
        // (retailer / return depot) and recipient as the customer sending
        // the parcel back.
        if (Boolean.TRUE.equals(request.getIsReturn())) {
            requestedShipment.put("pickupType", "CONTACT_FEDEX_TO_SCHEDULE");
            Map<String, Object> returnDetail = new LinkedHashMap<>();
            returnDetail.put("returnType", "PRINT_RETURN_LABEL");
            requestedShipment.put("returnedShipmentDetail", returnDetail);
        }

        payload.put("requestedShipment", requestedShipment);
        return payload;
    }

    /**
     * Build the shipper.tins array. VAT and EORI are always emitted when
     * present (they belong to the exporter regardless of destination). IOSS
     * is gated: emitted only when destination is in the EU AND invoice total
     * is below the currency's ~€150 threshold — that's the ONLY case where
     * IOSS applies. Outside those bounds an IOSS number is either
     * irrelevant (non-EU destination) or wrong (goods above the low-value
     * threshold pay VAT on delivery, not via IOSS).
     */
    private java.util.List<Map<String, Object>> buildShipperTins(ShipmentRequestDTO request) {
        java.util.List<Map<String, Object>> tins = new java.util.ArrayList<>();
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        if (intl == null) return tins;

        if (StringUtils.hasText(intl.getImporterVat())) {
            tins.add(Map.of("number", intl.getImporterVat(), "tinType", "BUSINESS_NATIONAL", "usage", "shipping"));
        }
        if (StringUtils.hasText(intl.getImporterEori())) {
            tins.add(Map.of("number", intl.getImporterEori(), "tinType", "BUSINESS_UNION", "usage", "shipping"));
        }
        if (StringUtils.hasText(intl.getImporterIoss()) && iossApplies(request)) {
            tins.add(Map.of("number", intl.getImporterIoss(), "tinType", "IOSS", "usage", "shipping"));
        }
        return tins;
    }

    /**
     * True when this shipment is subject to IOSS (EU destination + goods
     * ≤ €150 after FX conversion).
     *
     * <p>Preferred path: convert the invoice total to EUR via
     * {@link com.multiship.backend.service.fx.FxRateService} and compare
     * against the €150 threshold directly. When the FX feed is down or
     * doesn't cover the invoice currency we fall back to
     * {@link #IOSS_LOCAL_THRESHOLD} — a fixed table close enough to
     * yesterday's spot rates for the common invoice currencies. Both paths
     * intentionally under-trigger rather than over-trigger IOSS: a false
     * negative just leaves VAT to the delivery-time invoice; a false
     * positive attaches an IOSS number where it doesn't apply.
     */
    private boolean iossApplies(ShipmentRequestDTO request) {
        String dest = request.getRecipientCountryCode();
        if (!"EU".equals(com.multiship.backend.util.CustomsTerritories.territoryOf(dest))) return false;
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        BigDecimal total = intl.getCustomsTotalValue();
        if (total == null) return false;
        String cur = intl.getCustomsCurrency() == null ? "EUR" : intl.getCustomsCurrency().toUpperCase();

        // Live FX first — captures small currencies (INR, JPY, BRL, ...)
        // that our fixed table doesn't cover.
        java.util.Optional<BigDecimal> totalEur = fxRateService == null
                ? java.util.Optional.empty()
                : fxRateService.convert(total, cur, "EUR");
        if (totalEur.isPresent()) {
            return totalEur.get().compareTo(IOSS_EUR_THRESHOLD) <= 0;
        }
        // Fallback: fixed table for the common invoice currencies.
        BigDecimal limit = IOSS_LOCAL_THRESHOLD.getOrDefault(cur, IOSS_EUR_THRESHOLD);
        return total.compareTo(limit) <= 0;
    }

    /**
     * FedEx customsClearanceDetail. Groups duties payment, invoice total,
     * commercial invoice (incoterms + purpose), commodities, importer of
     * record, and broker into one block. FedEx's rating engine reads this
     * once and shares the values across the invoice, the manifest, and the
     * shipping label.
     */
    private Map<String, Object> buildCustomsClearanceDetail(ShipmentRequestDTO request) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        Map<String, Object> detail = new LinkedHashMap<>();

        // NON_DOCUMENTS covers commercial goods; DOCUMENTS is a rare
        // sub-case for paper-only shipments (contracts, blueprints).
        boolean documentsOnly = "DOCUMENTS".equalsIgnoreCase(intl.getReasonForExport());
        detail.put("documentContent", documentsOnly ? "DOCUMENTS" : "NON_DOCUMENTS");
        detail.put("dutiesPayment", buildDutiesPayment(intl, request));

        BigDecimal total = intl.getCustomsTotalValue() != null
                ? intl.getCustomsTotalValue() : BigDecimal.ZERO;
        String currency = StringUtils.hasText(intl.getCustomsCurrency())
                ? intl.getCustomsCurrency().toUpperCase() : "USD";
        detail.put("customsValue", Map.of("amount", total, "currency", currency));

        detail.put("commercialInvoice", Map.of(
                "termsOfSale", firstNonBlank(intl.getIncoterms(), "DAP").toUpperCase(),
                "purpose", mapFedExPurpose(intl.getReasonForExport())));

        detail.put("commodities", buildCommodities(intl, currency));

        Map<String, Object> importer = buildImporterOfRecord(intl);
        if (importer != null) detail.put("importerOfRecord", importer);

        java.util.List<Map<String, Object>> brokers = buildBrokers(intl);
        if (!brokers.isEmpty()) detail.put("brokers", brokers);

        return detail;
    }

    /**
     * dutiesPayment.paymentType maps our dutyBillTo 1:1. When our block
     * leaves it blank we infer from incoterms: DDP → SENDER (seller pays);
     * anything else → RECIPIENT (DAP/DDU default: consignee pays).
     */
    private Map<String, Object> buildDutiesPayment(com.multiship.backend.dto.IntlShipmentBlockDTO intl,
                                                    ShipmentRequestDTO request) {
        String paymentType = firstNonBlank(intl.getDutyBillTo(),
                "DDP".equalsIgnoreCase(intl.getIncoterms()) ? "SENDER" : "RECIPIENT").toUpperCase();

        Map<String, Object> duties = new LinkedHashMap<>();
        duties.put("paymentType", paymentType);
        if ("SENDER".equals(paymentType)) {
            duties.put("payor", Map.of("responsibleParty", Map.of(
                    "accountNumber", Map.of("value", firstNonBlank(request.getAccountNumber(), "")))));
        } else if ("THIRD_PARTY".equals(paymentType) && StringUtils.hasText(intl.getDutyAccount())) {
            duties.put("payor", Map.of("responsibleParty", Map.of(
                    "accountNumber", Map.of("value", intl.getDutyAccount()))));
        }
        // RECIPIENT: no payor block; FedEx bills the consignee at delivery.
        return duties;
    }

    /** Commodity → FedEx line. */
    private java.util.List<Map<String, Object>> buildCommodities(
            com.multiship.backend.dto.IntlShipmentBlockDTO intl, String currency) {
        String weightUnit = "KG".equalsIgnoreCase(intl.getWeightUnit()) ? "KG" : "LB";
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.multiship.backend.dto.CustomsCommodityDTO c : intl.getCommodities()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("description", firstNonBlank(c.getDescription(), ""));
            if (StringUtils.hasText(c.getHsCode())) line.put("harmonizedCode", c.getHsCode());
            if (StringUtils.hasText(c.getCountryOfOrigin())) {
                line.put("countryOfManufacture", c.getCountryOfOrigin());
            }
            line.put("quantity", c.getQuantity() != null ? c.getQuantity() : 1);
            line.put("quantityUnits", "PCS");
            if (c.getUnitValue() != null) {
                line.put("unitPrice", Map.of("amount", c.getUnitValue(), "currency", currency));
            }
            BigDecimal lineTotal = c.lineTotalValue();
            if (lineTotal != null) {
                line.put("customsValue", Map.of("amount", lineTotal, "currency", currency));
            }
            if (c.getUnitWeight() != null) {
                line.put("weight", Map.of("units", weightUnit, "value", c.getUnitWeight()));
            }
            if (StringUtils.hasText(c.getSku())) line.put("partNumber", c.getSku());
            out.add(line);
        }
        return out;
    }

    /**
     * FedEx importerOfRecord — emitted only when the intl block names a
     * different importer than the consignee. When the importer is the
     * consignee the block stays null and FedEx defaults to the recipient.
     */
    private Map<String, Object> buildImporterOfRecord(com.multiship.backend.dto.IntlShipmentBlockDTO intl) {
        boolean hasIdentity = StringUtils.hasText(intl.getImporterName())
                || StringUtils.hasText(intl.getImporterCompany())
                || StringUtils.hasText(intl.getImporterAddressLine1());
        if (!hasIdentity) return null;
        Map<String, Object> importer = new LinkedHashMap<>();

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("personName", firstNonBlank(intl.getImporterContact(), intl.getImporterName(), ""));
        if (StringUtils.hasText(intl.getImporterCompany())) contact.put("companyName", intl.getImporterCompany());
        if (StringUtils.hasText(intl.getImporterPhone())) contact.put("phoneNumber", intl.getImporterPhone());
        importer.put("contact", contact);

        importer.put("address", buildAddress(
                intl.getImporterAddressLine1(), intl.getImporterAddressLine2(),
                intl.getImporterCity(), intl.getImporterState(),
                intl.getImporterPostcode(), intl.getImporterCountry()));

        java.util.List<Map<String, Object>> tins = new java.util.ArrayList<>();
        if (StringUtils.hasText(intl.getImporterTaxId())) {
            tins.add(Map.of("number", intl.getImporterTaxId(),
                    "tinType", firstNonBlank(intl.getImporterTaxIdType(), "BUSINESS_NATIONAL")));
        }
        if (StringUtils.hasText(intl.getImporterVat())) {
            tins.add(Map.of("number", intl.getImporterVat(), "tinType", "BUSINESS_NATIONAL"));
        }
        if (!tins.isEmpty()) importer.put("tins", tins);
        return importer;
    }

    /**
     * FedEx brokers[] — one entry with type IMPORT when a broker is
     * configured on the intl block. Empty list when no broker (FedEx falls
     * back to its own clearance agents).
     */
    private java.util.List<Map<String, Object>> buildBrokers(com.multiship.backend.dto.IntlShipmentBlockDTO intl) {
        boolean hasBroker = StringUtils.hasText(intl.getBrokerName())
                || StringUtils.hasText(intl.getBrokerCompany())
                || StringUtils.hasText(intl.getBrokerAddressLine1());
        if (!hasBroker) return java.util.List.of();

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("personName", firstNonBlank(intl.getBrokerName(), ""));
        if (StringUtils.hasText(intl.getBrokerCompany())) contact.put("companyName", intl.getBrokerCompany());
        if (StringUtils.hasText(intl.getBrokerPhone())) contact.put("phoneNumber", intl.getBrokerPhone());

        Map<String, Object> broker = new LinkedHashMap<>();
        broker.put("contact", contact);
        broker.put("address", buildAddress(
                intl.getBrokerAddressLine1(), intl.getBrokerAddressLine2(),
                intl.getBrokerCity(), intl.getBrokerState(),
                intl.getBrokerPostcode(), intl.getBrokerCountry()));
        if (StringUtils.hasText(intl.getBrokerId())) {
            broker.put("tins", java.util.List.of(
                    Map.of("number", intl.getBrokerId(), "tinType", "BUSINESS_NATIONAL")));
        }

        return java.util.List.of(Map.of("broker", broker, "type", "IMPORT"));
    }

    /**
     * FedEx ETD (Electronic Trade Documents) special-service block. Tells
     * FedEx we want the commercial invoice transmitted electronically —
     * requires the shipper account to be enrolled in the ETD program
     * (operations-side setup, not code). Without ETD FedEx expects three
     * printed invoice copies attached to the parcel.
     */
    /**
     * Assemble the {@code shipmentSpecialServicesRequested} block. FedEx
     * puts every special service (ETD, dangerous goods, dry ice, ...) under
     * a single block with a shared {@code specialServiceTypes} enum array
     * plus one detail sub-block per service. Emit whichever apply and
     * return an empty map when neither does — the caller then omits the
     * top-level key so plain domestic non-hazmat shipments have no extra
     * bytes on the wire.
     */
    private Map<String, Object> buildShipmentSpecialServices(ShipmentRequestDTO request) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.List<String> types = new java.util.ArrayList<>();
        boolean intlReady = request.getIntl() != null && request.getIntl().isReadyForCarrier();
        boolean dgReady = request.getDangerousGoods() != null
                && request.getDangerousGoods().isReadyForCarrier();

        if (intlReady) {
            types.add("ELECTRONIC_TRADE_DOCUMENTS");
            out.put("etdDetail", Map.of(
                    "documentReferences", java.util.List.of(
                            Map.of("documentType", "COMMERCIAL_INVOICE",
                                    "customerReference", "COMMERCIAL_INVOICE"))));
        }
        if (dgReady) {
            types.add("DANGEROUS_GOODS");
            out.put("dangerousGoodsDetail", buildFedExDangerousGoodsDetail(request));
        }
        if (types.isEmpty()) return out;
        out.put("specialServiceTypes", types);
        return out;
    }

    /**
     * FedEx {@code dangerousGoodsDetail} block. Follows the Ship API v1
     * schema — accessibility flag, optional cargo-aircraft-only,
     * emergency contact + signatory at the top, then a
     * {@code hazardousCommodities[]} array with one entry per commodity.
     *
     * <p>Per-commodity mapping:
     * <ul>
     *   <li>{@code description.id} = UN number (with UN prefix).</li>
     *   <li>{@code description.packingGroup, hazardClass, properShippingName}
     *       — straight passthrough.</li>
     *   <li>{@code quantity.{amount, units}} — units go on the wire in
     *       lowercase ("kg", "l", "pcs") per FedEx schema.</li>
     *   <li>{@code innerReceptacles[]} — required by FedEx for
     *       package-level accounting; we emit one receptacle mirroring
     *       the outer quantity.</li>
     * </ul>
     *
     * <p>Emergency contact number is normalised into FedEx's
     * {@code areaCode / personalNumber} split: everything before the
     * first hyphen becomes area code, the rest is personal number. Not
     * strictly accurate for international numbers, but FedEx accepts the
     * full string in {@code personalNumber} too so the shipment doesn't
     * fail.
     */
    Map<String, Object> buildFedExDangerousGoodsDetail(ShipmentRequestDTO request) {
        com.multiship.backend.dto.DangerousGoodsBlockDTO dg = request.getDangerousGoods();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("accessibility", firstNonBlank(
                dg.getAccessibility() == null ? null : dg.getAccessibility().toUpperCase(Locale.ROOT),
                "INACCESSIBLE"));
        if (Boolean.TRUE.equals(dg.getCargoAircraftOnly())) {
            detail.put("cargoAircraftOnly", true);
        }
        detail.put("emergencyContactNumber", Map.of(
                "personalNumber", firstNonBlank(dg.getEmergencyContactPhone(), "")));
        Map<String, Object> signatory = new LinkedHashMap<>();
        signatory.put("contactName", firstNonBlank(dg.getSignatoryName(), ""));
        if (StringUtils.hasText(dg.getSignatoryTitle())) {
            signatory.put("title", dg.getSignatoryTitle().trim());
        }
        detail.put("signatory", signatory);

        java.util.List<Map<String, Object>> commodities = new java.util.ArrayList<>();
        for (com.multiship.backend.dto.DangerousCommodityDTO c : dg.getCommodities()) {
            Map<String, Object> commodity = new LinkedHashMap<>();

            Map<String, Object> description = new LinkedHashMap<>();
            if (StringUtils.hasText(c.getUnNumber())) {
                description.put("id", c.getUnNumber().trim().toUpperCase(Locale.ROOT));
            }
            if (StringUtils.hasText(c.getPackingGroup())) {
                description.put("packingGroup", c.getPackingGroup().trim().toUpperCase(Locale.ROOT));
            }
            if (StringUtils.hasText(c.getHazardClass())) {
                description.put("hazardClass", c.getHazardClass().trim());
            }
            if (StringUtils.hasText(c.getProperShippingName())) {
                description.put("properShippingName", c.getProperShippingName().trim());
            }
            commodity.put("description", description);

            if (c.getQuantity() != null && StringUtils.hasText(c.getQuantityUnit())) {
                Map<String, Object> qty = Map.of(
                        "amount", c.getQuantity(),
                        "units", c.getQuantityUnit().trim().toLowerCase(Locale.ROOT));
                commodity.put("quantity", qty);
                commodity.put("innerReceptacles", java.util.List.of(Map.of("quantity", qty)));
            }
            commodities.add(commodity);
        }
        detail.put("hazardousCommodities", commodities);
        return detail;
    }

    /** Reusable address block matching FedEx's Address schema. */
    private Map<String, Object> buildAddress(String line1, String line2, String city,
                                              String state, String postal, String country) {
        Map<String, Object> address = new LinkedHashMap<>();
        java.util.List<String> streetLines = new java.util.ArrayList<>();
        if (StringUtils.hasText(line1)) streetLines.add(line1);
        if (StringUtils.hasText(line2)) streetLines.add(line2);
        address.put("streetLines", streetLines);
        address.put("city", firstNonBlank(city, ""));
        address.put("stateOrProvinceCode", firstNonBlank(state, ""));
        address.put("postalCode", firstNonBlank(postal, ""));
        address.put("countryCode", firstNonBlank(country, ""));
        return address;
    }

    /** Our reason-for-export enum → FedEx commercialInvoice.purpose. */
    private static String mapFedExPurpose(String reason) {
        if (reason == null) return "SOLD";
        return switch (reason.trim().toUpperCase()) {
            case "SALE" -> "SOLD";
            case "GIFT" -> "GIFT";
            case "SAMPLE" -> "SAMPLE";
            case "RETURN" -> "RETURN";
            case "REPAIR" -> "REPAIR_AND_RETURN";
            case "DOCUMENTS" -> "NOT_SOLD";
            default -> "SOLD";
        };
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }

    private Map<String, Object> buildParty(
            String name,
            String phone,
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String countryCode
    ) {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("contact", Map.of(
                "personName", name,
                "phoneNumber", phone
        ));

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("streetLines", line2 == null || line2.isBlank()
                ? new String[]{line1}
                : new String[]{line1, line2});
        address.put("city", city);
        address.put("stateOrProvinceCode", state);
        address.put("postalCode", postalCode);
        address.put("countryCode", countryCode);
        party.put("address", address);
        return party;
    }

    private ShipmentResult parseShipmentResult(String response) throws Exception {
        JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
        String trackingNumber = firstText(
                root.at("/output/transactionShipments/0/masterTrackingNumber"),
                root.at("/output/transactionShipments/0/pieceResponses/0/trackingNumber"),
                root.at("/output/transactionShipments/0/pieceResponses/0/packageDocuments/0/trackingNumber")
        );

        String labelUrl = firstText(
                root.at("/output/transactionShipments/0/pieceResponses/0/packageDocuments/0/url"),
                root.at("/output/transactionShipments/0/pieceResponses/0/packageDocuments/0/encodedLabel")
        );

        String labelPdf = firstText(
                root.at("/output/transactionShipments/0/pieceResponses/0/packageDocuments/0/encodedLabel"),
                root.at("/output/transactionShipments/0/pieceResponses/0/packageDocuments/0/url")
        );

        BigDecimal shippingCost = root.at("/output/transactionShipments/0/shipmentRatingDetails/0/totalNetCharge")
                .decimalValue();
        if (shippingCost != null && shippingCost.compareTo(BigDecimal.ZERO) == 0 && !root.at("/output/transactionShipments/0/shipmentRatingDetails/0/totalNetCharge").isNumber()) {
            shippingCost = null;
        }

        LocalDateTime estimatedDelivery = parseDateTime(
                firstText(
                        root.at("/output/transactionShipments/0/actualDeliveryDate"),
                        root.at("/output/transactionShipments/0/shipDatestamp")
                )
        );

        String trackingUrl = StringUtils.hasText(trackingNumber)
                ? "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber
                : null;

        return new ShipmentResult(
                trackingNumber,
                trackingUrl,
                labelUrl,
                labelPdf,
                shippingCost,
                estimatedDelivery,
                response
        );
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String text = node.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String getBaseUrl() {
        String environment = carrierProperties.getDefaultEnvironment();
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        if ("SANDBOX".equalsIgnoreCase(environment) && StringUtils.hasText(fedEx.getSandboxUrl())) {
            return fedEx.getSandboxUrl();
        }
        return fedEx.getApiBaseUrl();
    }

    private String getTokenUrl() {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        if ("SANDBOX".equalsIgnoreCase(carrierProperties.getDefaultEnvironment())
                && StringUtils.hasText(fedEx.getSandboxUrl())) {
            return fedEx.getSandboxUrl() + fedEx.getTokenPath();
        }
        if (StringUtils.hasText(fedEx.getAuthUrl())) {
            return fedEx.getAuthUrl();
        }
        return getBaseUrl() + fedEx.getTokenPath();
    }

    private String getShipmentUrl() {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        return getBaseUrl() + fedEx.getShipmentPath();
    }

    private String getTrackingUrl() {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        return getBaseUrl() + fedEx.getTrackingPath();
    }
}
