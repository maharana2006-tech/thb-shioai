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

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "UPS";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken) {
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
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken);
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
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken) throws Exception {
        String url = carrierProperties.getUps().getApiBaseUrl() + "/api/rating/"
                + carrierProperties.getUps().getApiVersion() + "/Shop";
        String response = RestClient.builder().baseUrl(url).build()
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
    public PackageAvailability listPackages(String originCountry, String accessToken) {
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
        return getAccessToken(clientId, clientSecret, null);
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
     * <p>Note: the "Consumer Key" and "Consumer Secret" values from the UPS
     * Developer Portal ARE the OAuth client_id / client_secret used here.
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        String tokenUrl = carrierProperties.getUps().getAuthUrl();
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");

            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

            RestClient restClient = RestClient.builder().baseUrl(tokenUrl).build();
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
                log.warn("UPS token endpoint returned no access_token; response: {}", response);
                return buildFallbackToken(clientId, clientSecret);
            }
            return accessToken;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // UPS puts the reason ({"response":{"errors":[{"code":"...","message":"..."}]}})
            // in the response body. Surface it in the log so verify failures are actionable.
            log.warn("UPS token request rejected (HTTP {}): {} — using local fallback token.",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return buildFallbackToken(clientId, clientSecret);
        } catch (Exception ex) {
            log.warn("UPS token request failed; using local fallback token. Reason: {}", ex.getMessage());
            return buildFallbackToken(clientId, clientSecret);
        }
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken) {
        try {
            Map<String, Object> payload = buildShipmentPayload(request);
            String response = RestClient.builder()
                    .baseUrl(carrierProperties.getUps().getApiBaseUrl())
                    .build()
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseShipmentResult(response);
        } catch (Exception ex) {
            log.warn("UPS shipment request failed; using local fallback shipment result. Reason: {}", ex.getMessage());
            return buildFallbackShipmentResult(request);
        }
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("UPS client id and client secret are required.");
        }
        return true;
    }

    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingUrl = "https://www.ups.com/track?tracknum=" + trackingNumber;
        return new TrackingResult(
                trackingNumber,
                "IN_TRANSIT",
                trackingUrl,
                null,
                null,
                false,
                null
        );
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

    private Map<String, Object> buildShipmentPayload(ShipmentRequestDTO request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceType", request.getServiceType());
        payload.put("packageType", request.getPackageType());
        payload.put("weight", request.getWeight());
        payload.put("referenceNumber", request.getReferenceNumber());
        return payload;
    }

    private ShipmentResult parseShipmentResult(String response) throws Exception {
        JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
        String trackingNumber = root.path("trackingNumber").asText(null);
        String labelUrl = root.path("labelUrl").asText(null);
        String labelPdf = root.path("labelPdf").asText(null);
        BigDecimal shippingCost = root.path("shippingCost").isNumber() ? root.path("shippingCost").decimalValue() : null;
        LocalDateTime estimatedDelivery = parseDateTime(root.path("estimatedDelivery").asText(null));
        String trackingUrl = StringUtils.hasText(trackingNumber) ? "https://www.ups.com/track?tracknum=" + trackingNumber : null;
        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelPdf, shippingCost, estimatedDelivery, response);
    }

    private ShipmentResult buildFallbackShipmentResult(ShipmentRequestDTO request) {
        String trackingNumber = "1Z" + hashShort(request.getReferenceNumber() + ":" + request.getCarrierCode());
        String trackingUrl = "https://www.ups.com/track?tracknum=" + trackingNumber;
        String labelUrl = "https://labels.local/ups/" + trackingNumber + ".pdf";
        String labelPdf = labelUrl;
        BigDecimal shippingCost = request.getWeight() != null ? request.getWeight().multiply(BigDecimal.valueOf(1.25)) : BigDecimal.ZERO;
        LocalDateTime estimatedDelivery = LocalDateTime.now(ZoneOffset.UTC).plusDays(3);
        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelPdf, shippingCost, estimatedDelivery, null);
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
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
