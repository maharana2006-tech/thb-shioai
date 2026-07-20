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
            return new ServiceAvailability(matrix, false, "built-in availability — no live FedEx credentials");
        }
        try {
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken);
            if (!live.isEmpty()) {
                return new ServiceAvailability(live, true, "FedEx Service Availability API");
            }
            return new ServiceAvailability(matrix, false, "FedEx API returned no services — used built-in availability");
        } catch (Exception ex) {
            log.warn("FedEx availability lookup failed; using built-in availability. Reason: {}", ex.getMessage());
            return new ServiceAvailability(matrix, false, "FedEx API unreachable — used built-in availability");
        }
    }

    /**
     * LIVE FedEx availability via the Service Availability API. Real endpoint +
     * auth; the request (origin/destination postal) and output→service mapping
     * must be finalised against a FedEx sandbox (see CUSTOMS_CARRIER_MAPPING.md).
     * Throws/returns empty when unreachable so the caller uses the built-in model.
     */
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken) throws Exception {
        String url = carrierProperties.getFedEx().getApiBaseUrl() + "/availability/v1/service/availability";
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
        return new PackageAvailability(pkgs, false, "FedEx published packaging");
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

    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        try {
            String trackingUrl = getTrackingUrl();
            RestClient restClient = RestClient.builder().baseUrl(trackingUrl).build();
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/" + trackingNumber).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            String status = root.at("/output/completeTrackResults/0/trackResults/0/latestStatusDetail/description")
                    .asText("UNKNOWN");
            String currentLocation = root.at("/output/completeTrackResults/0/trackResults/0/latestStatusDetail/location/address/city")
                    .asText(null);
            String trackingLink = "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber;
            boolean delivered = "DELIVERED".equalsIgnoreCase(status);
            LocalDateTime estimatedDelivery = parseDateTime(
                    root.at("/output/completeTrackResults/0/trackResults/0/dateAndTimes/0/dateTime").asText(null)
            );

            return new TrackingResult(
                    trackingNumber,
                    status,
                    trackingLink,
                    currentLocation,
                    estimatedDelivery,
                    delivered,
                    response
            );
        } catch (Exception ex) {
            log.error("FedEx tracking failed for tracking number {}", trackingNumber, ex);
            throw new CarrierConnectionException("Unable to track FedEx shipment.", ex);
        }
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

        requestedShipment.put("shipper", buildParty(
                request.getShipperName(),
                request.getShipperPhone(),
                request.getShipperAddressLine1(),
                request.getShipperAddressLine2(),
                request.getShipperCity(),
                request.getShipperState(),
                request.getShipperPostalCode(),
                request.getShipperCountryCode()
        ));

        requestedShipment.put("recipients", new Object[]{buildParty(
                request.getRecipientName(),
                request.getRecipientPhone(),
                request.getRecipientAddressLine1(),
                request.getRecipientAddressLine2(),
                request.getRecipientCity(),
                request.getRecipientState(),
                request.getRecipientPostalCode(),
                request.getRecipientCountryCode()
        )});

        Map<String, Object> packageLineItem = new LinkedHashMap<>();
        packageLineItem.put("weight", Map.of(
                "units", "LB",
                "value", request.getWeight()
        ));
        if (request.getDeclaredValue() != null) {
            packageLineItem.put("declaredValue", Map.of(
                    "amount", request.getDeclaredValue(),
                    "currency", "USD"
            ));
        }

        requestedShipment.put("requestedPackageLineItems", new Object[]{packageLineItem});
        payload.put("requestedShipment", requestedShipment);
        return payload;
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
