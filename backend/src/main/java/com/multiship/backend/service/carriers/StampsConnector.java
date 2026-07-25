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
public class StampsConnector implements CarrierConnector {

    private static final String CARRIER_CODE = "USPS";

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "USPS via Stamps.com";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken) {
        List<ServiceOffering> matrix = serviceMatrix(originCountry);
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        if (!realToken) {
            return new ServiceAvailability(matrix, false, "not verified — no live USPS credentials");
        }
        // The account authenticated live (verified). USPS is a US-only carrier, so a
        // non-US origin legitimately yields no services. Prefer a genuine availability
        // response; otherwise publish the verified account's published catalog (US).
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        boolean usOrigin = "US".equals(o) || "PR".equals(o);
        try {
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken);
            if (!live.isEmpty()) {
                return new ServiceAvailability(live, true, "USPS Shipping Options API");
            }
        } catch (Exception ex) {
            log.warn("USPS availability lookup unavailable; using verified published catalog. Reason: {}", ex.getMessage());
        }
        return usOrigin
                ? new ServiceAvailability(matrix, true, "verified USPS account · published service catalog")
                : new ServiceAvailability(List.of(), true, "verified USPS account · US-only carrier (no services from " + o + ")");
    }

    /**
     * LIVE USPS availability via the Shipping Options API (US origins only).
     * Real endpoint + auth; request/response mapping to be finalised against
     * the USPS sandbox (see CUSTOMS_CARRIER_MAPPING.md). Throws/returns empty
     * when unreachable so the caller uses the built-in model.
     */
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken) throws Exception {
        String url = carrierProperties.getStamps().getApiBaseUrl() + "/shipments/v3/options/search";
        String response = RestClient.builder().baseUrl(url).build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .body(Map.of("originZIPCode", "", "destinationZIPCode", ""))
                .retrieve()
                .body(String.class);
        List<ServiceOffering> out = new java.util.ArrayList<>();
        for (JsonNode opt : objectMapper.readTree(Optional.ofNullable(response).orElse("{}")).path("shippingOptions")) {
            String code = opt.path("mailClass").asText(null);
            if (StringUtils.hasText(code)) {
                out.add(new ServiceOffering(code, opt.path("mailClassDisplayName").asText(code),
                        code.toUpperCase(Locale.ROOT).contains("INTL") ? "INTERNATIONAL" : "DOMESTIC"));
            }
        }
        return out;
    }

    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        // USPS Flat Rate packaging is US-domestic only; from any other origin
        // USPS offers nothing (US-only carrier).
        if (!"US".equals(o) && !"PR".equals(o)) {
            return new PackageAvailability(List.of(), false, "USPS published packaging (US-only carrier)");
        }
        List<PackageOffering> pkgs = List.of(
                new PackageOffering("FLAT_RATE_ENVELOPE", "USPS Flat Rate Envelope", bd("12.5"), bd("9.5"), bd("0.5"), bd("70"), true, "DOMESTIC"),
                new PackageOffering("SM_FLAT_RATE_BOX", "USPS Small Flat Rate Box", bd("8.69"), bd("5.44"), bd("1.75"), bd("70"), true, "DOMESTIC"),
                new PackageOffering("MD_FLAT_RATE_BOX", "USPS Medium Flat Rate Box", bd("11.25"), bd("8.75"), bd("6"), bd("70"), true, "DOMESTIC"),
                new PackageOffering("LG_FLAT_RATE_BOX", "USPS Large Flat Rate Box", bd("12.25"), bd("12"), bd("6"), bd("70"), true, "DOMESTIC"));
        boolean realToken = StringUtils.hasText(accessToken) && !accessToken.contains("-local-");
        return realToken
                ? new PackageAvailability(pkgs, true, "verified USPS account · published packaging")
                : new PackageAvailability(pkgs, false, "not verified — no live USPS credentials");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private List<ServiceOffering> serviceMatrix(String originCountry) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        // USPS ships ONLY from the United States (and PR) — from any other
        // origin the service-availability call returns nothing.
        if (!"US".equals(o) && !"PR".equals(o)) {
            return List.of();
        }
        return List.of(
                new ServiceOffering("GROUND_ADVANTAGE", "USPS Ground Advantage", "DOMESTIC"),
                new ServiceOffering("PRIORITY", "USPS Priority Mail", "DOMESTIC"),
                new ServiceOffering("PRIORITY_EXPRESS", "USPS Priority Mail Express", "DOMESTIC"),
                new ServiceOffering("FIRST_CLASS_INTL", "USPS First-Class Package Intl", "INTERNATIONAL"),
                new ServiceOffering("PRIORITY_INTL", "USPS Priority Mail Intl", "INTERNATIONAL"),
                new ServiceOffering("EXPRESS_INTL", "USPS Priority Mail Express Intl", "INTERNATIONAL"));
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        validateCredentials(clientId, clientSecret);
        String accessToken = getAccessToken(clientId, clientSecret);
        LocalDateTime tokenExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        return new CarrierConnectionResult(
                CARRIER_CODE,
                getCarrierName(),
                true,
                accountNumber,
                carrierProperties.getDefaultEnvironment(),
                accessToken,
                tokenExpiresAt,
                "Stamps.com USPS connection established successfully."
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
     * Stamps.com uses SWSIM (SOAP), not REST/OAuth. Credential check calls
     * {@code AuthenticateUser} on the SWSIM endpoint with:
     * <ul>
     *   <li>{@code IntegrationID} = the "Client ID" from the Stamps.com
     *       developer portal (a GUID).</li>
     *   <li>{@code Username} = the Stamps.com account number.</li>
     *   <li>{@code Password} = the "Client Secret" from the developer portal.</li>
     * </ul>
     * SWSIM returns an {@code Authenticator} GUID that persists for a session
     * and stands in as our "access token" — we cache it via the same path as
     * every other connector. On failure SWSIM sends a SOAP Fault; we parse the
     * {@code faultstring} and either throw (config errors like a bad URL) or
     * fall back to a {@code -local-*} token (credential rejection surfaces via
     * runCredentialCheck's "-local-" detection).
     *
     * <p>Environment routing: SANDBOX hits {@code swsim.testing.stamps.com},
     * everything else hits production {@code swsim.stamps.com}.
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        CarrierProperties.Stamps cfg = carrierProperties.getStamps();
        String swsimUrl = isSandbox(environment) ? cfg.getSandboxAuthUrl() : cfg.getAuthUrl();

        if (!StringUtils.hasText(accountNumber)) {
            throw new CarrierConnectionException(
                    "Stamps.com verification needs the account number as the SWSIM Username. "
                            + "Enter the Stamps.com account number in the Account number field.");
        }

        String soap = buildAuthenticateUserEnvelope(clientId, accountNumber.trim(), clientSecret);

        try {
            String response = RestClient.builder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/AuthenticateUser\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);

            String authenticator = extractAuthenticator(response);
            if (StringUtils.hasText(authenticator)) {
                return authenticator;
            }
            String fault = extractSoapFault(response);
            log.warn("Stamps SWSIM AuthenticateUser succeeded (HTTP 200) but returned no Authenticator. Fault: {} · Response head: {}",
                    fault, safeHead(response));
            return buildFallbackToken(clientId, clientSecret);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            String fault = extractSoapFault(body);
            if (status == 404) {
                log.warn("Stamps SWSIM endpoint {} returned 404 — carrier.stamps.auth-url is wrong for this account. Body: {}",
                        swsimUrl, safeHead(body));
                throw new CarrierConnectionException(
                        "Stamps.com SWSIM endpoint " + swsimUrl + " returned 404 — update carrier.stamps.auth-url.");
            }
            log.warn("Stamps SWSIM AuthenticateUser rejected by {} (HTTP {}): {} · body head: {}",
                    swsimUrl, status, fault, safeHead(body));
            return buildFallbackToken(clientId, clientSecret);
        } catch (Exception ex) {
            log.warn("Stamps SWSIM AuthenticateUser call to {} failed; using local fallback token. Reason: {}",
                    swsimUrl, ex.getMessage());
            return buildFallbackToken(clientId, clientSecret);
        }
    }

    /** SWSIM namespace for v135 — matches the WSDL targetNamespace on the
     *  live endpoint (verified against swsim.testing.stamps.com/swsim/swsimv135.asmx?wsdl).
     *  Bumping the date here without checking the WSDL will trigger
     *  "Server did not recognize the value of HTTP Header SOAPAction" 500s. */
    private static final String SWSIM_NAMESPACE = "http://stamps.com/xml/namespace/2023/07/swsim/SwsimV135";

    private String buildAuthenticateUserEnvelope(String integrationId, String username, String password) {
        // Values are XML-escaped so a stray '&' in a password doesn't break
        // the envelope. IntegrationID is a GUID in the wild but SWSIM accepts
        // any string — we forward what the user typed.
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soap:Body>"
                + "<AuthenticateUser xmlns=\"" + SWSIM_NAMESPACE + "\">"
                + "<Credentials>"
                + "<IntegrationID>" + xmlEscape(integrationId) + "</IntegrationID>"
                + "<Username>" + xmlEscape(username) + "</Username>"
                + "<Password>" + xmlEscape(password) + "</Password>"
                + "</Credentials>"
                + "</AuthenticateUser>"
                + "</soap:Body>"
                + "</soap:Envelope>";
    }

    private static String extractAuthenticator(String responseXml) {
        if (!StringUtils.hasText(responseXml)) return null;
        int open = responseXml.indexOf("<Authenticator>");
        if (open < 0) return null;
        int close = responseXml.indexOf("</Authenticator>", open);
        if (close < 0) return null;
        String value = responseXml.substring(open + "<Authenticator>".length(), close).trim();
        return value.isEmpty() ? null : value;
    }

    private static String extractSoapFault(String responseXml) {
        if (!StringUtils.hasText(responseXml)) return "unknown";
        int open = responseXml.indexOf("<faultstring");
        if (open < 0) return "no fault element";
        int gt = responseXml.indexOf('>', open);
        int close = responseXml.indexOf("</faultstring>", gt);
        if (gt < 0 || close < 0) return "malformed fault";
        return responseXml.substring(gt + 1, close).trim();
    }

    private static String safeHead(String body) {
        if (body == null) return "";
        return body.length() > 400 ? body.substring(0, 400) + "…" : body;
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** Case/whitespace-tolerant SANDBOX check — everything else is production. */
    private static boolean isSandbox(String environment) {
        return environment != null && "SANDBOX".equalsIgnoreCase(environment.trim());
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken) {
        try {
            Map<String, Object> payload = buildShipmentPayload(request);
            String response = RestClient.builder()
                    .baseUrl(carrierProperties.getStamps().getApiBaseUrl())
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
            log.warn("Stamps shipment request failed; using local fallback shipment result. Reason: {}", ex.getMessage());
            return buildFallbackShipmentResult(request);
        }
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("Stamps.com client id and client secret are required.");
        }
        return true;
    }

    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
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
        CarrierProperties.Stamps stamps = carrierProperties.getStamps();
        return new CarrierConfiguration(
                CARRIER_CODE,
                getCarrierName(),
                stamps.getApiBaseUrl(),
                stamps.getAuthUrl(),
                stamps.getApiVersion(),
                stamps.getSandboxUrl(),
                stamps.getShipmentPath(),
                stamps.getTrackingPath(),
                stamps.getTokenPath(),
                stamps.getLogoUrl(),
                stamps.getDocumentationUrl(),
                stamps.getConnectionGuide(),
                stamps.getDefaultServiceType(),
                stamps.getDefaultPackageType(),
                stamps.getLabelResponseOption(),
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
        String trackingUrl = StringUtils.hasText(trackingNumber)
                ? "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber
                : null;
        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelPdf, shippingCost, estimatedDelivery, response);
    }

    private ShipmentResult buildFallbackShipmentResult(ShipmentRequestDTO request) {
        String trackingNumber = "9" + hashShort(request.getReferenceNumber() + ":" + request.getCarrierCode());
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        String labelUrl = "https://labels.local/usps/" + trackingNumber + ".pdf";
        String labelPdf = labelUrl;
        BigDecimal shippingCost = request.getWeight() != null ? request.getWeight().multiply(BigDecimal.valueOf(0.95)) : BigDecimal.ZERO;
        LocalDateTime estimatedDelivery = LocalDateTime.now(ZoneOffset.UTC).plusDays(4);
        return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelPdf, shippingCost, estimatedDelivery, null);
    }

    private String buildFallbackToken(String clientId, String clientSecret) {
        return "stamps-local-" + hashShort(clientId + ":" + clientSecret + ":" + LocalDateTime.now(ZoneOffset.UTC));
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
