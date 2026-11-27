package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.service.carriers.exceptions.CarrierException;
import com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper;
import com.multiship.backend.service.carriers.exceptions.CarrierValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DPD UK via the MyDPD shipping API ({@code https://api.dpd.co.uk}).
 *
 * <p>Assumption: the client ships with <b>DPD UK</b>. DPD Local runs the same
 * API on {@code https://api.dpdlocal.co.uk} (change {@code carrier.dpd.api-base-url});
 * DPD Germany / DPD EU is a different SOAP API and would be its own connector.
 *
 * <p>Credential mapping onto {@code carrier_account_ref}: {@code clientId} is the
 * MyDPD API username, {@code clientSecret} the password, {@code accountNumber}
 * the DPD account number. Every call carries {@code GEOClient: account/<number>}
 * plus the {@code GEOSession} that {@code POST /user/?action=login} (Basic auth)
 * returned. The platform passes a single token around, so ours is
 * {@code <account>|<geoSession>} — see {@link #splitToken}.
 *
 * <p>Known limits, surfaced as explicit messages:
 * <ul>
 *   <li>No real sandbox — SANDBOX accounts are routed to {@code carrier.dpd.sandbox-base-url}
 *       (the local mock in {@code scripts/dpd-mock}); when that is blank they hit the live host.</li>
 *   <li>No tracking API — {@link #trackShipment} returns the public tracker URL.</li>
 *   <li>The label is a second call ({@code GET /shipping/shipment/{id}/label/}),
 *       whose {@code Accept} header picks the print format
 *       ({@code carrier.dpd.label-accept}).</li>
 *   <li>Void and reprint need DPD's {@code shipmentId}; the platform hands us the
 *       consignment number, so {@link #findShipmentId} searches for it first.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DpdConnector implements CarrierConnector {

    static final String CARRIER_CODE = "DPD";
    private static final String CARRIER_NAME = "DPD";

    /** Public tracker — accepts consignment or parcel numbers. */
    private static final String TRACKING_URL = "https://track.dpd.co.uk/parcels/";

    private static final DateTimeFormatter DPD_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final BigDecimal KG_PER_LB = new BigDecimal("0.45359237");
    private static final BigDecimal KG_PER_OZ = new BigDecimal("0.028349523125");

    /** Shown before the account has synced its live network list; never persisted (live=false). */
    static final List<ServiceOffering> FALLBACK_SERVICES = List.of(
            new ServiceOffering("1^12", "DPD Next Day", "DOMESTIC"),
            new ServiceOffering("1^13", "DPD By 12:00", "DOMESTIC"),
            new ServiceOffering("1^16", "DPD By 10:30", "DOMESTIC"),
            new ServiceOffering("1^32", "DPD Two Day", "DOMESTIC"),
            new ServiceOffering("1^09", "DPD Saturday", "DOMESTIC"),
            new ServiceOffering("1^41", "DPD Sunday", "DOMESTIC"),
            new ServiceOffering("1^91", "DPD Classic (international)", "INTERNATIONAL"));

    // ponytail: the live catalogue is asked for two fixed lanes (GB→GB, GB→DE); pass the
    // account's own depot postcode if DPD ever returns depot-specific networks.
    private static final String[][] CATALOGUE_LANES = {
            {"GB", "B1 1AA", "GB", "SW1A 1AA", "DOMESTIC"},
            {"GB", "B1 1AA", "DE", "10115", "INTERNATIONAL"}};

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    /** Login sessions keyed by account+credentials; DPD sessions idle out, so keep this short. */
    private final Cache<String, String> sessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(2)).maximumSize(1000).build();

    private final ThreadLocal<String> authFailureDetail = new ThreadLocal<>();

    // ───────────────────────── identity / config ─────────────────────────

    @Override public String getCarrierCode() { return CARRIER_CODE; }
    @Override public String getCarrierName() { return CARRIER_NAME; }

    @Override
    public CarrierConfiguration getConfiguration() {
        CarrierProperties.Dpd dpd = carrierProperties.getDpd();
        return new CarrierConfiguration(CARRIER_CODE, CARRIER_NAME,
                dpd.getApiBaseUrl(), dpd.getApiBaseUrl(), "v1",
                StringUtils.hasText(dpd.getSandboxBaseUrl()) ? dpd.getSandboxBaseUrl() : dpd.getApiBaseUrl(),
                "/shipping/shipment", null, "/user/?action=login",
                dpd.getLogoUrl(), dpd.getDocumentationUrl(), dpd.getConnectionGuide(),
                dpd.getDefaultNetworkCode(), "parcel", "BASE64",
                carrierProperties.getDefaultEnvironment(), true);
    }

    // ───────────────────────── auth ─────────────────────────

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new CarrierConnectionException("DPD MyDPD API username and password are required.");
        }
        return true;
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret, null, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        return getAccessToken(clientId, clientSecret, accountNumber, null);
    }

    /** {@code POST /user/?action=login} with Basic auth → {@code data.geoSession}. */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            authFailureDetail.set("MyDPD username and password are both required.");
            return CARRIER_CODE + "-local-missing-credentials";
        }
        if (!StringUtils.hasText(accountNumber)) {
            authFailureDetail.set("DPD account number is required — it is sent as the GEOClient header on every call.");
            return CARRIER_CODE + "-local-missing-account";
        }
        String account = accountNumber.trim();
        String cacheKey = baseUrl(environment) + "|" + account + "|" + clientId.trim() + "|" + clientSecret;
        String cached = sessions.getIfPresent(cacheKey);
        if (cached != null) return cached;
        try {
            String basic = Base64.getEncoder().encodeToString(
                    (clientId.trim() + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            String body = client(environment).post().uri("/user/?action=login")
                    .header("Authorization", "Basic " + basic)
                    .header("GEOClient", "account/" + account)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            String session = dataOrThrow(body, "login").path("geoSession").asText(null);
            if (!StringUtils.hasText(session)) {
                throw new CarrierException(CARRIER_CODE, "DPD login answered without a geoSession.");
            }
            String token = sessionToken(account, session);
            sessions.put(cacheKey, token);
            return token;
        } catch (CarrierException cex) {
            log.warn("DPD login rejected: {}", cex.getMessage());
            authFailureDetail.set(cex.getMessage());
            return CARRIER_CODE + "-local-rejected";
        } catch (RestClientResponseException ex) {
            String detail = ex.getStatusCode().value() == 401
                    ? "DPD rejected the MyDPD username / password (401). Check them in MyDPD → API access."
                    : "DPD answered " + ex.getStatusCode().value() + " to the login: " + brief(ex.getResponseBodyAsString());
            log.warn("DPD credential check failed: {}", detail);
            authFailureDetail.set(detail);
            return CARRIER_CODE + "-local-" + ex.getStatusCode().value();
        } catch (Exception ex) {
            log.warn("DPD login unreachable: {}", ex.getMessage());
            authFailureDetail.set("Could not reach DPD: " + ex.getMessage());
            return CARRIER_CODE + "-local-unreachable";
        }
    }

    @Override
    public String consumeAuthFailureDetail() {
        String d = authFailureDetail.get();
        authFailureDetail.remove();
        return d;
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        validateCredentials(clientId, clientSecret);
        String token = getAccessToken(clientId, clientSecret, accountNumber, carrierProperties.getDefaultEnvironment());
        boolean live = isRealToken(token);
        String message = live ? "DPD connection established."
                : "DPD rejected the credentials: " + consumeAuthFailureDetail();
        return new CarrierConnectionResult(CARRIER_CODE, CARRIER_NAME, live, accountNumber,
                carrierProperties.getDefaultEnvironment(), live ? token : null,
                live ? LocalDateTime.now(ZoneOffset.UTC).plusHours(2) : null, message);
    }

    static String sessionToken(String account, String geoSession) {
        return account + "|" + geoSession;
    }

    /** {@code [account, geoSession]}; a token without the separator is treated as a bare session. */
    static String[] splitToken(String token) {
        int i = token.indexOf('|');
        return i < 0 ? new String[]{"", token} : new String[]{token.substring(0, i), token.substring(i + 1)};
    }

    private static RestClient.RequestHeadersSpec<?> auth(RestClient.RequestHeadersSpec<?> spec, String token) {
        String[] t = splitToken(token);
        return spec.header("GEOClient", "account/" + t[0]).header("GEOSession", t[1]);
    }

    // ───────────────────────── catalogue ─────────────────────────

    /** Live: {@code GET /shipping/network} for a domestic and an international lane. */
    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
        if (!isRealToken(accessToken)) {
            return new ServiceAvailability(FALLBACK_SERVICES, false,
                    "built-in DPD list — connect a verified DPD account to sync live networks");
        }
        try {
            Map<String, ServiceOffering> byCode = new LinkedHashMap<>();
            for (String[] lane : CATALOGUE_LANES) {
                String body = auth(client(environment).get().uri(b -> b.path("/shipping/network")
                                .queryParam("businessUnit", 0).queryParam("deliveryDirection", 1)
                                .queryParam("numberOfParcels", 1).queryParam("totalWeight", 1)
                                .queryParam("shipmentType", 0)
                                .queryParam("collectionDetails.address.countryCode", lane[0])
                                .queryParam("collectionDetails.address.postcode", lane[1])
                                .queryParam("deliveryDetails.address.countryCode", lane[2])
                                .queryParam("deliveryDetails.address.postcode", lane[3])
                                .build()), accessToken)
                        .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
                for (ServiceOffering o : parseNetworks(body, lane[4])) byCode.putIfAbsent(o.serviceCode(), o);
            }
            return new ServiceAvailability(List.copyOf(byCode.values()), true, "DPD GET /shipping/network", true);
        } catch (Exception ex) {
            log.warn("DPD GET /shipping/network failed: {}", ex.getMessage());
            return new ServiceAvailability(FALLBACK_SERVICES, false,
                    "built-in DPD list — network call failed: " + ex.getMessage());
        }
    }

    List<ServiceOffering> parseNetworks(String body, String scope) {
        List<ServiceOffering> out = new ArrayList<>();
        for (JsonNode n : dataOrThrow(body, "network")) {
            String code = n.path("network").path("networkCode").asText(null);
            if (!StringUtils.hasText(code)) continue;
            String name = firstText(n.path("network").path("networkDescription").asText(null),
                    joinText(n.path("product").path("productDescription").asText(null),
                            n.path("service").path("serviceDescription").asText(null)),
                    code);
            out.add(new ServiceOffering(code, name.startsWith("DPD") ? name : "DPD " + name, scope));
        }
        return out;
    }

    /** DPD prices by weight and network, not by box — a single open "parcel" type. */
    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        return new PackageAvailability(List.of(new PackageOffering("parcel", "DPD parcel",
                null, null, null, new BigDecimal("30"), false, "BOTH")), false, "DPD has no package catalogue");
    }

    // ───────────────────────── shipment ─────────────────────────

    /** DPD UK publishes no rate API — prices follow the account contract. */
    @Override
    public boolean lacksRateApi() {
        return true;
    }

    @Override
    public Map<String, Object> previewShipmentPayload(ShipmentRequestDTO request) {
        return buildShipmentPayload(request);
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!isRealToken(accessToken)) {
            throw new CarrierException(CARRIER_CODE,
                    "DPD account is not verified live — check the MyDPD username, password and account number.");
        }
        if (isSandbox(environment) && !StringUtils.hasText(carrierProperties.getDpd().getSandboxBaseUrl())) {
            log.warn("DPD has no sandbox — SANDBOX account {} is creating a real shipment; void it before the manifest.",
                    request.getAccountNumber());
        }
        try {
            String response = auth(client(environment).post().uri("/shipping/shipment")
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                            .body(buildShipmentPayload(request)), accessToken)
                    .retrieve().body(String.class);
            String shipmentId = dataOrThrow(response, "createShipment").path("shipmentId").asText(null);
            String label = StringUtils.hasText(shipmentId) ? fetchLabel(shipmentId, accessToken, environment) : null;
            return parseShipmentResponse(response, label);
        } catch (CarrierException cex) {
            throw cex;
        } catch (RestClientResponseException ex) {
            log.warn("DPD createShipment failed: {} {}", ex.getStatusCode().value(), brief(ex.getResponseBodyAsString()));
            throw CarrierExceptionMapper.map(CARRIER_CODE, ex, "createShipment");
        } catch (Exception ex) {
            log.warn("DPD createShipment failed: {}", ex.getMessage());
            throw CarrierExceptionMapper.map(CARRIER_CODE, ex, "createShipment");
        }
    }

    /** {@code POST /shipping/shipment} body — one consignment, parcels counted not itemised. */
    Map<String, Object> buildShipmentPayload(ShipmentRequestDTO r) {
        CarrierProperties.Dpd dpd = carrierProperties.getDpd();
        List<PackageDetailDTO> pieces = r.getPackages() == null ? List.of() : r.getPackages();
        int parcels = Math.max(1, pieces.size());
        BigDecimal totalKg = pieces.isEmpty() ? kg(r.getWeight(), r.getWeightUnit())
                : pieces.stream().map(p -> kg(p.getWeight(), firstText(p.getWeightUnit(), r.getWeightUnit())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean international = !"GB".equalsIgnoreCase(firstText(r.getRecipientCountryCode(), "GB"));

        Map<String, Object> consignment = new LinkedHashMap<>();
        consignment.put("consignmentNumber", null);
        consignment.put("consignmentRef", null);
        consignment.put("parcel", List.of());
        consignment.put("collectionDetails", party(r.getShipperName(), r.getShipperCompany(), r.getShipperPhone(),
                null, null, r.getShipperAddressLine1(), r.getShipperAddressLine2(), r.getShipperCity(),
                r.getShipperState(), r.getShipperPostalCode(), r.getShipperCountryCode()));
        consignment.put("deliveryDetails", party(r.getRecipientName(), r.getRecipientCompany(), r.getRecipientPhone(),
                r.getRecipientEmail(), r.getRecipientPhone(), r.getRecipientAddressLine1(),
                firstText(r.getRecipientAddressLine2(), r.getRecipientAddressLine3()), r.getRecipientCity(),
                r.getRecipientState(), r.getRecipientPostalCode(), r.getRecipientCountryCode()));
        consignment.put("networkCode", firstText(r.getServiceType(), dpd.getDefaultNetworkCode()));
        consignment.put("numberOfParcels", parcels);
        consignment.put("totalWeight", totalKg);
        putIfText(consignment, "shippingRef1", cut(r.getReferenceNumber(), 25));
        putIfText(consignment, "shippingRef2", cut(r.getPoNumber(), 25));
        putIfText(consignment, "shippingRef3", cut(r.getDepartmentNumber(), 25));
        if (international && r.getDeclaredValue() != null) consignment.put("customsValue", r.getDeclaredValue());
        putIfText(consignment, "deliveryInstructions", cut(r.getSpecialInstructions(), 50));
        putIfText(consignment, "parcelDescription", cut(firstText(
                pieces.isEmpty() ? null : pieces.get(0).getDescription(),
                r.getIntl() == null || r.getIntl().getCommodities() == null || r.getIntl().getCommodities().isEmpty()
                        ? null : r.getIntl().getCommodities().get(0).getDescription()), 50));
        consignment.put("liability", r.getInsuredValue() != null && r.getInsuredValue().signum() > 0);
        if (r.getInsuredValue() != null) consignment.put("liabilityValue", r.getInsuredValue());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", null);
        body.put("collectionOnDelivery", false);
        body.put("invoice", null);
        body.put("collectionDate", collectionDate(r.getShipDateOverride(), r.getShipperTimezone()));
        body.put("consolidate", false);
        body.put("consignment", List.of(consignment));
        return body;
    }

    private static Map<String, Object> party(String name, String company, String phone, String email, String mobile,
                                             String street, String locality, String town, String county,
                                             String postcode, String country) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("contactName", cut(firstText(name, company, "Receiver"), 35));
        putIfText(contact, "telephone", cut(digitsAndPlus(phone), 15));
        Map<String, Object> address = new LinkedHashMap<>();
        putIfText(address, "organisation", cut(company, 35));
        address.put("countryCode", country == null ? "GB" : country.trim().toUpperCase(Locale.ROOT));
        address.put("postcode", cut(postcode, 8));
        address.put("street", cut(street, 35));
        putIfText(address, "locality", cut(locality, 35));
        address.put("town", cut(town, 35));
        putIfText(address, "county", cut(county, 35));
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("contactDetails", contact);
        party.put("address", address);
        if (StringUtils.hasText(email) || StringUtils.hasText(mobile)) {
            Map<String, Object> notify = new LinkedHashMap<>();
            putIfText(notify, "email", cut(email, 50));
            putIfText(notify, "mobile", cut(digitsAndPlus(mobile), 15));
            party.put("notificationDetails", notify);
        }
        return party;
    }

    /** DPD wants a local date-time; 09:00 keeps it inside any depot's collection window. */
    static String collectionDate(LocalDate override, String timezone) {
        ZoneId zone = ZoneId.of("Europe/London");
        if (StringUtils.hasText(timezone)) {
            try { zone = ZoneId.of(timezone.trim()); } catch (Exception ignored) { /* fall back to London */ }
        }
        LocalDate date = override != null ? override : LocalDate.now(zone);
        return date.atTime(9, 0).format(DPD_DATETIME);
    }

    /** DPD weighs in kilograms with one decimal; always round up so a parcel is never under-declared. */
    static BigDecimal kg(BigDecimal weight, String unit) {
        if (weight == null) return BigDecimal.ZERO;
        BigDecimal k = "KG".equalsIgnoreCase(unit) ? weight
                : "G".equalsIgnoreCase(unit) ? weight.movePointLeft(3)
                : "OZ".equalsIgnoreCase(unit) ? weight.multiply(KG_PER_OZ)
                : weight.multiply(KG_PER_LB);
        return k.setScale(1, RoundingMode.CEILING).max(new BigDecimal("0.1"));
    }

    /** {@code data.shipmentId} + {@code data.consignmentDetail[0].{consignmentNumber, parcelNumbers[]}}. */
    ShipmentResult parseShipmentResponse(String response, String labelBase64) {
        JsonNode data = dataOrThrow(response, "createShipment");
        String shipmentId = data.path("shipmentId").asText(null);
        JsonNode detail = data.path("consignmentDetail").path(0);
        String consignment = detail.path("consignmentNumber").asText(null);
        if (!StringUtils.hasText(consignment)) {
            throw new CarrierValidationException(CARRIER_CODE,
                    "DPD created shipment " + shipmentId + " but returned no consignment number.", response);
        }
        List<PackageTracking> packages = new ArrayList<>();
        int seq = 1;
        for (JsonNode p : detail.path("parcelNumbers")) {
            String n = p.asText();
            packages.add(new PackageTracking(seq++, n, TRACKING_URL + n, null, labelBase64, null, shipmentId));
        }
        if (packages.isEmpty()) {
            packages.add(new PackageTracking(1, consignment, TRACKING_URL + consignment, null, labelBase64, null, shipmentId));
        }
        return new ShipmentResult(consignment, TRACKING_URL + consignment, null, labelBase64, null, null, response, packages);
    }

    /** {@code GET /shipping/shipment/{id}/label/} — the Accept header selects the print format. */
    private String fetchLabel(String shipmentId, String token, String environment) {
        try {
            byte[] bytes = auth(client(environment).get().uri("/shipping/shipment/{id}/label/", shipmentId), token)
                    .header("Accept", carrierProperties.getDpd().getLabelAccept())
                    .retrieve().body(byte[].class);
            return bytes == null || bytes.length == 0 ? null : Base64.getEncoder().encodeToString(bytes);
        } catch (RestClientResponseException ex) {
            log.warn("DPD label for shipment {} failed: {} {} — reprint it from the order page.",
                    shipmentId, ex.getStatusCode().value(), brief(ex.getResponseBodyAsString()));
            return null;
        } catch (Exception ex) {
            log.warn("DPD label for shipment {} failed: {}", shipmentId, ex.getMessage());
            return null;
        }
    }

    @Override
    public LabelReprintResult reprintLabel(String trackingNumber, String labelSize, String labelFormat,
                                           String accessToken, String environment) {
        if (!isRealToken(accessToken)) {
            return new LabelReprintResult(CARRIER_CODE, null, null, "ERROR", "DPD account is not verified live.", null);
        }
        try {
            String shipmentId = findShipmentId(trackingNumber, accessToken, environment);
            if (shipmentId == null) {
                return new LabelReprintResult(CARRIER_CODE, null, null, "ERROR",
                        "No DPD shipment carries consignment " + trackingNumber + ".", null);
            }
            String label = fetchLabel(shipmentId, accessToken, environment);
            return label == null
                    ? new LabelReprintResult(CARRIER_CODE, null, null, "ERROR", "DPD returned no label for shipment " + shipmentId + ".", null)
                    : new LabelReprintResult(CARRIER_CODE, null, label, "OK", "DPD label for shipment " + shipmentId + ".", null);
        } catch (Exception ex) {
            return new LabelReprintResult(CARRIER_CODE, null, null, "ERROR", "DPD reprint failed: " + ex.getMessage(), null);
        }
    }

    // ───────────────────────── void / manifest / tracking ─────────────────────────

    /** {@code PUT /shipping/shipment/{id}/?action=Void} after resolving the shipmentId by consignment. */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                   String accountNumber, String senderCountryCode) {
        if (!isRealToken(accessToken)) {
            return new VoidResult(trackingNumber, false, "ERROR", "DPD account is not verified live.", null);
        }
        try {
            String shipmentId = findShipmentId(trackingNumber, accessToken, environment);
            if (shipmentId == null) {
                return new VoidResult(trackingNumber, false, "ERROR",
                        "No DPD shipment carries consignment " + trackingNumber + ".", null);
            }
            String body = auth(client(environment).put().uri("/shipping/shipment/{id}/?action=Void", shipmentId)
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                            .body(Map.of("isVoided", true)), accessToken)
                    .retrieve().body(String.class);
            dataOrThrow(body == null ? "{}" : body, "void");
            return new VoidResult(trackingNumber, true, "VOIDED", "DPD shipment " + shipmentId + " voided.", body);
        } catch (CarrierException cex) {
            return new VoidResult(trackingNumber, false, "ERROR", cex.getMessage(), null);
        } catch (RestClientResponseException ex) {
            return new VoidResult(trackingNumber, false, "ERROR", "DPD void failed (" + ex.getStatusCode().value()
                    + "): " + brief(ex.getResponseBodyAsString()), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return new VoidResult(trackingNumber, false, "ERROR", "DPD void failed: " + ex.getMessage(), null);
        }
    }

    /** {@code GET /shipping/shipment/?searchCriteria.consignmentNumber=…} → shipmentId, matched on consignment or parcel. */
    private String findShipmentId(String trackingNumber, String token, String environment) {
        String body = auth(client(environment).get().uri(b -> b.path("/shipping/shipment/")
                        .queryParam("searchCriteria.consignmentNumber", trackingNumber.trim()).build()), token)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        return matchShipmentId(dataOrThrow(body == null ? "{}" : body, "findShipment"), trackingNumber.trim());
    }

    static String matchShipmentId(JsonNode data, String trackingNumber) {
        JsonNode list = data.has("results") ? data.path("results") : data;
        if (!list.isArray()) list = objectAsArray(list);
        for (JsonNode s : list) {
            for (JsonNode c : s.path("consignmentDetail")) {
                if (trackingNumber.equalsIgnoreCase(c.path("consignmentNumber").asText(""))) {
                    return s.path("shipmentId").asText(null);
                }
                for (JsonNode p : c.path("parcelNumbers")) {
                    if (trackingNumber.equalsIgnoreCase(p.asText(""))) return s.path("shipmentId").asText(null);
                }
            }
        }
        return null;
    }

    /** {@code POST /shipping/manifest/?collectionDate=…} then fetch the printable manifest. */
    @Override
    public CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
        if (!isRealToken(accessToken)) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR", "DPD account is not verified live.", null);
        }
        LocalDate day = request.closeDate() != null ? request.closeDate() : LocalDate.now(ZoneId.of("Europe/London"));
        try {
            String body = auth(client(environment).post().uri(b -> b.path("/shipping/manifest/")
                            .queryParam("collectionDate", day.atStartOfDay().format(DPD_DATETIME)).build())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON), accessToken)
                    .retrieve().body(String.class);
            JsonNode data = dataOrThrow(body == null ? "{}" : body, "manifest");
            List<String> ids = new ArrayList<>();
            for (JsonNode m : data.isArray() ? data : objectAsArray(data)) {
                String id = m.path("manifestId").asText(null);
                if (StringUtils.hasText(id)) ids.add(id);
            }
            if (ids.isEmpty()) {
                return new CloseOutResult(CARRIER_CODE, null, null, null, count, "NOTHING_TO_MANIFEST",
                        "DPD found no shipments to manifest for " + day + ".", body);
            }
            String pdf = null;
            try {
                byte[] bytes = auth(client(environment).get().uri("/shipping/manifest/{id}/", ids.get(0)), accessToken)
                        .header("Accept", carrierProperties.getDpd().getLabelAccept()).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) pdf = Base64.getEncoder().encodeToString(bytes);
            } catch (Exception ex) {
                log.warn("DPD manifest {} print failed: {}", ids.get(0), ex.getMessage());
            }
            return new CloseOutResult(CARRIER_CODE, String.join(",", ids), null, pdf, count, "MANIFESTED",
                    "DPD manifest " + String.join(", ", ids) + " created for " + day + ".", body);
        } catch (CarrierException cex) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR", cex.getMessage(), null);
        } catch (RestClientResponseException ex) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR", "DPD manifest failed ("
                    + ex.getStatusCode().value() + "): " + brief(ex.getResponseBodyAsString()), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR", "DPD manifest failed: " + ex.getMessage(), null);
        }
    }

    /** DPD UK publishes no tracking API — the public tracker URL is the honest answer. */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        if (!StringUtils.hasText(trackingNumber)) {
            throw new CarrierException(CARRIER_CODE, "Tracking number is required.");
        }
        String number = trackingNumber.trim();
        return new TrackingResult(number, "UNKNOWN", TRACKING_URL + number, null, null, false, null);
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * DPD wraps every answer as {@code {"error": …, "data": …}}; {@code error} is an object
     * or an array of {@code {errorCode, errorType, obj, errorMessage}}. Returns {@code data}.
     */
    JsonNode dataOrThrow(String body, String operation) {
        try {
            JsonNode root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull() && !(error.isArray() && error.isEmpty())) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode e : error.isArray() ? error : objectAsArray(error)) {
                    if (!sb.isEmpty()) sb.append("; ");
                    sb.append(e.path("errorMessage").asText(e.path("errorType").asText("error")));
                    String obj = e.path("obj").asText(null);
                    if (StringUtils.hasText(obj)) sb.append(" [").append(obj).append("]");
                }
                throw new CarrierValidationException(CARRIER_CODE, "DPD rejected " + operation + ": " + sb, body);
            }
            return root.path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CarrierException(CARRIER_CODE, "DPD returned invalid JSON on " + operation + ": " + ex.getOriginalMessage());
        }
    }

    private static JsonNode objectAsArray(JsonNode node) {
        return new ObjectMapper().createArrayNode().add(node);
    }

    /** SANDBOX → {@code sandboxBaseUrl} when configured (the local mock); everything else → live host. */
    String baseUrl(String environment) {
        CarrierProperties.Dpd dpd = carrierProperties.getDpd();
        return isSandbox(environment) && StringUtils.hasText(dpd.getSandboxBaseUrl())
                ? dpd.getSandboxBaseUrl() : dpd.getApiBaseUrl();
    }

    private RestClient client(String environment) {
        return HttpClients.newBuilder().baseUrl(baseUrl(environment)).build();
    }

    private static boolean isRealToken(String token) {
        return StringUtils.hasText(token) && !token.contains("-local-");
    }

    private static boolean isSandbox(String environment) {
        return environment != null && environment.toUpperCase(Locale.ROOT).startsWith("SAND");
    }

    private static String brief(String body) {
        if (body == null) return "";
        return body.length() > 600 ? body.substring(0, 600) + "…" : body;
    }

    static String cut(String s, int max) {
        if (!StringUtils.hasText(s)) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String digitsAndPlus(String phone) {
        if (!StringUtils.hasText(phone)) return null;
        String d = phone.replaceAll("[^0-9+]", "");
        return d.isEmpty() ? null : d;
    }

    private static void putIfText(Map<String, Object> m, String key, String value) {
        if (StringUtils.hasText(value)) m.put(key, value.trim());
    }

    private static String firstText(String... values) {
        for (String v : values) if (StringUtils.hasText(v)) return v.trim();
        return null;
    }

    private static String joinText(String a, String b) {
        String j = firstText(a) == null ? "" : a.trim();
        if (StringUtils.hasText(b) && !j.contains(b.trim())) j = (j + " " + b.trim()).trim();
        return j.isEmpty() ? null : j;
    }
}
