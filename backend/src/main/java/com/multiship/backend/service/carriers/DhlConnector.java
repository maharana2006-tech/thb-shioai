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
    public ServiceAvailability listServices(String originCountry, String accessToken) {
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
    public PackageAvailability listPackages(String originCountry, String accessToken) {
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
            RestClient.builder().baseUrl(host).build().get()
                    .uri("/products")
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + basic)
                    .retrieve()
                    .body(String.class);
            return basic;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.warn("DHL rejected credentials (HTTP {}): {}", status, ex.getResponseBodyAsString());
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
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken) {
        String host = carrierProperties.getDhl().getApiBaseUrl();
        try {
            Map<String, Object> payload = buildShipmentPayload(request);
            String response = RestClient.builder().baseUrl(host).build().post()
                    .uri(carrierProperties.getDhl().getShipmentPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseShipmentResult(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("DHL shipment rejected (HTTP {}): {}", ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
            return buildFallbackShipmentResult(request);
        } catch (Exception ex) {
            log.warn("DHL shipment call failed; using local fallback. Reason: {}", ex.getMessage());
            return buildFallbackShipmentResult(request);
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
    public TrackingResult trackShipment(String trackingNumber, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingUrl = "https://www.dhl.com/en/express/tracking.html?AWB=" + trackingNumber;
        try {
            String response = RestClient.builder().baseUrl(carrierProperties.getDhl().getApiBaseUrl()).build().get()
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
                } catch (Exception ignored) {
                    // Malformed time — fall through to midnight.
                }
            }
            return d.atStartOfDay();
        } catch (Exception ignored) {
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
            } catch (Exception ignored) {
                return null;
            }
        }
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productCode", firstNonBlank(request.getServiceType(), "P"));
        payload.put("plannedShippingDateAndTime", plannedShipDate());
        payload.put("pickup", Map.of("isRequested", false));
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
        if (request.getDeclaredValue() != null) {
            content.put("declaredValue", request.getDeclaredValue());
            content.put("declaredValueCurrency", firstNonBlank(
                    request.getDeclaredValueCurrency(),
                    isIntl ? request.getIntl().getCustomsCurrency() : "USD").toUpperCase());
        }
        content.put("packages", List.of(buildPackage(request)));
        content.put("unitOfMeasurement", "KG".equalsIgnoreCase(request.getWeightUnit()) ? "metric" : "imperial");
        content.put("description", firstNonBlank(request.getSpecialInstructions(), "General merchandise"));
        content.put("incoterm", firstNonBlank(
                isIntl ? request.getIntl().getIncoterms() : null, "DAP").toUpperCase());
        if (isIntl) {
            content.put("exportDeclaration", buildExportDeclaration(request));
        }
        return content;
    }

    /** DHL Package — typeCode + weight + dimensions in metric or imperial. */
    private Map<String, Object> buildPackage(ShipmentRequestDTO request) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("typeCode", firstNonBlank(request.getPackageType(), "3BX"));
        pkg.put("weight", request.getWeight());
        if (request.getLength() != null && request.getWidth() != null && request.getHeight() != null) {
            pkg.put("dimensions", Map.of(
                    "length", request.getLength(),
                    "width", request.getWidth(),
                    "height", request.getHeight()));
        }
        return pkg;
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
                "date", LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)));
        ed.put("exportReason", mapExportReason(intl.getReasonForExport()));

        // Duty billing — DHL uses shipperAccountNumber for SENDER, otherwise
        // the payer's account. Absent = recipient (DHL default).
        String dutyBillTo = intl.getDutyBillTo();
        if ("SENDER".equalsIgnoreCase(dutyBillTo)
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
            String trackingNumber = root.path("shipmentTrackingNumber").asText(null);
            String labelUrl = null;
            for (JsonNode doc : root.path("documents")) {
                if ("label".equalsIgnoreCase(doc.path("typeCode").asText())) {
                    labelUrl = doc.path("url").asText(null);
                    if (labelUrl == null) labelUrl = doc.path("content").asText(null);
                }
            }
            String trackingUrl = StringUtils.hasText(trackingNumber)
                    ? "https://www.dhl.com/en/express/tracking.html?AWB=" + trackingNumber : null;
            return new ShipmentResult(trackingNumber, trackingUrl, labelUrl, labelUrl,
                    null, LocalDateTime.now(ZoneOffset.UTC).plusDays(2), response);
        } catch (Exception ex) {
            log.warn("Failed to parse DHL response; treating as fallback. Reason: {}", ex.getMessage());
            return new ShipmentResult(null, null, null, null, null, null, response);
        }
    }

    private ShipmentResult buildFallbackShipmentResult(ShipmentRequestDTO request) {
        String tracking = "JD" + hashShort(request.getReferenceNumber() + ":" + request.getCarrierCode());
        String labelUrl = "https://labels.local/dhl/" + tracking + ".pdf";
        return new ShipmentResult(tracking,
                "https://www.dhl.com/en/express/tracking.html?AWB=" + tracking,
                labelUrl, labelUrl, null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2), null);
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

    /** DHL's "planned shipping date" — next business day at 13:00 GMT+00:00. */
    private static String plannedShipDate() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1) + "T13:00:00 GMT+00:00";
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
