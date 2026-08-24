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
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
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
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken, environment);
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
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken, String environment) throws Exception {
        String url = getBaseUrl(environment) + "/availability/v1/service/availability";
        String response = HttpClients.newBuilder().baseUrl(url).build()
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
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
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
            RestClient restClient = HttpClients.newBuilder().baseUrl(tokenUrl).build();
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
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        // F7 fix — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream in buildShipmentPayload (line ~955)
        // which shipped international parcels as US domestic. Fail at
        // the boundary instead.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "FedEx shipment requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before generating a label.");
        }
        try {
            String shipmentUrl = getShipmentUrl(environment);
            RestClient restClient = HttpClients.newBuilder().baseUrl(shipmentUrl).build();
            Map<String, Object> payload = buildShipmentPayload(request);

            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            return parseShipmentResult(response);
        } catch (com.multiship.backend.service.carriers.exceptions.CarrierException cex) {
            throw cex;
        } catch (Exception ex) {
            log.warn("FedEx createShipment failed: {}", ex.getMessage());
            // FDX-A — no more sandbox fake-label fallback. Pre-fix, any
            // exception in sandbox / non-production silently synthesised a
            // "78xxxxxxxxxx" tracking + https://labels.local/fedex/... URL,
            // masking real config errors (wrong serviceType, missing customs,
            // bad credentials) as a "successful" sandbox test. Now every
            // environment sees the real carrier error — the exception mapper
            // already produces an actionable operator message and the
            // idempotency layer still prevents double-charges on retry.
            throw com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper
                    .map("FEDEX", ex, "createShipment");
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
    public java.util.List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return java.util.List.of();
        }
        // FDX-B1 — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" in buildRateRequestBody, which returned
        // US-domestic quotes for what should have been an international
        // rate-shop. The operator would see believable-but-wrong pricing
        // and ship anyway. Same guard shape F7 added to createShipment.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "FedEx rate-shop requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before rate-shopping — "
                            + "quotes without a destination silently fall to US-domestic.");
        }
        // Guard: caller must supply either a shipment-level weight or a
        // packages[] with at least one entry. Rate-shopping with zero weight
        // is meaningless — return empty so the operator sees "no FedEx rates
        // returned" rather than a spurious BigDecimal.ONE quote.
        boolean noWeight = request.getWeight() == null
                && (request.getPackages() == null || request.getPackages().isEmpty()
                    || request.effectivePackages().stream().allMatch(p -> p.getWeight() == null));
        if (noWeight) {
            log.warn("FedEx rate-shop skipped: request has no weight and no packages[] with weight.");
            return java.util.List.of();
        }
        try {
            java.util.Map<String, Object> body = buildRateRequestBody(request);
            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build().post()
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
     * Build the /rate/v1/rates/quotes request body. Package-visible so tests
     * can assert against the JSON shape without needing a live carrier.
     *
     * <p>Multi-package: one {@code requestedPackageLineItems} entry per box
     * from {@code request.effectivePackages()}. Each entry carries its own
     * weight; dimensions ride along when the box has them (FedEx needs dims
     * to compute DIM-weight surcharges — omitting them under-quotes any
     * oversize-but-light shipment).
     *
     * <p>Legacy single-package path (no packages[]) falls back to a single
     * entry with {@code request.getWeight()} to preserve pre-Sprint-28
     * behaviour.
     */
    java.util.Map<String, Object> buildRateRequestBody(ShipmentRequestDTO request) {
        String fedexWeightUnit = "KG".equalsIgnoreCase(request.getWeightUnit()) ? "KG" : "LB";
        String fedexDimUnit = "CM".equalsIgnoreCase(request.getDimUnit()) ? "CM" : "IN";
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

        // One entry per box; effectivePackages() returns a synthetic
        // 1-element list when packages[] wasn't supplied.
        java.util.List<java.util.Map<String, Object>> lineItems = new java.util.ArrayList<>();
        for (com.multiship.backend.dto.PackageDetailDTO pkg : request.effectivePackages()) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("groupPackageCount", 1);
            BigDecimal pieceWeight = pkg.getWeight() != null ? pkg.getWeight()
                    : (request.getWeight() != null ? request.getWeight() : BigDecimal.ONE);
            String pieceWeightUnit = "KG".equalsIgnoreCase(pkg.getWeightUnit()) ? "KG"
                    : (pkg.getWeightUnit() != null ? pkg.getWeightUnit().toUpperCase() : fedexWeightUnit);
            item.put("weight", java.util.Map.of("units", pieceWeightUnit, "value", pieceWeight));
            // Include dims when the box carries them — matters for DIM-weight
            // surcharges (oversize-but-light boxes are underpriced without dims).
            if (pkg.getLength() != null && pkg.getWidth() != null && pkg.getHeight() != null) {
                String pieceDimUnit = pkg.getDimUnit() != null
                        ? ("CM".equalsIgnoreCase(pkg.getDimUnit()) ? "CM" : "IN")
                        : fedexDimUnit;
                item.put("dimensions", java.util.Map.of(
                        "length", pkg.getLength(),
                        "width", pkg.getWidth(),
                        "height", pkg.getHeight(),
                        "units", pieceDimUnit));
            }
            lineItems.add(item);
        }
        requestedShipment.put("requestedPackageLineItems", lineItems);
        body.put("requestedShipment", requestedShipment);
        return body;
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
            try { return new BigDecimal(nested.asText()); }
            catch (NumberFormatException ex) {
                log.debug("FedEx readFedExAmount: non-numeric shipmentRateDetail.totalNetCharge.amount '{}'", nested.asText());
            }
        }
        return null;
    }

    /**
     * FDX-D — pre-fix, a missing currency in the rate response silently
     * defaulted to "USD". FedEx's real responses always include currency,
     * but a parsing edge case that mislabels a GBP or EUR rate as USD
     * silently over/under-charges by the FX difference. Now returns null
     * and lets downstream {@link RateOption#currency} carry it (nullable
     * per the record definition). The rate-shop UI treats null currency
     * as "no quote" — surfaces the missing field instead of hiding it.
     */
    private static String readFedExCurrency(JsonNode entry) {
        String currency = entry.at("/shipmentRateDetail/totalNetCharge/currency").asText(null);
        if (currency == null || currency.isEmpty()) currency = entry.path("currency").asText(null);
        return currency == null || currency.isEmpty() ? null : currency;
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
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        String trackingLink = "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber;
        try {
            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build().post()
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

    /**
     * FedEx Cancel Shipment — {@code PUT /ship/v1/shipments/cancel} with
     * a Bearer token and body containing {@code accountNumber},
     * {@code trackingNumber}, {@code senderCountryCode}, and
     * {@code deletionControl}. FedEx refunds postage when the label
     * hasn't been scanned yet; post-scan cancels still succeed but no
     * refund is issued.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to
     * {@code NOT_SUPPORTED}.
     *
     * <p>Sprint 49 Tier 2 — account number and sender country now come
     * from the caller ({@code VoidServiceImpl} passes the account +
     * platform shipper defaults) instead of the hardcoded {@code "ACCOUNT"}
     * / {@code "US"} placeholders. FedEx cancel will reject anything that
     * doesn't match the label, which the old placeholders always did.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                    String accountNumber, String senderCountryCode) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                    "FedEx void needs live credentials; the account is on a fallback token.",
                    null);
        }
        if (!StringUtils.hasText(accountNumber)) {
            return new VoidResult(trackingNumber, false, "ERROR",
                    "FedEx cancel needs the shipper account number that created the label; none was passed.",
                    null);
        }
        String senderCountry = StringUtils.hasText(senderCountryCode) ? senderCountryCode : "US";
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("accountNumber", java.util.Map.of("value", accountNumber));
            body.put("emailShipment", false);
            body.put("senderCountryCode", senderCountry);
            body.put("deletionControl", "DELETE_ALL_PACKAGES");
            body.put("trackingNumber", trackingNumber);

            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build()
                    .put()
                    .uri("/ship/v1/shipments/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseFedExVoidResponse(trackingNumber, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx cancel rejected for {} (HTTP {}): {}",
                    trackingNumber, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "FedEx cancel rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("FedEx cancel call failed for {}: {}", trackingNumber, ex.getMessage());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "FedEx cancel call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse the FedEx cancel response. FedEx returns {@code output.cancelledShipment}
     * boolean plus a message. When {@code cancelledShipment=true} the void
     * was accepted.
     */
    VoidResult parseFedExVoidResponse(String trackingNumber, String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode output = root.path("output");
            boolean cancelled = output.path("cancelledShipment").asBoolean(false);
            String reason = output.path("message").asText(
                    output.path("cancelledHistory").asText(""));
            String status = cancelled ? "VOIDED" : "ERROR";
            String message = cancelled
                    ? "FedEx confirmed cancel." + (reason.isEmpty() ? "" : " " + reason)
                    : "FedEx cancel rejected. " + reason;
            return new VoidResult(trackingNumber, cancelled, status, message, response);
        } catch (Exception ex) {
            return new VoidResult(trackingNumber, false, "ERROR",
                    "FedEx cancel response parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * FedEx Address Validation — {@code POST /address/v1/addresses/resolve}
     * with a Bearer token. Body carries {@code addressesToValidate[]} with
     * one {@code address} per entry. Response gives per-address
     * {@code attributes} (Residential/Commercial), {@code classification}
     * (BUSINESS/RESIDENTIAL/UNKNOWN), {@code state} (STANDARDIZED for
     * corrected, INVALID for not found), and a resolved {@code address}.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public AddressValidationResult validateAddress(AddressToValidate address, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new AddressValidationResult(false, "NOT_SUPPORTED", "UNKNOWN", null,
                    java.util.List.of(),
                    "FedEx AV needs live credentials; the account is on a fallback token.",
                    null);
        }
        try {
            java.util.List<String> streetLines = new java.util.ArrayList<>();
            if (StringUtils.hasText(address.addressLine1())) streetLines.add(address.addressLine1());
            if (StringUtils.hasText(address.addressLine2())) streetLines.add(address.addressLine2());
            if (StringUtils.hasText(address.addressLine3())) streetLines.add(address.addressLine3());

            Map<String, Object> addr = new LinkedHashMap<>();
            addr.put("streetLines", streetLines);
            if (StringUtils.hasText(address.city())) addr.put("city", address.city());
            if (StringUtils.hasText(address.state())) addr.put("stateOrProvinceCode", address.state());
            if (StringUtils.hasText(address.postalCode())) addr.put("postalCode", address.postalCode());
            if (StringUtils.hasText(address.countryCode())) addr.put("countryCode", address.countryCode());

            Map<String, Object> body = Map.of(
                    "addressesToValidate", java.util.List.of(
                            Map.of("address", addr)));

            // Read as raw bytes and decode UTF-8 explicitly: FedEx returns UTF-8
            // JSON but often omits the charset, so the default String converter
            // would fall back to ISO-8859-1 and mojibake accented place names
            // (e.g. "Región" → "RegiÃ³n").
            byte[] raw = HttpClients.newBuilder().baseUrl(getBaseUrl()).build().post()
                    .uri("/address/v1/addresses/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            String response = raw == null ? "{}" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            // Sprint 49 Tier 1 — full response contains PII (streetLines / city /
            // postalCode / email / phone); logged at DEBUG so prod doesn't leak
            // it by default.
            log.debug("FedEx AV response: {}", response);
            return parseFedExAvResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx AV rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "FedEx AV rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("FedEx AV call failed: {}", ex.getMessage());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "FedEx AV call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a FedEx AV response. Package-visible for tests.
     * Response shape (only the fields we use):
     * <pre>
     * output.resolvedAddresses[0].
     *   classification            (BUSINESS / RESIDENTIAL / UNKNOWN / MIXED)
     *   attributes.Residential    (boolean-as-string)
     *   state                     (STANDARDIZED / INVALID / NORMALIZED)
     *   parsedPostalCode          (present when corrected)
     *   streetLinesToken[]        (corrected street lines)
     *   city / stateOrProvinceCode / postalCode / countryCode
     * </pre>
     */
    AddressValidationResult parseFedExAvResponse(String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode resolved = root.at("/output/resolvedAddresses/0");
            if (resolved.isMissingNode()) {
                return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                        java.util.List.of(),
                        "FedEx AV returned no resolved address.", response);
            }

            String state = resolved.path("state").asText("");
            String classification = mapFedExClassification(resolved.path("classification").asText("UNKNOWN"));

            AddressToValidate suggested = readFedExResolvedAddress(resolved);

            switch (state.toUpperCase()) {
                case "STANDARDIZED":
                    return new AddressValidationResult(true, "EXACT", classification, null,
                            java.util.List.of(),
                            "FedEx confirmed this address is deliverable.", response);
                case "NORMALIZED":
                    return new AddressValidationResult(true, "CORRECTED", classification, suggested,
                            java.util.List.of("FedEx corrected the address; review before shipping."),
                            "FedEx suggested a corrected address.", response);
                case "INVALID":
                    return new AddressValidationResult(false, "NOT_FOUND", classification, null,
                            java.util.List.of("FedEx couldn't find this address."),
                            "FedEx address not found.", response);
                default:
                    // Some FedEx API versions omit the top-level `state` field.
                    // Fall back to attributes.Matched + attributes.DPV, which
                    // together indicate "delivery-point verified". When those
                    // are also missing, treat a resolved suggestion that
                    // differs from input as CORRECTED, or same-as-input as
                    // EXACT — anything else is genuinely unknown.
                    String matched = resolved.path("attributes").path("Matched").asText("");
                    String dpv = resolved.path("attributes").path("DPV").asText("");
                    if ("true".equalsIgnoreCase(matched) || "true".equalsIgnoreCase(dpv)) {
                        return new AddressValidationResult(true, "EXACT", classification, null,
                                java.util.List.of(),
                                "FedEx confirmed this address (attributes.Matched=true).", response);
                    }
                    if ("false".equalsIgnoreCase(matched)) {
                        return new AddressValidationResult(false, "NOT_FOUND", classification, null,
                                java.util.List.of("FedEx couldn't find this address (attributes.Matched=false)."),
                                "FedEx address not found.", response);
                    }
                    if (suggested != null) {
                        return new AddressValidationResult(true, "CORRECTED", classification, suggested,
                                java.util.List.of("FedEx returned a resolved address; state field was absent."),
                                "FedEx suggested a corrected address.", response);
                    }
                    log.warn("FedEx AV returned no interpretable state / attributes.Matched. Response: {}", response);
                    return new AddressValidationResult(false, "ERROR", classification, null,
                            java.util.List.of("FedEx could not confirm this address; verify it manually."),
                            "FedEx couldn't standardize this address (no standardization status).",
                            response);
            }
        } catch (Exception ex) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "FedEx AV response parse failed: " + ex.getMessage(), response);
        }
    }

    private static AddressToValidate readFedExResolvedAddress(com.fasterxml.jackson.databind.JsonNode resolved) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode tokens = resolved.path("streetLinesToken");
        if (tokens.isArray()) tokens.forEach(n -> lines.add(n.asText()));
        return new AddressToValidate(
                null, null,
                lines.size() > 0 ? lines.get(0) : null,
                lines.size() > 1 ? lines.get(1) : null,
                lines.size() > 2 ? lines.get(2) : null,
                resolved.path("city").asText(null),
                resolved.path("stateOrProvinceCode").asText(null),
                resolved.path("postalCode").asText(null),
                resolved.path("countryCode").asText(null));
    }

    private static String mapFedExClassification(String raw) {
        if (raw == null) return "UNKNOWN";
        return switch (raw.trim().toUpperCase()) {
            case "BUSINESS" -> "COMMERCIAL";
            case "RESIDENTIAL" -> "RESIDENTIAL";
            default -> "UNKNOWN";
        };
    }

    /**
     * Sprint 35 — FedEx {@code packageSpecialServices} block. Currently
     * emits signature only ({@code signatureOptionType} + optional
     * {@code signatureOptionDetail} sub-block); FedEx's insurance is
     * handled inline via the line item's {@code declaredValue}.
     *
     * <p>signatureOptionType values (FedEx enum):
     * NO_SIGNATURE_REQUIRED | INDIRECT | DIRECT | ADULT.
     */
    private Map<String, Object> buildFedExPackageSpecialServices(ShipmentRequestDTO request) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sig = normaliseSignatureOption(request.getSignatureOption());
        if (sig != null) {
            java.util.List<String> types = new java.util.ArrayList<>();
            types.add("SIGNATURE_OPTION");
            out.put("specialServiceTypes", types);
            out.put("signatureOptionType", sig);
        }
        return out;
    }

    /** Normalise the DTO's freeform signatureOption to the FedEx enum
     *  values. Blank / unknown / NONE → null (carrier default). */
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
     * FedEx Estimated Duties & Taxes (EDT) — {@code POST /rate/v1/rates/quotes}
     * with {@code edtRequestType=ALL} + a {@code customsClearanceDetail}
     * block. FedEx returns freight in {@code totalNetCharge} and duty +
     * tax as separate entries in {@code ancillaryFeesAndTaxes[]} typed
     * {@code ESTIMATED_DUTIES_TAXES} + {@code TAXES}.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public LandedCostResult estimateLandedCost(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new LandedCostResult("FEDEX", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "FedEx EDT needs live credentials; the account is on a fallback token.",
                    null);
        }
        // FDX-B1 — recipient country is required. Pre-fix, blank silently
        // failed the isInternational check (both null == "not international")
        // and returned NOT_SUPPORTED, suppressing what should have been an
        // intl duties estimate. Fail loudly instead — same F7-style guard.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "FedEx EDT (duties estimate) requires a recipient country code "
                            + "(order " + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order — blank recipients "
                            + "silently suppress duties estimates.");
        }
        if (!isInternational(request)) {
            return new LandedCostResult("FEDEX", "NOT_SUPPORTED",
                    null, null, null, null, null, null,
                    java.util.List.of(),
                    java.util.List.of("FedEx EDT is only supported for international shipments."),
                    "Not an international lane; FedEx EDT skipped.", null);
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
                    "countryCode", firstNonBlank(request.getRecipientCountryCode(), "US"),
                    "residential", Boolean.TRUE.equals(request.getRecipientResidential()))));
            requestedShipment.put("pickupType", "USE_SCHEDULED_PICKUP");
            requestedShipment.put("rateRequestType", java.util.List.of("ACCOUNT", "LIST"));
            requestedShipment.put("edtRequestType", "ALL");

            requestedShipment.put("requestedPackageLineItems", java.util.List.of(java.util.Map.of(
                    "weight", java.util.Map.of("units", fedexWeightUnit, "value",
                            request.getWeight() != null ? request.getWeight() : java.math.BigDecimal.ONE))));

            if (request.getIntl() != null && request.getIntl().isReadyForCarrier()) {
                requestedShipment.put("customsClearanceDetail", buildCustomsClearanceDetail(request));
            }

            body.put("requestedShipment", requestedShipment);

            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build().post()
                    .uri("/rate/v1/rates/quotes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseFedExLandedCostResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx EDT rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new LandedCostResult("FEDEX", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "FedEx EDT rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("FedEx EDT failed: {}", ex.getMessage());
            return new LandedCostResult("FEDEX", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "FedEx EDT call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a FedEx Rate response with EDT extensions. Freight comes from
     * the preferred rate (Sprint 18); duties + taxes come from
     * {@code ancillaryFeesAndTaxes[]} in the same {@code ratedShipmentDetails}
     * entry. Package-visible for tests.
     */
    LandedCostResult parseFedExLandedCostResponse(String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode details = root.at("/output/rateReplyDetails/0");
            if (details.isMissingNode()) {
                return new LandedCostResult("FEDEX", "ERROR",
                        null, null, null, null, null, null,
                        java.util.List.of(), java.util.List.of(),
                        "FedEx returned no rateReplyDetails.", response);
            }
            com.fasterxml.jackson.databind.JsonNode preferredRate =
                    pickPreferredFedExRate(details.at("/ratedShipmentDetails"));
            if (preferredRate == null || preferredRate.isMissingNode()) {
                return new LandedCostResult("FEDEX", "ERROR",
                        null, null, null, null, null, null,
                        java.util.List.of(), java.util.List.of(),
                        "FedEx returned no ratedShipmentDetails.", response);
            }

            java.math.BigDecimal freight = readFedExAmount(preferredRate);
            String currency = readFedExCurrency(preferredRate);

            java.math.BigDecimal duty = null;
            java.math.BigDecimal tax = null;
            com.fasterxml.jackson.databind.JsonNode ancillary =
                    preferredRate.at("/shipmentRateDetail/ancillaryFeesAndTaxes");
            if (!ancillary.isArray()) {
                ancillary = preferredRate.at("/ancillaryFeesAndTaxes");
            }
            if (ancillary.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode fee : ancillary) {
                    String type = fee.path("type").asText("").toUpperCase();
                    java.math.BigDecimal amount = readFedExAncillaryAmount(fee);
                    if (amount == null) continue;
                    if (type.contains("DUTY") || type.contains("DUTIES")) {
                        duty = duty == null ? amount : duty.add(amount);
                    } else if (type.contains("TAX")) {
                        tax = tax == null ? amount : tax.add(amount);
                    }
                }
            }

            java.math.BigDecimal grand = java.math.BigDecimal.ZERO;
            if (freight != null) grand = grand.add(freight);
            if (duty != null) grand = grand.add(duty);
            if (tax != null) grand = grand.add(tax);
            if (grand.signum() == 0) grand = null;

            return new LandedCostResult("FEDEX", "LIVE",
                    freight, duty, tax, null, grand, currency,
                    java.util.List.of(), java.util.List.of(),
                    "FedEx returned an EDT estimate.", response);
        } catch (Exception ex) {
            return new LandedCostResult("FEDEX", "ERROR",
                    null, null, null, null, null, null,
                    java.util.List.of(), java.util.List.of(),
                    "FedEx EDT parse failed: " + ex.getMessage(), response);
        }
    }

    private static boolean isInternational(ShipmentRequestDTO r) {
        String from = r.getShipperCountryCode();
        String to = r.getRecipientCountryCode();
        return StringUtils.hasText(from) && StringUtils.hasText(to)
                && !from.trim().equalsIgnoreCase(to.trim());
    }

    /**
     * Read one entry from FedEx's {@code ancillaryFeesAndTaxes[]}. Each
     * entry has a flat {@code amount} field (unlike the freight
     * {@code totalNetCharge} shape that {@link #readFedExAmount} handles).
     */
    private static java.math.BigDecimal readFedExAncillaryAmount(
            com.fasterxml.jackson.databind.JsonNode fee) {
        com.fasterxml.jackson.databind.JsonNode amount = fee.path("amount");
        if (amount.isNumber()) return amount.decimalValue();
        if (amount.isTextual()) {
            try { return new java.math.BigDecimal(amount.asText()); }
            catch (NumberFormatException ex) {
                log.debug("FedEx readFedExAncillaryAmount: non-numeric fee.amount '{}'", amount.asText());
            }
        }
        return null;
    }

    /**
     * FedEx Create Pickup — {@code POST /pickup/v1/pickups} with a Bearer
     * token. Body carries {@code originDetail} (address + ready time +
     * customer close time), total package count + weight, carrier code
     * (FDXE = Express, FDXG = Ground; we send FDXG as the operational
     * default), and a pickup confirmation. Response includes
     * {@code pickupConfirmationCode} and location.
     *
     * <p>{@code -local-*} fallback tokens short-circuit to NOT_SUPPORTED.
     */
    @Override
    public PickupResult schedulePickup(PickupRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("FEDEX", null, null, null, null, "NOT_SUPPORTED",
                    "FedEx pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        // FDX-C — pre-fix, buildFedExPickupRequest hardcoded the literal
        // string "ACCOUNT" as the shipper's associatedAccountNumber, which
        // FedEx rejects with a validation error every time. Same bug shape
        // as Sprint 49 T2 fixed for voidShipment + Sprint 50 T1 #10 fixed
        // for closeOutDay. Now sourced from the caller (PickupServiceImpl
        // threads account.getAccountNumber() onto PickupRequest); short-
        // circuit to ERROR when blank rather than send a placeholder that
        // FedEx will silently reject.
        if (!StringUtils.hasText(request.accountNumber())) {
            return new PickupResult("FEDEX", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "FedEx pickup needs the shipper account number that owns the labels; none was passed.",
                    null);
        }
        try {
            Map<String, Object> body = buildFedExPickupRequest(request);
            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build()
                    .post()
                    .uri("/pickup/v1/pickups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseFedExPickupResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx pickup rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new PickupResult("FEDEX", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "FedEx pickup rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("FedEx pickup call failed: {}", ex.getMessage());
            return new PickupResult("FEDEX", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "FedEx pickup call failed: " + ex.getMessage(), null);
        }
    }

    /** Build the FedEx CreatePickupRequest body. */
    Map<String, Object> buildFedExPickupRequest(PickupRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        // FDX-C — real shipper account (pre-fix, hardcoded literal "ACCOUNT").
        // The schedulePickup entry point already guards against blank here;
        // firstNonBlank retained as a defence-in-depth for direct callers
        // of buildFedExPickupRequest in tests.
        body.put("associatedAccountNumber", Map.of("value",
                firstNonBlank(req.accountNumber(), "")));

        Map<String, Object> originDetail = new LinkedHashMap<>();
        AddressToValidate a = req.address();
        Map<String, Object> pickupLocation = new LinkedHashMap<>();
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("companyName", firstNonBlank(req.contactName(), ""));
        contact.put("personName", firstNonBlank(req.contactName(), ""));
        contact.put("phoneNumber", firstNonBlank(req.contactPhone(), ""));
        pickupLocation.put("contact", contact);
        if (a != null) {
            Map<String, Object> address = new LinkedHashMap<>();
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (StringUtils.hasText(a.addressLine1())) lines.add(a.addressLine1());
            if (StringUtils.hasText(a.addressLine2())) lines.add(a.addressLine2());
            address.put("streetLines", lines);
            if (StringUtils.hasText(a.city())) address.put("city", a.city());
            if (StringUtils.hasText(a.state())) address.put("stateOrProvinceCode", a.state());
            if (StringUtils.hasText(a.postalCode())) address.put("postalCode", a.postalCode());
            if (StringUtils.hasText(a.countryCode())) address.put("countryCode", a.countryCode());
            address.put("residential", false);
            pickupLocation.put("address", address);
        }
        originDetail.put("pickupLocation", pickupLocation);
        originDetail.put("readyDateTimestamp", formatFedExPickupTimestamp(req));
        originDetail.put("customerCloseTime", req.pickupWindowEnd() == null
                ? "17:00:00" : req.pickupWindowEnd().toString() + ":00");
        originDetail.put("pickupDateType", "SAME_DAY");
        body.put("originDetail", originDetail);

        // FDXG = FedEx Ground; FDXE = FedEx Express. Ground is the safe
        // operational default — most manual-label flows are Ground.
        body.put("carrierCode", "FDXG");

        body.put("totalPackageCount", req.packageCount());
        String fedexWeightUnit = "KG".equalsIgnoreCase(req.weightUnit()) ? "KG" : "LB";
        body.put("totalWeight", Map.of(
                "units", fedexWeightUnit,
                "value", req.totalWeight() != null ? req.totalWeight() : java.math.BigDecimal.ONE));

        if (StringUtils.hasText(req.specialInstructions())) {
            body.put("remarks", req.specialInstructions());
        }
        return body;
    }

    /**
     * Produce a FedEx-compatible ISO-8601 timestamp for readyDateTimestamp.
     *
     * <p>FDX-E — the {@code pickupDate}-missing fallback used to call
     * {@link java.time.LocalDate#now()} which resolves to the JVM's default
     * zone (server-dependent — usually UTC on K8s, could be anything on a
     * dev box). {@link com.multiship.backend.util.LabelDates#today(String)}
     * with a null zone returns UTC deterministically, matching the pre-F6-E
     * behavior on label ship dates and the pattern F6-E established for
     * every other date field. The DTO layer ({@code PickupRequestDTO})
     * marks {@code pickupDate} as {@code @NotNull} so this fallback only
     * fires on direct-constructor callers; the fix still tightens the
     * safety net for those.
     */
    private static String formatFedExPickupTimestamp(PickupRequest req) {
        java.time.LocalDate date = req.pickupDate() != null
                ? req.pickupDate()
                : com.multiship.backend.util.LabelDates.today(null);
        java.time.LocalTime time = req.pickupWindowStart() != null
                ? req.pickupWindowStart() : java.time.LocalTime.of(9, 0);
        return date.toString() + "T" + time.toString();
    }

    /**
     * Parse a FedEx pickup response. Success = {@code output.pickupConfirmationCode}
     * is non-blank. Package-visible for tests.
     */
    PickupResult parseFedExPickupResponse(PickupRequest req, String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            String code = root.at("/output/pickupConfirmationCode").asText(null);
            String status = StringUtils.hasText(code) ? "SCHEDULED" : "ERROR";
            String message = StringUtils.hasText(code)
                    ? "FedEx confirmed pickup — " + code
                    : "FedEx pickup response missing pickupConfirmationCode.";
            return new PickupResult("FEDEX", code,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    status, message, response);
        } catch (Exception ex) {
            return new PickupResult("FEDEX", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "FedEx pickup parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * FedEx End of Day / CloseShipment —
     * {@code POST /ship/v1/shipments/endofday} with a Bearer token.
     * Body carries the carrier code (FDXE Express, FDXG Ground) and the
     * account. Response includes an alerts array, a groupID that groups
     * the closed shipments, and a base64 close-out PDF.
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED.
     *
     * <p>Sprint 50 Tier 1 (finding #10) — identical bug shape to the
     * {@link #voidShipment} placeholder Sprint 49 Tier 2 fixed: the
     * request body used to send the literal {@code "ACCOUNT"} as the
     * shipper account number. FedEx rejects that with a validation error,
     * so every real close-out silently failed. Now sourced from the caller's
     * {@link CloseOutRequest#accountNumber()}; a blank value short-circuits
     * to ERROR with a machine-readable reason, mirroring voidShipment.
     */
    @Override
    public CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new CloseOutResult("FEDEX", null, null, null, 0, "NOT_SUPPORTED",
                    "FedEx close-out needs live credentials; the account is on a fallback token.",
                    null);
        }
        java.util.List<String> tracking = request.trackingNumbers();
        if (tracking == null || tracking.isEmpty()) {
            return new CloseOutResult("FEDEX", null, null, null, 0, "ERROR",
                    "FedEx close-out requires at least one tracking number.", null);
        }
        // Sprint 50 Tier 1 (finding #10) — FedEx rejects a placeholder account
        // number on end-of-day. The caller (ManifestServiceImpl) resolves the
        // shipper account and passes it through CloseOutRequest.accountNumber().
        String accountNumber = request.accountNumber();
        if (!StringUtils.hasText(accountNumber)) {
            return new CloseOutResult("FEDEX", null, null, null, tracking.size(), "ERROR",
                    "FedEx close-out needs the shipper account number that generated the labels; none was passed.",
                    null);
        }
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("accountNumber", java.util.Map.of("value", accountNumber));
            body.put("carrierCode", "FDXG");

            String response = HttpClients.newBuilder().baseUrl(getBaseUrl(environment)).build()
                    .post()
                    .uri("/ship/v1/shipments/endofday")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-locale", "en_US")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseFedExCloseOutResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("FedEx end-of-day rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new CloseOutResult("FEDEX", null, null, null, tracking.size(), "ERROR",
                    "FedEx end-of-day rejected: HTTP " + ex.getStatusCode().value(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("FedEx end-of-day call failed: {}", ex.getMessage());
            return new CloseOutResult("FEDEX", null, null, null, tracking.size(), "ERROR",
                    "FedEx end-of-day call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse a FedEx end-of-day response. Success = presence of
     * {@code output.transactionShipments[0].alerts} without an error-typed
     * alert AND either a {@code groupID} or a manifest PDF. Response
     * body sometimes puts a base64 close-out at
     * {@code output.transactionShipments[0].pieceResponses[0].deliveryDocumentTokens[0].image}.
     */
    CloseOutResult parseFedExCloseOutResponse(CloseOutRequest request, String response) {
        int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(response).orElse("{}"));
            com.fasterxml.jackson.databind.JsonNode ts = root.at("/output/transactionShipments/0");
            String groupID = ts.path("groupID").asText(null);
            if (!StringUtils.hasText(groupID)) {
                groupID = root.at("/output/manifestData/groupID").asText(null);
            }
            String pdfBase64 = ts.at("/pieceResponses/0/deliveryDocumentTokens/0/image").asText(null);
            if (!StringUtils.hasText(pdfBase64)) {
                pdfBase64 = root.at("/output/manifestData/manifestImage").asText(null);
            }
            String status = StringUtils.hasText(groupID) ? "MANIFESTED" : "ERROR";
            String message = StringUtils.hasText(groupID)
                    ? "FedEx closed out " + count + " shipment(s) · group " + groupID
                    : "FedEx end-of-day response missing groupID.";
            return new CloseOutResult("FEDEX", groupID, null, pdfBase64, count,
                    status, message, response);
        } catch (Exception ex) {
            return new CloseOutResult("FEDEX", null, null, null, count, "ERROR",
                    "FedEx end-of-day parse failed: " + ex.getMessage(), response);
        }
    }

    /**
     * FedEx Push Tracking Notification — HMAC-SHA256(body, secret) in
     * the {@code X-FedEx-Signature} header, hex-encoded.
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload,
                                           java.util.Map<String, String> headers,
                                           String secret) {
        String provided = pickWebhookHeader(headers, "X-FedEx-Signature");
        String expected = WebhookHmacUtil.hmacSha256Hex(rawPayload, secret);
        return provided != null && expected != null
                && WebhookHmacUtil.constantTimeEquals(provided, expected);
    }

    /**
     * Parse a FedEx push-tracking webhook. FedEx pushes:
     * <pre>
     * {
     *   "trackingNumber": "794...",
     *   "shipmentStatusEventLocalTimeStamp": "2026-07-26T14:30:00-04:00",
     *   "eventType": "DL",
     *   "eventDescription": "Delivered",
     *   "eventLocation": {
     *     "city": "Louisville", "stateOrProvinceCode": "KY", "countryCode": "US"
     *   }
     * }
     * </pre>
     */
    @Override
    public TrackingWebhookEvent parseWebhookEvent(String rawPayload,
                                                   java.util.Map<String, String> headers) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    Optional.ofNullable(rawPayload).orElse("{}"));
            String tracking = root.path("trackingNumber").asText(null);
            if (!StringUtils.hasText(tracking)) return null;
            String eventType = root.path("eventType").asText(null);
            String description = root.path("eventDescription").asText("");
            LocalDateTime occurred = parseDateTime(root.path("shipmentStatusEventLocalTimeStamp").asText(null));
            com.fasterxml.jackson.databind.JsonNode loc = root.path("eventLocation");
            String location = buildFedExLocation(loc);
            boolean delivered = "DL".equalsIgnoreCase(eventType)
                    || "DELIVERED".equalsIgnoreCase(description);
            return new TrackingWebhookEvent(tracking, eventType, eventType,
                    occurred, location, delivered, description);
        } catch (Exception ex) {
            log.warn("FedEx webhook parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String buildFedExLocation(com.fasterxml.jackson.databind.JsonNode loc) {
        if (loc == null || loc.isMissingNode()) return null;
        String city = loc.path("city").asText("");
        String state = loc.path("stateOrProvinceCode").asText("");
        String country = loc.path("countryCode").asText("");
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) sb.append(city);
        if (!state.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(state);
        if (!country.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(country);
        return sb.length() == 0 ? null : sb.toString();
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
    /** Fallback FX thresholds for the common invoice currencies. Used only
     *  when {@link com.multiship.backend.service.fx.FxRateService#convert}
     *  returns empty (feed down, unsupported currency). Rates are close
     *  enough to spot to keep the IOSS threshold within one order of
     *  magnitude — a false negative just leaves VAT for delivery-time
     *  invoicing; a false positive attaches IOSS where it doesn't apply.
     *
     *  <p>FDX-D — the pre-fix comment claimed "Real FX conversion lands in
     *  a future sprint (gap 15)" but F6-D shipped ECB-backed FX (see
     *  {@link com.multiship.backend.service.fx.EcbFxRateService}). This
     *  table is now the second-tier fallback, not the primary path. */
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
        // F6-E — shipDatestamp is FedEx's authoritative "when did this ship"
        // date; SLA counters (Ground business-day math, Express AM/PM
        // deadlines) all key off it. Use the shipper's local day, not UTC.
        requestedShipment.put("shipDatestamp",
                com.multiship.backend.util.LabelDates.today(request.getShipperTimezone()).toString());
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

        // Sprint 28 — multi-package. FedEx wants requestedPackageLineItems[]
        // with one entry per box AND totalPackageCount at the parent.
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> packages = request.effectivePackages();
        java.util.List<Map<String, Object>> lineItems = new java.util.ArrayList<>();
        String declaredCurrency = StringUtils.hasText(request.getDeclaredValueCurrency())
                ? request.getDeclaredValueCurrency().trim().toUpperCase() : "USD";
        // Sprint 48 B11 — derive per-package declared value from CI
        // commodities (grouped by boxSeq). Domestic FedEx uses per-pkg
        // declaredValue on each line item; international FedEx MPS
        // switches to shipment-level totalDeclaredValue on the master
        // (added below after the loop).
        boolean fedexIntl = request.getIntl() != null && request.getIntl().isReadyForCarrier();
        com.multiship.backend.util.DeclaredValueContextBuilder.DeclaredValueContext dvCtx =
                com.multiship.backend.util.DeclaredValueContextBuilder.build(
                        request.getIntl() != null ? request.getIntl().getCommodities() : null,
                        packages.size(),
                        packages,
                        declaredCurrency,
                        request.getDeclaredValue());
        int seq = 1;
        for (com.multiship.backend.dto.PackageDetailDTO p : packages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequenceNumber", String.valueOf(
                    p.getSequenceNumber() == null ? seq : p.getSequenceNumber()));
            String fedexWeightUnit = "KG".equalsIgnoreCase(
                    firstNonBlank(p.getWeightUnit(), request.getWeightUnit())) ? "KG" : "LB";
            item.put("weight", Map.of(
                    "units", fedexWeightUnit,
                    "value", p.getWeight() != null ? p.getWeight() : java.math.BigDecimal.ZERO));
            // Resolution chain per package:
            //   1. per-box CI-derived declared value (from dvCtx)
            //   2. explicit p.declaredValue (legacy override)
            //   3. insuredValue (shipment-level insurance, wins for legacy
            //      insurance-only orders)
            // Skipped for intl MPS — value is set at shipment level via
            // totalDeclaredValue below.
            java.math.BigDecimal declared = null;
            int pkgIdx = (p.getSequenceNumber() != null ? p.getSequenceNumber() : seq) - 1;
            if (!fedexIntl && pkgIdx >= 0 && pkgIdx < dvCtx.perPackage().size()) {
                java.math.BigDecimal fromItems = dvCtx.perPackage().get(pkgIdx);
                if (fromItems != null && fromItems.signum() > 0) declared = fromItems;
            }
            if (declared == null && p.getDeclaredValue() != null) declared = p.getDeclaredValue();
            if (declared == null && !fedexIntl && request.getInsuredValue() != null
                    && request.getInsuredValue().signum() > 0) {
                declared = request.getInsuredValue();
            }
            if (declared != null) {
                String currencyForLine = request.getInsuredValue() != null
                        ? firstNonBlank(request.getInsuredValueCurrency(), declaredCurrency)
                        : declaredCurrency;
                item.put("declaredValue", Map.of(
                        "amount", declared,
                        "currency", currencyForLine.trim().toUpperCase()));
            }
            if (StringUtils.hasText(p.getReference())) {
                item.put("customerReferences", java.util.List.of(Map.of(
                        "customerReferenceType", "CUSTOMER_REFERENCE",
                        "value", p.getReference())));
            }
            // Sprint 35 — signature + insurance are per-package on FedEx.
            Map<String, Object> packageSpecialServices = buildFedExPackageSpecialServices(request);
            if (!packageSpecialServices.isEmpty()) {
                item.put("packageSpecialServices", packageSpecialServices);
            }
            lineItems.add(item);
            seq++;
        }
        requestedShipment.put("totalPackageCount", String.valueOf(packages.size()));
        requestedShipment.put("requestedPackageLineItems", lineItems);

        // Sprint 48 B11 — for intl MPS FedEx expects one shipment-level
        // declared value on totalDeclaredValue (per FedEx MPS docs); the
        // per-package declaredValue field is skipped for intl in the loop
        // above. Carriage-liability total = sum of per-box CI-derived
        // amounts (customs total is set on commodities separately below).
        if (fedexIntl && dvCtx.shipmentTotal() != null && dvCtx.shipmentTotal().signum() > 0) {
            requestedShipment.put("totalDeclaredValue", Map.of(
                    "amount", dvCtx.shipmentTotal(),
                    "currency", declaredCurrency));
        }

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
        // F6-C — clearanceOption (per-account, resolved by
        // ShipmentDefaultsResolver from CarrierAccountRef) wins over
        // dutyBillTo (per-customs-profile). Both fall back to
        // incoterms-derived default (DDP → SENDER, else RECIPIENT).
        String paymentType = firstNonBlank(
                intl.getClearanceOption(),
                intl.getDutyBillTo(),
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

    /**
     * Our reason-for-export enum → FedEx commercialInvoice.purpose.
     *
     * <p>FDX-D — pre-fix, only 6 of the 8 resolver-validated enum values
     * were explicitly mapped; MERCHANDISE / PERSONAL_USE / REPAIR_AND_RETURN
     * silently fell to the default "SOLD", which mislabels the customs
     * invoice (PERSONAL_USE incorrectly declared as commercial sale would
     * VAT the parcel at the border; REPAIR_AND_RETURN incorrectly declared
     * as sale would double-tax on the return leg).
     *
     * <p>Now all 8 {@link com.multiship.backend.service.ShipmentDefaultsResolver
     * #SHIPPING_PURPOSE_ENUM} values are explicit. Unknown values still
     * fall to SOLD but log a warning so future audits catch drift.
     */
    private static String mapFedExPurpose(String reason) {
        if (reason == null) return "SOLD";
        String v = reason.trim().toUpperCase();
        return switch (v) {
            case "SALE", "MERCHANDISE" -> "SOLD";
            case "GIFT" -> "GIFT";
            case "SAMPLE" -> "SAMPLE";
            case "RETURN", "REPAIR", "REPAIR_AND_RETURN" -> "REPAIR_AND_RETURN";
            case "PERSONAL_USE" -> "PERSONAL_EFFECTS";
            case "DOCUMENTS" -> "NOT_SOLD";
            default -> {
                log.warn("FedEx mapFedExPurpose: unrecognised reason '{}' — defaulting to SOLD. "
                        + "Add an explicit mapping in mapFedExPurpose if this is a real value.", v);
                yield "SOLD";
            }
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
        JsonNode shipment = root.at("/output/transactionShipments/0");
        JsonNode pieceResponses = shipment.path("pieceResponses");

        // Master tracking: FedEx's masterTrackingNumber is the shipment ID
        // that ties multi-piece pieces together. When absent (single-pkg)
        // it equals pieces[0].trackingNumber — same fallback chain the old
        // parser used.
        String trackingNumber = firstText(
                shipment.path("masterTrackingNumber"),
                pieceResponses.path(0).path("trackingNumber"),
                pieceResponses.path(0).path("packageDocuments").path(0).path("trackingNumber")
        );

        String labelUrl = firstText(
                pieceResponses.path(0).path("packageDocuments").path(0).path("url"),
                pieceResponses.path(0).path("packageDocuments").path(0).path("encodedLabel")
        );

        String labelPdf = firstText(
                pieceResponses.path(0).path("packageDocuments").path(0).path("encodedLabel"),
                pieceResponses.path(0).path("packageDocuments").path(0).path("url")
        );

        JsonNode totalNetChargeNode = shipment.path("shipmentRatingDetails").path(0).path("totalNetCharge");
        BigDecimal shippingCost = totalNetChargeNode.isNumber() ? totalNetChargeNode.decimalValue() : null;

        LocalDateTime estimatedDelivery = parseDateTime(
                firstText(
                        shipment.path("actualDeliveryDate"),
                        shipment.path("shipDatestamp")
                )
        );

        String trackingUrl = StringUtils.hasText(trackingNumber)
                ? "https://www.fedex.com/fedextrack/?trknbr=" + trackingNumber
                : null;

        // Per-piece rows — one PackageTracking per element in pieceResponses[].
        // FedEx returns N pieces for an N-piece shipment when the createShipment
        // MPS payload was accepted. When the request was single-package this
        // list has exactly one element that mirrors the master.
        java.util.List<PackageTracking> packages = new java.util.ArrayList<>();
        if (pieceResponses.isArray()) {
            for (int i = 0; i < pieceResponses.size(); i++) {
                JsonNode piece = pieceResponses.get(i);
                String pcTrack = firstText(
                        piece.path("trackingNumber"),
                        piece.path("packageDocuments").path(0).path("trackingNumber"));
                if (!StringUtils.hasText(pcTrack)) continue;
                String pcLabelUrl = firstText(
                        piece.path("packageDocuments").path(0).path("url"),
                        piece.path("packageDocuments").path(0).path("encodedLabel"));
                String pcLabelPdf = firstText(
                        piece.path("packageDocuments").path(0).path("encodedLabel"),
                        piece.path("packageDocuments").path(0).path("url"));
                JsonNode pcCharge = piece.path("netChargeAmount");
                BigDecimal pcNet = pcCharge.isNumber() ? pcCharge.decimalValue() : null;
                packages.add(new PackageTracking(i + 1, pcTrack,
                        StringUtils.hasText(pcTrack) ? "https://www.fedex.com/fedextrack/?trknbr=" + pcTrack : null,
                        pcLabelUrl, pcLabelPdf, pcNet));
            }
        }

        return new ShipmentResult(
                trackingNumber,
                trackingUrl,
                labelUrl,
                labelPdf,
                shippingCost,
                estimatedDelivery,
                response,
                packages
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
        } catch (Exception ex) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ex2) {
                log.debug("FedEx parseDateTime: unparseable timestamp '{}' (neither OffsetDateTime nor LocalDateTime matched)", value);
                return null;
            }
        }
    }

    private String getBaseUrl() {
        return getBaseUrl(carrierProperties.getDefaultEnvironment());
    }

    /**
     * Base URL for the given caller environment. SANDBOX routes to the
     * per-carrier sandbox host so sandbox credentials don't hit production;
     * anything else routes to the production host.
     */
    private String getBaseUrl(String environment) {
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

    private String getShipmentUrl(String environment) {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        return getBaseUrl(environment) + fedEx.getShipmentPath();
    }

    private String getTrackingUrl() {
        CarrierProperties.FedEx fedEx = carrierProperties.getFedEx();
        return getBaseUrl() + fedEx.getTrackingPath();
    }
}
