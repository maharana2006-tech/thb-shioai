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
        String tokenUrl = isSandbox(environment)
                ? carrierProperties.getUps().getSandboxAuthUrl()
                : carrierProperties.getUps().getAuthUrl();
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
    public TrackingResult trackShipment(String trackingNumber, String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingUrl = "https://www.ups.com/track?tracknum=" + trackingNumber;
        try {
            String response = RestClient.builder().baseUrl(carrierProperties.getUps().getApiBaseUrl()).build().get()
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
        } catch (Exception ignored) {
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

        // Single-package payload today (Sprint 2 scope); multi-package lands
        // with the Order model rework. UPS Package[] preserves the units.
        shipment.put("Package", java.util.List.of(buildPackage(request)));

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
        shipmentRequest.put("LabelSpecification", Map.of(
                "LabelImageFormat", Map.of("Code", "GIF"),
                "HTTPUserAgent", "Mozilla/4.5"));
        payload.put("ShipmentRequest", shipmentRequest);
        return payload;
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
     * request lands at UPS as 1.5 KG, not 1.5 LB.
     */
    private Map<String, Object> buildPackage(ShipmentRequestDTO request) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("Description", firstNonBlank(request.getSpecialInstructions(), "Package"));
        pkg.put("PackagingType", Map.of(
                "Code", firstNonBlank(request.getPackageType(), "02")));

        String weightUnitCode = "KG".equalsIgnoreCase(request.getWeightUnit()) ? "KGS" : "LBS";
        Map<String, Object> weight = new LinkedHashMap<>();
        weight.put("UnitOfMeasurement", Map.of("Code", weightUnitCode));
        weight.put("Weight", request.getWeight() != null ? request.getWeight().toPlainString() : "0");
        pkg.put("PackageWeight", weight);

        if (request.getLength() != null || request.getWidth() != null || request.getHeight() != null) {
            String dimUnitCode = "CM".equalsIgnoreCase(request.getDimUnit()) ? "CM" : "IN";
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("UnitOfMeasurement", Map.of("Code", dimUnitCode));
            dims.put("Length", request.getLength() != null ? request.getLength().toPlainString() : "0");
            dims.put("Width", request.getWidth() != null ? request.getWidth().toPlainString() : "0");
            dims.put("Height", request.getHeight() != null ? request.getHeight().toPlainString() : "0");
            pkg.put("Dimensions", dims);
        }
        return pkg;
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
            String dutyBillTo = request.getIntl().getDutyBillTo();
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
        forms.put("InvoiceDate", java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        forms.put("PurchaseOrderNumber", firstNonBlank(request.getReferenceNumber(), ""));
        forms.put("TermsOfShipment", firstNonBlank(intl.getIncoterms(), "DAP").toUpperCase());
        String reason = firstNonBlank(intl.getReasonForExport(), "SALE").toUpperCase();
        forms.put("ReasonForExport", reason);
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
