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

    /** SWSIM namespace for v135 — matches the WSDL targetNamespace on the live
     *  endpoint (verified against swsim.testing.stamps.com/swsim/swsimv135.asmx?wsdl).
     *  Bumping the date here without checking the WSDL will trigger "Server did
     *  not recognize the value of HTTP Header SOAPAction" 500s. Used by BOTH
     *  AuthenticateUser (getAccessToken) AND CreateIndicium (createShipment). */
    private static final String SWSIM_NAMESPACE = "http://stamps.com/xml/namespace/2023/07/swsim/SwsimV135";

    private String buildAuthenticateUserEnvelope(String integrationId, String username, String password) {
        // Values are XML-escaped so a stray '&' in a password doesn't break the
        // envelope. IntegrationID is a GUID in the wild but SWSIM accepts any
        // string — we forward what the user typed.
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

    private static String safeHead(String body) {
        if (body == null) return "";
        return body.length() > 400 ? body.substring(0, 400) + "…" : body;
    }

    /** Case/whitespace-tolerant SANDBOX check — everything else is production. */
    private static boolean isSandbox(String environment) {
        return environment != null && "SANDBOX".equalsIgnoreCase(environment.trim());
    }

    /**
     * SWSIM {@code CreateIndicium} — the SOAP call that produces the actual
     * label PDF, prints the CN22/CN23 customs form onto it automatically when
     * a {@code CustomsInfo} block is present, and returns the tracking
     * number + label URL.
     *
     * <p>Content type is {@code text/xml} (SWSIM won't accept
     * application/xml); SOAPAction is quoted and matches the WSDL. Auth is
     * via the {@code Authenticator} element in the body — Stamps.com sessions
     * are stateful; every call returns a new Authenticator, and the token we
     * received from {@code getAccessToken} was seeded by AuthenticateUser.
     */
    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken) {
        String swsimUrl = carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildCreateIndiciumEnvelope(request, accessToken);
        try {
            String response = RestClient.builder().baseUrl(swsimUrl).build()
                    .post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/CreateIndicium\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseCreateIndiciumResponse(response, request);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps CreateIndicium rejected by {} (HTTP {}): {}",
                    swsimUrl, ex.getStatusCode().value(), fault);
            return buildFallbackShipmentResult(request);
        } catch (Exception ex) {
            log.warn("Stamps CreateIndicium call to {} failed; using local fallback shipment result. Reason: {}",
                    swsimUrl, ex.getMessage());
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

    /**
     * URL-only tracking. SWSIM's TrackShipment requires a valid Authenticator
     * so this 1-arg variant only returns the public USPS tracking link.
     * Matches the honest stub Sprints 12/13/14 established for FedEx / UPS /
     * DHL — the 2-arg authenticated variant does the real work.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        return new TrackingResult(trackingNumber, "UNKNOWN", trackingUrl, null, null, false, null);
    }

    /**
     * SWSIM {@code TrackShipment} — SOAP call following the Sprint 4 scaffold.
     * The Authenticator returned by getAccessToken (via AuthenticateUser) is
     * threaded in the SOAP body. TrackingNumber goes in the request; Carrier
     * defaults to USPS. Response shape:
     * <pre>
     * TrackShipmentResponse.
     *   Authenticator (rotated — future SWSIM calls should use this),
     *   TrackingEvents.TrackingEvent[] (oldest-first per SWSIM convention).
     * </pre>
     * Each TrackingEvent carries TrackingEventType (Delivered / OutForDelivery
     * / ...), Timestamp, Event (description), and address fields (City,
     * State, Zip, Country) that we compose into a "City, ST" location.
     *
     * <p>SWSIM already returns oldest-first, so no reversal (unlike
     * FedEx / UPS / DHL). Any {@code -local-*} authenticator short-circuits
     * to the URL-only stub — same convention Sprints 12/13/14 established.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String swsimUrl = carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildTrackShipmentEnvelope(trackingNumber, accessToken);
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        try {
            String response = RestClient.builder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/TrackShipment\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);

            java.util.List<TrackingEvent> events = parseSwsimTrackingEvents(response);
            String status = events.isEmpty()
                    ? "UNKNOWN"
                    : firstNonBlankStr(events.get(events.size() - 1).status(),
                            events.get(events.size() - 1).description(), "UNKNOWN");
            String currentLocation = events.isEmpty() ? null : events.get(events.size() - 1).location();
            boolean delivered = events.stream().anyMatch(e ->
                    "Delivered".equalsIgnoreCase(e.status())
                    || (e.description() != null && e.description().toLowerCase().contains("delivered")));

            return new TrackingResult(trackingNumber, status, trackingUrl, currentLocation,
                    null, delivered, response, events);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps SWSIM TrackShipment rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), fault);
            return trackShipment(trackingNumber);
        } catch (Exception ex) {
            log.warn("Stamps SWSIM TrackShipment failed for {}; falling back to URL-only. Reason: {}",
                    trackingNumber, ex.getMessage());
            return trackShipment(trackingNumber);
        }
    }

    /** Build the SWSIM TrackShipment SOAP envelope. */
    String buildTrackShipmentEnvelope(String trackingNumber, String authenticator) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<TrackShipment xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<TrackingNumber>").append(xmlEscape(nonBlank(trackingNumber, ""))).append("</TrackingNumber>");
        xml.append("<Carrier>USPS</Carrier>");
        xml.append("</TrackShipment>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse SWSIM's TrackingEvents block into our neutral TrackingEvent list.
     * SWSIM already emits oldest-first so no reversal. Regex-based rather
     * than a full XML parse — the response is well-formed, small, and we
     * only want a handful of fields per event.
     */
    java.util.List<TrackingEvent> parseSwsimTrackingEvents(String responseXml) {
        if (!StringUtils.hasText(responseXml)) return java.util.List.of();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<TrackingEvent>([\\s\\S]*?)</TrackingEvent>")
                .matcher(responseXml);
        java.util.List<TrackingEvent> out = new java.util.ArrayList<>();
        while (m.find()) {
            String body = m.group(1);
            String type = extractElement(body, "TrackingEventType");
            String desc = extractElement(body, "Event");
            LocalDateTime ts = parseSwsimTimestamp(extractElement(body, "Timestamp"));
            String location = buildSwsimLocation(
                    extractElement(body, "City"),
                    extractElement(body, "State"),
                    extractElement(body, "Country"));
            out.add(new TrackingEvent(ts, type, desc == null ? "" : desc, location));
        }
        return java.util.List.copyOf(out);
    }

    /**
     * SWSIM timestamps look like {@code 2024-01-15T14:30:00} or
     * {@code 2024-01-15T14:30:00-05:00}. Try LocalDateTime first, then
     * OffsetDateTime as a fallback — same pattern the DHL helper uses.
     */
    static LocalDateTime parseSwsimTimestamp(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    /** Build a "City, ST US" location string from SWSIM's split fields. */
    static String buildSwsimLocation(String city, String state, String country) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(city)) sb.append(city);
        if (StringUtils.hasText(state)) sb.append(sb.length() > 0 ? ", " : "").append(state);
        if (StringUtils.hasText(country)) sb.append(sb.length() > 0 ? " " : "").append(country);
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String firstNonBlankStr(String... candidates) {
        if (candidates == null) return "";
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    /**
     * SWSIM {@code GetRates} — SOAP call that quotes every USPS class of
     * service the lane supports in one round-trip. No {@code ServiceType} on
     * the Rate block → SWSIM returns the full ladder (Priority Mail, Ground
     * Advantage, Priority Mail Express, plus the international variants for
     * non-US destinations). SOAPAction + envelope shape follow the same
     * pattern the Sprint 4 CreateIndicium and Sprint 14 TrackShipment
     * connectors established.
     *
     * <p>Response shape:
     * <pre>
     * GetRatesResponse.
     *   Authenticator (rotated — SWSIM sessions are stateful),
     *   Rates.Rate[] (one per service):
     *     ServiceType         → USPS code ("USPS PM", "USPS GA", ...)
     *     ServiceDescription  → "USPS Priority Mail" (sometimes missing)
     *     Amount              → total postage, always USD for USPS
     *     DeliverDays         → integer days ("2") or range ("1-3") or absent
     *     DeliveryDate        → optional ISO date
     * </pre>
     *
     * <p>Fallback tokens ({@code -local-*}) short-circuit to an empty list —
     * same auth-degraded convention Sprints 12/13/14/18 established.
     */
    @Override
    public java.util.List<RateOption> getRates(ShipmentRequestDTO request, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return java.util.List.of();
        }
        String swsimUrl = carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildGetRatesEnvelope(request, accessToken);
        try {
            String response = RestClient.builder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/GetRates\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseGetRatesResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps SWSIM GetRates rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), fault);
            return java.util.List.of();
        } catch (Exception ex) {
            log.warn("Stamps SWSIM GetRates failed; returning empty rate list. Reason: {}",
                    ex.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Build the SWSIM GetRates SOAP envelope. No {@code ServiceType} — omitting
     * it asks SWSIM for the full rate ladder. Country only when non-US (SWSIM
     * treats absent Country as US and errors when both are set).
     */
    String buildGetRatesEnvelope(ShipmentRequestDTO request, String authenticator) {
        StringBuilder xml = new StringBuilder(768);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<GetRates xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<Rate>");
        xml.append("<From><ZIPCode>")
                .append(xmlEscape(nonBlank(request.getShipperPostalCode(), "")))
                .append("</ZIPCode></From>");
        xml.append("<To>");
        xml.append("<ZIPCode>")
                .append(xmlEscape(nonBlank(request.getRecipientPostalCode(), "")))
                .append("</ZIPCode>");
        String country = nonBlank(request.getRecipientCountryCode(), "US");
        if (!"US".equalsIgnoreCase(country)) {
            xml.append("<Country>").append(xmlEscape(country)).append("</Country>");
        }
        xml.append("</To>");
        // SWSIM GetRates is single-package; use the first for the quote.
        com.multiship.backend.dto.PackageDetailDTO firstPkg = request.effectivePackages().get(0);
        xml.append("<WeightOz>").append(xmlEscape(weightInOz(firstPkg))).append("</WeightOz>");
        xml.append("<PackageType>")
                .append(xmlEscape(nonBlank(
                        nonBlank(firstPkg.getPackageType(), request.getPackageType()), "Package")))
                .append("</PackageType>");
        xml.append("<ShipDate>")
                .append(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .append("</ShipDate>");
        if (request.getDeclaredValue() != null) {
            xml.append("<DeclaredValue>")
                    .append(xmlEscape(request.getDeclaredValue().toPlainString()))
                    .append("</DeclaredValue>");
        }
        xml.append("</Rate>");
        xml.append("</GetRates>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse a GetRates SOAP response into carrier-neutral RateOptions.
     * Regex-based (same approach as parseSwsimTrackingEvents) — the response
     * is well-formed, small, and we only need a handful of fields per rate.
     * Package-visible so tests can assert against canned response XML.
     */
    java.util.List<RateOption> parseGetRatesResponse(String responseXml) {
        if (!StringUtils.hasText(responseXml)) return java.util.List.of();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<Rate>([\\s\\S]*?)</Rate>")
                .matcher(responseXml);
        java.util.List<RateOption> out = new java.util.ArrayList<>();
        while (m.find()) {
            String body = m.group(1);
            String serviceCode = extractElement(body, "ServiceType");
            if (!StringUtils.hasText(serviceCode)) continue;
            String serviceName = extractElement(body, "ServiceDescription");
            if (!StringUtils.hasText(serviceName)) serviceName = uspsServiceName(serviceCode);

            String amount = extractElement(body, "Amount");
            java.math.BigDecimal totalAmount = parseSwsimAmount(amount);
            if (totalAmount == null) continue;

            Integer transitDays = parseSwsimDeliverDays(extractElement(body, "DeliverDays"));
            LocalDateTime estimatedDelivery = parseSwsimTimestamp(
                    extractElement(body, "DeliveryDate"));

            // USPS bills in USD; SWSIM has no currency element on rates, so
            // we hard-code USD rather than defaulting via a helper.
            out.add(new RateOption("USPS", serviceCode, serviceName, totalAmount,
                    "USD", estimatedDelivery, transitDays));
        }
        return java.util.List.copyOf(out);
    }

    /** SWSIM Amount is a decimal string ("10.20"); tolerate money-formatted
     *  values ("$10.20") that SWSIM occasionally emits on error responses. */
    private static java.math.BigDecimal parseSwsimAmount(String value) {
        if (!StringUtils.hasText(value)) return null;
        String cleaned = value.trim().replace("$", "").replace(",", "");
        try {
            return new java.math.BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** SWSIM {@code DeliverDays} is usually an integer ("2") but sometimes a
     *  range ("1-3"). For a range we return the LOWER bound (matches how
     *  most carrier UIs display "as fast as N days"). Null when absent or
     *  unparseable. */
    static Integer parseSwsimDeliverDays(String value) {
        if (!StringUtils.hasText(value)) return null;
        String v = value.trim();
        int dash = v.indexOf('-');
        String head = dash >= 0 ? v.substring(0, dash).trim() : v;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Map a USPS service code to its human-readable name. Used when the
     *  GetRates response omits ServiceDescription (occasional on error paths). */
    private static String uspsServiceName(String code) {
        return switch (code == null ? "" : code.trim().toUpperCase()) {
            case "USPS PM" -> "USPS Priority Mail";
            case "USPS PME" -> "USPS Priority Mail Express";
            case "USPS GA" -> "USPS Ground Advantage";
            case "USPS FCM" -> "USPS First-Class Mail";
            case "USPS MM" -> "USPS Media Mail";
            case "USPS PMI" -> "USPS Priority Mail International";
            case "USPS PMEI" -> "USPS Priority Mail Express International";
            case "USPS GXG" -> "USPS Global Express Guaranteed";
            case "USPS FCMI" -> "USPS First-Class Mail International";
            case "USPS FCPIS" -> "USPS First-Class Package International Service";
            default -> "USPS " + (code == null ? "" : code);
        };
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

    /**
     * SWSIM {@code CreateIndicium} SOAP envelope. Every field name below is
     * from the v135 WSDL — SWSIM is picky about element order and casing,
     * so this is hand-built rather than reflected off a POJO.
     *
     * <p>Customs behaviour: when {@code request.intl} is present and ready,
     * we emit a {@code CustomsInfo} block. SWSIM then auto-generates the
     * appropriate customs form (CN22 for goods ≤ $400 on First-Class /
     * Ground Advantage Intl, CN23 for larger values or Priority Mail Intl)
     * and PRINTS IT ONTO THE LABEL PDF returned by CreateIndicium — no
     * separate PDF generation on our side. Domestic shipments skip the
     * block entirely.
     *
     * <p>Weight goes on the wire in ounces (SWSIM's {@code WeightOz}). Our
     * DTO carries LB/KG; we convert via {@link com.multiship.backend.util.UnitConverter}.
     */
    private String buildCreateIndiciumEnvelope(ShipmentRequestDTO request, String authenticator) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<CreateIndicium xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<IntegratorTxID>")
                .append(xmlEscape(nonBlank(request.getReferenceNumber(), "TX-" + System.currentTimeMillis())))
                .append("</IntegratorTxID>");

        // Rate: the class of service + package + weight. SWSIM re-validates
        // this against its own rate engine, so mismatches (weight over the
        // service's max) fail here before the label is printed.
        //
        // Sprint 28 — SWSIM's CreateIndicium is fundamentally single-package
        // (one label per SOAP call). We emit the FIRST package's shape and
        // log a warning when more are supplied; multi-package USPS labels
        // require N separate CreateIndicium calls, tracked in a follow-up.
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> packages = request.effectivePackages();
        if (packages.size() > 1) {
            log.warn("Stamps CreateIndicium is single-package; got {} packages — emitting the first only. " +
                    "Multi-package USPS labels need N SOAP calls (future work).", packages.size());
        }
        com.multiship.backend.dto.PackageDetailDTO firstPkg = packages.get(0);
        String weightOz = weightInOz(firstPkg);
        xml.append("<Rate>");
        appendServiceRate(xml, request, firstPkg, weightOz);
        xml.append("</Rate>");

        // From/To are separate blocks; addresses appear twice (once inside
        // Rate, once here) — that's the SWSIM shape.
        xml.append("<From>");
        appendAddress(xml, "FullName", request.getShipperName(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(), null,
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getShipperPhone());
        xml.append("</From>");
        xml.append("<To>");
        appendAddress(xml, "FullName", request.getRecipientName(),
                request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                request.getRecipientAddressLine3(),
                request.getRecipientCity(), request.getRecipientState(),
                request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                request.getRecipientPhone());
        xml.append("</To>");

        xml.append("<CustomerID>").append(xmlEscape(nonBlank(request.getReferenceNumber(), ""))).append("</CustomerID>");

        // Sprint 25 — Print Return Label. SWSIM's CreateIndicium accepts
        // {@code IsReturnLabel=true} at the top level; USPS then prints a
        // return-format label with the addresses interpreted as the
        // recipient (customer) sending BACK to the sender (retailer).
        // Callers should still populate From = return depot / retailer and
        // To = customer's return-from address.
        if (Boolean.TRUE.equals(request.getIsReturn())) {
            xml.append("<IsReturnLabel>true</IsReturnLabel>");
        }

        // CustomsInfo drives CN22/CN23 auto-print. Emitted only when the
        // shipment is international and the customs block is complete.
        if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
            appendCustomsInfo(xml, request);
        }

        xml.append("</CreateIndicium>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    private void appendServiceRate(StringBuilder xml, ShipmentRequestDTO request,
                                    com.multiship.backend.dto.PackageDetailDTO p, String weightOz) {
        xml.append("<From><ZIPCode>").append(xmlEscape(nonBlank(request.getShipperPostalCode(), "")))
                .append("</ZIPCode></From>");
        xml.append("<To>");
        xml.append("<ZIPCode>").append(xmlEscape(nonBlank(request.getRecipientPostalCode(), ""))).append("</ZIPCode>");
        String country = nonBlank(request.getRecipientCountryCode(), "US");
        if (!"US".equalsIgnoreCase(country)) {
            xml.append("<Country>").append(xmlEscape(country)).append("</Country>");
        }
        xml.append("</To>");
        xml.append("<ServiceType>").append(xmlEscape(nonBlank(request.getServiceType(), "USPS GA"))).append("</ServiceType>");
        xml.append("<PackageType>").append(xmlEscape(
                nonBlank(nonBlank(p.getPackageType(), request.getPackageType()), "Package"))).append("</PackageType>");
        xml.append("<WeightOz>").append(xmlEscape(weightOz)).append("</WeightOz>");
        xml.append("<ShipDate>").append(java.time.LocalDate.now(java.time.ZoneOffset.UTC)).append("</ShipDate>");
        java.math.BigDecimal declared = p.getDeclaredValue() != null
                ? p.getDeclaredValue() : request.getDeclaredValue();
        if (declared != null) {
            xml.append("<DeclaredValue>").append(xmlEscape(declared.toPlainString()))
                    .append("</DeclaredValue>");
        }
        // Sprint 27 — SWSIM Rate block accepts a HazardousMaterials boolean.
        // USPS heavily restricts DG (most air services are refused, ground
        // services accept a limited set — ORM-D-style small quantities).
        // The flag is mostly for operator visibility + carrier acceptance
        // routing; SWSIM validates the rest server-side and rejects when
        // the class/service combination isn't allowed.
        if (request.getDangerousGoods() != null
                && request.getDangerousGoods().isReadyForCarrier()) {
            xml.append("<HazardousMaterials>true</HazardousMaterials>");
        }
    }

    /**
     * SWSIM Address block. Order matters — FullName / FirstName / LastName
     * before Address1, then City / State / ZIPCode, then Country. Empty
     * elements are omitted rather than sent blank; SWSIM tolerates absence
     * but rejects empty strings on some fields.
     *
     * <p>SWSIM {@code CreateIndicium} only exposes Address1 + Address2 — no
     * Address3 element on the schema. When {@code line3} is non-blank the
     * caller passes it and we concatenate onto Address2 with a space
     * separator ({@code "Apt 42 Chiyoda-ku"}). This is the standard USPS
     * workaround; USPS delivery agents parse the compound line just fine.
     * A non-blank line3 with a blank line2 goes into Address2 by itself.
     */
    private void appendAddress(StringBuilder xml, String nameField, String name,
                                String line1, String line2, String line3,
                                String city, String state, String postal, String country,
                                String phone) {
        if (StringUtils.hasText(name)) {
            xml.append("<").append(nameField).append(">")
                    .append(xmlEscape(name))
                    .append("</").append(nameField).append(">");
        }
        if (StringUtils.hasText(line1)) xml.append("<Address1>").append(xmlEscape(line1)).append("</Address1>");
        String address2 = joinSwsimAddress2(line2, line3);
        if (StringUtils.hasText(address2)) xml.append("<Address2>").append(xmlEscape(address2)).append("</Address2>");
        if (StringUtils.hasText(city)) xml.append("<City>").append(xmlEscape(city)).append("</City>");
        if (StringUtils.hasText(state)) xml.append("<State>").append(xmlEscape(state)).append("</State>");
        if (StringUtils.hasText(postal)) xml.append("<ZIPCode>").append(xmlEscape(postal)).append("</ZIPCode>");
        String c = nonBlank(country, "US");
        if (!"US".equalsIgnoreCase(c)) {
            xml.append("<Country>").append(xmlEscape(c)).append("</Country>");
        }
        if (StringUtils.hasText(phone)) xml.append("<PhoneNumber>").append(xmlEscape(phone)).append("</PhoneNumber>");
    }

    /**
     * SWSIM {@code CustomsInfo} block. When present, SWSIM's CreateIndicium
     * response includes a label PDF with either CN22 or CN23 pre-printed on
     * it. Which form: SWSIM picks CN22 for goods ≤ $400 on eligible services
     * (First-Class Intl, Ground Advantage Intl); CN23 for larger values or
     * Priority Mail Intl. We can't override that decision from the request.
     *
     * <p>{@code ContentType} maps our reason for export to SWSIM's closed
     * enum: Merchandise / Gift / Sample / ReturnedGoods / Documents /
     * HumanitarianDonation / Other.
     */
    private void appendCustomsInfo(StringBuilder xml, ShipmentRequestDTO request) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        xml.append("<CustomsInfo>");
        xml.append("<ContentType>").append(mapContentType(intl.getReasonForExport())).append("</ContentType>");
        String notes = nonBlank(intl.getImporterCompanyReg(), "");
        if (!notes.isEmpty()) {
            xml.append("<Comments>").append(xmlEscape(notes)).append("</Comments>");
        }
        xml.append("<CustomsLines>");
        String weightUnit = intl.getWeightUnit();
        for (com.multiship.backend.dto.CustomsCommodityDTO c : intl.getCommodities()) {
            xml.append("<CustomsLine>");
            xml.append("<Description>").append(xmlEscape(nonBlank(c.getDescription(), ""))).append("</Description>");
            xml.append("<Quantity>").append(c.getQuantity() != null ? c.getQuantity() : 1).append("</Quantity>");
            java.math.BigDecimal lineValue = c.lineTotalValue();
            if (lineValue != null) {
                xml.append("<Value>").append(xmlEscape(lineValue.toPlainString())).append("</Value>");
            }
            if (c.getUnitWeight() != null) {
                java.math.BigDecimal oz = com.multiship.backend.util.UnitConverter
                        .toOunces(c.getUnitWeight(), weightUnit);
                if (oz != null) {
                    xml.append("<WeightOz>").append(xmlEscape(oz.toPlainString())).append("</WeightOz>");
                }
            }
            if (StringUtils.hasText(c.getHsCode())) {
                xml.append("<HSTariffNumber>").append(xmlEscape(c.getHsCode())).append("</HSTariffNumber>");
            }
            if (StringUtils.hasText(c.getCountryOfOrigin())) {
                xml.append("<CountryOfOrigin>").append(xmlEscape(c.getCountryOfOrigin())).append("</CountryOfOrigin>");
            }
            if (StringUtils.hasText(c.getSku())) {
                xml.append("<sku>").append(xmlEscape(c.getSku())).append("</sku>");
            }
            xml.append("</CustomsLine>");
        }
        xml.append("</CustomsLines>");
        xml.append("</CustomsInfo>");
    }

    /** Reason for export → SWSIM ContentType enum. */
    private static String mapContentType(String reason) {
        if (reason == null) return "Merchandise";
        return switch (reason.trim().toUpperCase()) {
            case "SALE" -> "Merchandise";
            case "GIFT" -> "Gift";
            case "SAMPLE" -> "Sample";
            case "RETURN" -> "ReturnedGoods";
            case "DOCUMENTS" -> "Documents";
            case "REPAIR" -> "Other"; // SWSIM has no repair-specific value
            default -> "Merchandise";
        };
    }

    /** Total shipment weight in ounces — the unit SWSIM speaks natively. */
    private static String weightInOz(com.multiship.backend.dto.PackageDetailDTO p) {
        java.math.BigDecimal oz = com.multiship.backend.util.UnitConverter
                .toOunces(p.getWeight(), p.getWeightUnit());
        return oz == null ? "0" : oz.toPlainString();
    }

    private static String nonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * Compose SWSIM's Address2 element from our optional line2 + line3.
     * Both blank → empty string (caller skips the element). Only one set →
     * that value alone. Both set → concatenate with a single space so USPS
     * delivery gets both bits of context onto the printed label.
     */
    static String joinSwsimAddress2(String line2, String line3) {
        boolean has2 = StringUtils.hasText(line2);
        boolean has3 = StringUtils.hasText(line3);
        if (!has2 && !has3) return "";
        if (has2 && has3) return line2.trim() + " " + line3.trim();
        return has2 ? line2.trim() : line3.trim();
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Parse a CreateIndicium SOAP response for the fields we care about:
     * TrackingNumber, URL (the label PDF), StampsTxID (SWSIM's own id), plus
     * the new Authenticator for the next call.
     */
    private ShipmentResult parseCreateIndiciumResponse(String responseXml, ShipmentRequestDTO request) {
        String tracking = extractElement(responseXml, "TrackingNumber");
        String url = extractElement(responseXml, "URL");
        // SWSIM returns the total postage under Rate.Amount when the label
        // prints successfully; fall back to null (client shows unpriced).
        java.math.BigDecimal cost = null;
        String amount = extractElement(responseXml, "Amount");
        if (StringUtils.hasText(amount)) {
            try {
                cost = new java.math.BigDecimal(amount);
            } catch (NumberFormatException ignored) {
                // SWSIM sometimes returns currency-formatted amounts on error
                // responses; treat those as unpriced rather than crashing.
            }
        }
        String trackingUrl = StringUtils.hasText(tracking)
                ? "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + tracking
                : null;
        java.time.LocalDateTime estimated = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(5);
        return new ShipmentResult(tracking, trackingUrl, url, url, cost, estimated, responseXml);
    }

    /** Extract the text between the first occurrence of {@code <elem>...</elem>}. */
    private static String extractElement(String xml, String elem) {
        if (xml == null) return null;
        int open = xml.indexOf("<" + elem + ">");
        if (open < 0) {
            // Try namespaced variant: <ns:elem>
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<[a-zA-Z0-9]+:" + elem + ">([^<]+)</[a-zA-Z0-9]+:" + elem + ">")
                    .matcher(xml);
            return m.find() ? m.group(1).trim() : null;
        }
        int close = xml.indexOf("</" + elem + ">", open);
        if (close < 0) return null;
        return xml.substring(open + elem.length() + 2, close).trim();
    }

    private static String extractSoapFault(String responseXml) {
        if (!StringUtils.hasText(responseXml)) return "unknown";
        String fault = extractElement(responseXml, "faultstring");
        return fault == null ? "no fault element" : fault;
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
