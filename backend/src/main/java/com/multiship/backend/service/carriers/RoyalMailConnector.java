package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.exception.CarrierConnectionException;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.exceptions.CarrierException;
import com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper;
import com.multiship.backend.service.carriers.exceptions.CarrierValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Royal Mail via the <b>Click &amp; Drop</b> public API
 * ({@code https://api.parcel.royalmail.com/api/v1}).
 *
 * <p>Why Click &amp; Drop and not Pro Shipping: it is the product a UK
 * business gets by default, auth is a single API key (no OAuth dance, no
 * token refresh), and labels come back inline on the create call. Pro
 * Shipping v4 (OAuth2 + mandatory end-of-day manifest) is the swap if the
 * client turns out to hold those credentials instead — only
 * {@link #getAccessToken} and {@link #createShipment} would change.
 *
 * <p>Credential mapping onto {@code carrier_account_ref}: the API key lives
 * in {@code clientSecret}; {@code clientId} is unused (optional) and
 * {@code accountNumber} is the Royal Mail OBA account number for the
 * operator's records. The access token the rest of the platform passes
 * around IS the API key — every call sends {@code Authorization: Bearer <key>}.
 *
 * <p>Known limits of this API, all surfaced as explicit messages rather
 * than silent fallbacks:
 * <ul>
 *   <li>No sandbox. SANDBOX and PRODUCTION hit the same host; a test label
 *       is a real label until the order is deleted before manifesting.</li>
 *   <li>No tracking endpoint of its own. Live events come from Royal Mail's
 *       separate <b>Tracking API v2</b> (developer.royalmail.net), whose
 *       Client ID / Secret are platform settings
 *       ({@value #TRACKING_CLIENT_ID_KEY} / {@value #TRACKING_CLIENT_SECRET_KEY})
 *       rather than per-account credentials. Without them
 *       {@link #trackShipment} returns the public tracker URL.</li>
 *   <li>Label download and manifesting need an Online Business Account
 *       (the API answers 403 otherwise).</li>
 *   <li>Void takes a Click &amp; Drop order id, but the platform only hands
 *       us the tracking number, so {@link #voidShipment} scans recent
 *       orders to find it.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoyalMailConnector implements CarrierConnector {

    static final String CARRIER_CODE = "ROYAL_MAIL";
    private static final String CARRIER_NAME = "Royal Mail";

    /** Public consignment tracking — works without credentials. */
    private static final String TRACKING_URL = "https://www.royalmail.com/track-your-item#/tracking-results/";

    /** Click &amp; Drop package formats; anything else on the request maps to {@code parcel}. */
    static final Set<String> PACKAGE_FORMATS = Set.of(
            "letter", "largeLetter", "smallParcel", "mediumParcel", "largeParcel", "parcel", "documents");

    /** Shown in the UI before the account has synced its live catalogue; never persisted (live=false). */
    private static final List<ServiceOffering> FALLBACK_SERVICES = List.of(
            new ServiceOffering("TPN", "Royal Mail Tracked 24", "DOMESTIC"),
            new ServiceOffering("TPS", "Royal Mail Tracked 48", "DOMESTIC"),
            new ServiceOffering("SD1", "Royal Mail Special Delivery Guaranteed by 1pm", "DOMESTIC"),
            new ServiceOffering("CRL1", "Royal Mail 1st Class", "DOMESTIC"),
            new ServiceOffering("CRL2", "Royal Mail 2nd Class", "DOMESTIC"),
            new ServiceOffering("MTA", "Royal Mail International Tracked", "INTERNATIONAL"),
            new ServiceOffering("OLA", "Royal Mail International Standard", "INTERNATIONAL"));

    private static final BigDecimal GRAMS_PER_LB = new BigDecimal("453.59237");
    private static final BigDecimal GRAMS_PER_OZ = new BigDecimal("28.349523125");
    private static final BigDecimal MM_PER_IN = new BigDecimal("25.4");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** Admin settings (Settings → System) holding the developer.royalmail.net Tracking API credentials. */
    public static final String TRACKING_CLIENT_ID_KEY = "royalmail.tracking.client-id";
    public static final String TRACKING_CLIENT_SECRET_KEY = "royalmail.tracking.client-secret";

    /** Tracking API event codes that mean the item reached the recipient (incl. neighbour / safeplace). */
    private static final Set<String> DELIVERED_EVENT_CODES = Set.of("EVKOP", "EVKSP", "EVKDN", "EVKSF");

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    @Value("${royalmail.tracking.client-id:}")
    private String envTrackingClientId;

    @Value("${royalmail.tracking.client-secret:}")
    private String envTrackingClientSecret;

    /** API keys that answered {@code GET /carriers} — re-verified every 6h like DHL's Basic-Auth cache. */
    private final Cache<String, Boolean> verifiedKeys = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6)).maximumSize(1000).build();

    private final ThreadLocal<String> authFailureDetail = new ThreadLocal<>();

    // ───────────────────────── identity / config ─────────────────────────

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return CARRIER_NAME;
    }

    @Override
    public CarrierConfiguration getConfiguration() {
        CarrierProperties.RoyalMail rm = carrierProperties.getRoyalMail();
        return new CarrierConfiguration(CARRIER_CODE, CARRIER_NAME,
                rm.getApiBaseUrl(), rm.getApiBaseUrl(), "v1",
                rm.getApiBaseUrl(), "/orders", null, "/carriers",
                rm.getLogoUrl(), rm.getDocumentationUrl(), rm.getConnectionGuide(),
                rm.getDefaultServiceType(), rm.getDefaultPackageType(), "BASE64",
                carrierProperties.getDefaultEnvironment(), true);
    }

    // ───────────────────────── auth ─────────────────────────

    /** The API key is the secret; tolerate operators who paste it into the ID slot instead. */
    static String apiKeyOf(String clientId, String clientSecret) {
        return StringUtils.hasText(clientSecret) ? clientSecret.trim()
                : StringUtils.hasText(clientId) ? clientId.trim() : null;
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        if (apiKeyOf(clientId, clientSecret) == null) {
            throw new CarrierConnectionException("Royal Mail Click & Drop API key is required.");
        }
        return true;
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret, null, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        String key = apiKeyOf(clientId, clientSecret);
        if (key == null) {
            authFailureDetail.set("No API key supplied.");
            return CARRIER_CODE + "-local-missing-key";
        }
        if (verifiedKeys.getIfPresent(key) != null) {
            return key;
        }
        try {
            // GET /version is PUBLIC (answers 200 with no key at all), so it cannot verify
            // anything. /carriers is the cheapest call that actually enforces the Bearer key.
            client().get().uri("/carriers").header("Authorization", "Bearer " + key)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            verifiedKeys.put(key, Boolean.TRUE);
            return key;
        } catch (RestClientResponseException ex) {
            String detail = ex.getStatusCode().value() == 401
                    ? "Click & Drop rejected the API key (401). Re-copy it from Click & Drop → Settings → Integrations → API."
                    : "Click & Drop answered " + ex.getStatusCode().value() + " to the credential check: "
                            + brief(ex.getResponseBodyAsString());
            log.warn("Royal Mail credential check failed: {}", detail);
            authFailureDetail.set(detail);
            return CARRIER_CODE + "-local-" + ex.getStatusCode().value();
        } catch (Exception ex) {
            log.warn("Royal Mail credential check unreachable: {}", ex.getMessage());
            authFailureDetail.set("Could not reach Click & Drop: " + ex.getMessage());
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
        String token = getAccessToken(clientId, clientSecret, accountNumber, null);
        boolean live = !token.contains("-local-");
        String message = live ? "Royal Mail Click & Drop connection established."
                : "Royal Mail Click & Drop rejected the credentials: " + consumeAuthFailureDetail();
        return new CarrierConnectionResult(CARRIER_CODE, CARRIER_NAME, live, accountNumber,
                carrierProperties.getDefaultEnvironment(), live ? token : null,
                live ? LocalDateTime.now(ZoneOffset.UTC).plusDays(30) : null, message);
    }

    // ───────────────────────── catalogue ─────────────────────────

    /** Live: {@code GET /services} — the services the account is actually contracted for. */
    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
        if (!isRealToken(accessToken)) {
            return new ServiceAvailability(FALLBACK_SERVICES, false,
                    "built-in Royal Mail list — connect a verified Click & Drop account to sync live services");
        }
        try {
            String body = client().get().uri("/services").header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            List<ServiceOffering> offerings = new ArrayList<>();
            for (JsonNode carrier : objectMapper.readTree(body)) {
                for (JsonNode s : carrier.path("services")) {
                    String code = s.path("serviceCode").asText(null);
                    if (StringUtils.hasText(code)) {
                        offerings.add(new ServiceOffering(code, s.path("serviceName").asText(code), "BOTH"));
                    }
                }
            }
            return new ServiceAvailability(offerings, true, "Click & Drop GET /services", true);
        } catch (Exception ex) {
            log.warn("Royal Mail GET /services failed: {}", ex.getMessage());
            return new ServiceAvailability(FALLBACK_SERVICES, false,
                    "built-in Royal Mail list — Click & Drop services call failed: " + ex.getMessage());
        }
    }

    /** Click &amp; Drop has fixed format names but publishes no dimensions, so these carry no size caps. */
    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        List<PackageOffering> offerings = PACKAGE_FORMATS.stream().sorted()
                .map(f -> new PackageOffering(f, "Royal Mail " + f, null, null, null, null, false, "BOTH"))
                .toList();
        return new PackageAvailability(offerings, false, "Click & Drop package formats (no dimensions published)");
    }

    // ───────────────────────── shipment ─────────────────────────

    /** Click &amp; Drop publishes no rate API — postage follows the account contract. */
    @Override
    public boolean lacksRateApi() {
        return true;
    }

    @Override
    public Map<String, Object> previewShipmentPayload(ShipmentRequestDTO request) {
        return buildOrdersPayload(request);
    }

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!isRealToken(accessToken)) {
            throw new CarrierException(CARRIER_CODE,
                    "Royal Mail account is not verified live — check the Click & Drop API key on the carrier account.");
        }
        if (isSandbox(environment)) {
            log.warn("Royal Mail Click & Drop has no sandbox — SANDBOX account {} is buying a real label; "
                    + "delete the order before manifesting to avoid postage.", request.getAccountNumber());
        }
        try {
            String response = client().post().uri("/orders")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(buildOrdersPayload(request))
                    .retrieve().body(String.class);
            return parseCreateOrdersResponse(response);
        } catch (CarrierException cex) {
            throw cex;
        } catch (RestClientResponseException rlex) {
            log.warn("Royal Mail createShipment failed: {} {}", rlex.getStatusCode().value(),
                    brief(rlex.getResponseBodyAsString()));
            throw CarrierExceptionMapper.map(CARRIER_CODE, rlex, "createShipment");
        } catch (Exception ex) {
            log.warn("Royal Mail createShipment failed: {}", ex.getMessage());
            throw CarrierExceptionMapper.map(CARRIER_CODE, ex, "createShipment");
        }
    }

    /** {@code POST /orders} body — one order per shipment, label inline, CN22/23 on non-GB lanes. */
    Map<String, Object> buildOrdersPayload(ShipmentRequestDTO r) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderReference", orderReferenceFor(r));
        order.put("isRecipientABusiness", StringUtils.hasText(r.getRecipientCompany()));
        order.put("recipient", contact(r.getRecipientName(), r.getRecipientCompany(),
                r.getRecipientAddressLine1(), r.getRecipientAddressLine2(), r.getRecipientAddressLine3(),
                r.getRecipientCity(), r.getRecipientState(), r.getRecipientPostalCode(), r.getRecipientCountryCode(),
                r.getRecipientPhone(), r.getRecipientEmail()));
        Map<String, Object> sender = new LinkedHashMap<>();
        putIfText(sender, "tradingName", firstText(r.getShipperCompany(), r.getShipperName()));
        putIfText(sender, "phoneNumber", r.getShipperPhone());
        putIfText(sender, "emailAddress", r.getShipperEmail());
        order.put("sender", sender);
        order.put("packages", packages(r));
        order.put("orderDate", OffsetDateTime.now(ZoneOffset.UTC).format(ISO));
        putIfText(order, "specialInstructions", r.getSpecialInstructions());

        BigDecimal value = r.getDeclaredValue() == null ? BigDecimal.ZERO : r.getDeclaredValue();
        order.put("subtotal", value);
        order.put("shippingCostCharged", BigDecimal.ZERO);
        order.put("total", value);
        order.put("currencyCode", firstText(r.getDeclaredValueCurrency(), "GBP"));

        Map<String, Object> postage = new LinkedHashMap<>();
        putIfText(postage, "serviceCode", firstText(r.getServiceType(),
                carrierProperties.getRoyalMail().getDefaultServiceType()));
        postage.put("requestSignatureUponDelivery", StringUtils.hasText(r.getSignatureOption())
                && !"NONE".equalsIgnoreCase(r.getSignatureOption()));
        order.put("postageDetails", postage);

        boolean international = !"GB".equalsIgnoreCase(firstText(r.getRecipientCountryCode(), "GB"));
        if (international && r.getIntl() != null) {
            putIfText(order, "customsDeclarationCategory", customsCategory(r.getIntl().getReasonForExport()));
            putIfText(order, "deliveryTerm", r.getIntl().getIncoterms());
        }
        order.put("label", Map.of("includeLabelInResponse", true, "includeCN", international));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(order));
        return body;
    }

    private static Map<String, Object> contact(String name, String company, String line1, String line2,
                                               String line3, String city, String county, String postcode,
                                               String country, String phone, String email) {
        Map<String, Object> address = new LinkedHashMap<>();
        putIfText(address, "fullName", name);
        putIfText(address, "companyName", company);
        address.put("addressLine1", line1);
        putIfText(address, "addressLine2", line2);
        putIfText(address, "addressLine3", line3);
        address.put("city", city);
        putIfText(address, "county", county);
        putIfText(address, "postcode", postcode);
        address.put("countryCode", alpha3(country));
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("address", address);
        putIfText(contact, "phoneNumber", phone);
        putIfText(contact, "emailAddress", email);
        return contact;
    }

    private List<Map<String, Object>> packages(ShipmentRequestDTO r) {
        List<PackageDetailDTO> pieces = r.getPackages();
        List<Map<String, Object>> out = new ArrayList<>();
        if (pieces == null || pieces.isEmpty()) {
            out.add(pkg(r.getPackageType(), r.getWeight(), r.getWeightUnit(),
                    r.getLength(), r.getWidth(), r.getHeight(), r.getDimUnit()));
        } else {
            for (PackageDetailDTO p : pieces) {
                out.add(pkg(firstText(p.getPackageType(), r.getPackageType()),
                        p.getWeight(), firstText(p.getWeightUnit(), r.getWeightUnit()),
                        p.getLength(), p.getWidth(), p.getHeight(), firstText(p.getDimUnit(), r.getDimUnit())));
            }
        }
        // Customs lines ride on the first package: Click & Drop declares contents per package and
        // the platform's commodities aren't box-aware for single-piece parcels.
        if (r.getIntl() != null && r.getIntl().getCommodities() != null && !r.getIntl().getCommodities().isEmpty()) {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (CustomsCommodityDTO c : r.getIntl().getCommodities()) {
                Map<String, Object> line = new LinkedHashMap<>();
                putIfText(line, "name", c.getDescription());
                putIfText(line, "SKU", c.getSku());
                line.put("quantity", c.getQuantity() == null ? 1 : c.getQuantity());
                if (c.getUnitValue() != null) line.put("unitValue", c.getUnitValue());
                if (c.getUnitWeight() != null) {
                    line.put("unitWeightInGrams", grams(c.getUnitWeight(), r.getIntl().getWeightUnit()));
                }
                putIfText(line, "customsDescription", c.getDescription());
                putIfText(line, "customsCode", c.getHsCode());
                if (StringUtils.hasText(c.getCountryOfOrigin())) {
                    line.put("originCountryCode", alpha3(c.getCountryOfOrigin()));
                }
                contents.add(line);
            }
            out.get(0).put("contents", contents);
        }
        return out;
    }

    private static Map<String, Object> pkg(String type, BigDecimal weight, String weightUnit,
                                           BigDecimal l, BigDecimal w, BigDecimal h, String dimUnit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("weightInGrams", grams(weight, weightUnit));
        p.put("packageFormatIdentifier", packageFormat(type));
        if (l != null && w != null && h != null) {
            p.put("dimensions", Map.of("heightInMms", mm(h, dimUnit), "widthInMms", mm(w, dimUnit),
                    "depthInMms", mm(l, dimUnit)));
        }
        return p;
    }

    /** First order reference wins (max 40 chars); otherwise a short unique one so void can find the order. */
    static String orderReferenceFor(ShipmentRequestDTO r) {
        String ref = firstText(r.getReferenceNumber(), r.getPoNumber());
        if (ref == null) ref = "MS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return ref.length() > 40 ? ref.substring(0, 40) : ref;
    }

    static String packageFormat(String type) {
        if (!StringUtils.hasText(type)) return "parcel";
        String wanted = type.trim().replace("_", "").replace(" ", "");
        return PACKAGE_FORMATS.stream().filter(f -> f.equalsIgnoreCase(wanted)).findFirst().orElse("parcel");
    }

    static int grams(BigDecimal weight, String unit) {
        if (weight == null) return 0;
        BigDecimal g = "KG".equalsIgnoreCase(unit) ? weight.movePointRight(3)
                : "G".equalsIgnoreCase(unit) ? weight
                : "OZ".equalsIgnoreCase(unit) ? weight.multiply(GRAMS_PER_OZ)
                : weight.multiply(GRAMS_PER_LB);
        return g.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    static int mm(BigDecimal v, String unit) {
        BigDecimal m = "CM".equalsIgnoreCase(unit) ? v.movePointRight(1) : v.multiply(MM_PER_IN);
        return m.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** Click &amp; Drop wants ISO alpha-3 ("GBR"); the platform stores alpha-2. */
    static String alpha3(String code) {
        if (!StringUtils.hasText(code)) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.length() == 3) return c;
        if ("UK".equals(c)) return "GBR";
        try {
            return Locale.of("", c).getISO3Country();
        } catch (java.util.MissingResourceException ex) {
            return c;
        }
    }

    static String customsCategory(String reasonForExport) {
        if (!StringUtils.hasText(reasonForExport)) return null;
        return switch (reasonForExport.trim().toUpperCase(Locale.ROOT)) {
            case "GIFT" -> "gift";
            case "SAMPLE", "COMMERCIAL_SAMPLE" -> "commercialSample";
            case "DOCUMENTS" -> "documents";
            case "RETURN", "RETURNED_GOODS", "RETURN_AND_REPAIR" -> "returnedGoods";
            case "SOLD", "SALE", "SALE_OF_GOODS", "COMMERCIAL" -> "saleOfGoods";
            default -> "other";
        };
    }

    ShipmentResult parseCreateOrdersResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode created = root.path("createdOrders");
            if (!created.isArray() || created.isEmpty()) {
                throw new CarrierValidationException(CARRIER_CODE,
                        "Royal Mail rejected the order: " + firstError(root), response);
            }
            JsonNode order = created.get(0);
            if (order.path("labelErrors").isArray() && !order.path("labelErrors").isEmpty()) {
                JsonNode e = order.path("labelErrors").get(0);
                throw new CarrierValidationException(CARRIER_CODE,
                        "Royal Mail created order " + order.path("orderIdentifier").asText()
                                + " but could not produce the label: " + e.path("message").asText(e.path("code").asText()),
                        response);
            }
            String tracking = order.path("trackingNumber").asText(null);
            String labelPdf = order.path("label").asText(null);
            String trackingUrl = StringUtils.hasText(tracking) ? TRACKING_URL + tracking : null;
            List<PackageTracking> packages = new ArrayList<>();
            int seq = 1;
            for (JsonNode p : order.path("packages")) {
                String t = p.path("trackingNumber").asText(tracking);
                packages.add(new PackageTracking(seq++, t, StringUtils.hasText(t) ? TRACKING_URL + t : null,
                        null, labelPdf, null));
            }
            if (packages.isEmpty() && StringUtils.hasText(tracking)) {
                packages.add(new PackageTracking(1, tracking, trackingUrl, null, labelPdf, null));
            }
            return new ShipmentResult(tracking, trackingUrl, null, labelPdf, null, null, response, packages);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw CarrierExceptionMapper.map(CARRIER_CODE, ex, "parseCreateOrdersResponse");
        }
    }

    private static String firstError(JsonNode root) {
        JsonNode failed = root.path("failedOrders");
        if (failed.isArray() && !failed.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode e : failed.get(0).path("errors")) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(e.path("errorMessage").asText(e.path("errorCode").asText("error")));
                for (JsonNode f : e.path("fields")) {
                    sb.append(" [").append(f.path("fieldName").asText()).append("]");
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return root.path("message").asText("no order was created");
    }

    // ───────────────────────── void / manifest / tracking ─────────────────────────

    /**
     * {@code DELETE /orders/{id}} — but the platform only knows the tracking number, so
     * find the order first. ponytail: scans the last 14 days, 100 per page, max 10 pages;
     * persist the Click &amp; Drop orderIdentifier on the label row if void volume grows.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                   String accountNumber, String senderCountryCode) {
        if (!isRealToken(accessToken)) {
            return new VoidResult(trackingNumber, false, "ERROR", "Royal Mail account is not verified live.", null);
        }
        try {
            String orderId = findOrderIdByTracking(trackingNumber, accessToken);
            if (orderId == null) {
                return new VoidResult(trackingNumber, false, "ERROR",
                        "No Click & Drop order in the last 14 days carries tracking " + trackingNumber + ".", null);
            }
            String body = client().delete().uri("/orders/{id}", orderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            boolean deleted = root.path("deletedOrders").isArray() && !root.path("deletedOrders").isEmpty();
            String msg = deleted ? "Royal Mail order " + orderId + " deleted."
                    : root.path("errors").path(0).path("message").asText("Royal Mail did not delete order " + orderId + ".");
            return new VoidResult(trackingNumber, deleted, deleted ? "VOIDED" : "ERROR", msg, body);
        } catch (RestClientResponseException ex) {
            String msg = ex.getStatusCode().value() == 403
                    ? "Royal Mail: deleting orders is not enabled on this Click & Drop account."
                    : "Royal Mail void failed (" + ex.getStatusCode().value() + "): "
                            + brief(ex.getResponseBodyAsString());
            return new VoidResult(trackingNumber, false, "ERROR", msg, ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return new VoidResult(trackingNumber, false, "ERROR", "Royal Mail void failed: " + ex.getMessage(), null);
        }
    }

    private String findOrderIdByTracking(String trackingNumber, String accessToken) throws Exception {
        String since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(14).format(ISO);
        String token = null;
        for (int page = 0; page < 10; page++) {
            final String cont = token;
            String body = client().get()
                    .uri(b -> {
                        b.path("/orders/full").queryParam("pageSize", 100).queryParam("startDateTime", since);
                        if (cont != null) b.queryParam("continuationToken", cont);
                        return b.build();
                    })
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode o : root.path("orders")) {
                if (trackingNumber.equalsIgnoreCase(o.path("shippingDetails").path("trackingNumber").asText(""))) {
                    return o.path("orderIdentifier").asText();
                }
            }
            token = root.path("continuationToken").asText(null);
            if (!StringUtils.hasText(token)) break;
        }
        return null;
    }

    /** {@code POST /manifests} — manifests every un-manifested order on the account (OBA accounts only). */
    @Override
    public CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
        if (!isRealToken(accessToken)) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR",
                    "Royal Mail account is not verified live.", null);
        }
        try {
            var entity = client().post().uri("/manifests")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(Map.of()).retrieve().toEntity(String.class);
            String body = entity.getBody() == null ? "{}" : entity.getBody();
            JsonNode manifests = objectMapper.readTree(body).path("manifests");
            if (entity.getStatusCode().value() == 200 || manifests.isEmpty()) {
                return new CloseOutResult(CARRIER_CODE, null, null, null, count, "NOTHING_TO_MANIFEST",
                        "Royal Mail found no orders eligible for manifesting.", body);
            }
            List<String> ids = new ArrayList<>();
            manifests.forEach(m -> ids.add(m.asText()));
            return new CloseOutResult(CARRIER_CODE, String.join(",", ids), null, null, count, "ACCEPTED",
                    "Royal Mail accepted the manifest request; paperwork is generated asynchronously "
                            + "(GET /manifests/{guid}).", body);
        } catch (RestClientResponseException ex) {
            String msg = ex.getStatusCode().value() == 403
                    ? "Royal Mail manifesting needs an Online Business Account (OBA) — this Click & Drop account is not OBA."
                    : "Royal Mail manifest failed (" + ex.getStatusCode().value() + "): "
                            + brief(ex.getResponseBodyAsString());
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR", msg, ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return new CloseOutResult(CARRIER_CODE, null, null, null, count, "ERROR",
                    "Royal Mail manifest failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Live events via the Tracking API v2 when its platform credentials are set;
     * otherwise the public tracker URL. Unlike the other carriers the 1-arg form
     * can go live because the Tracking API is platform-scoped, not per account —
     * so a Royal Mail order tracks even before any Click &amp; Drop account exists.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        if (!StringUtils.hasText(trackingNumber)) {
            throw new CarrierException(CARRIER_CODE, "Tracking number is required.");
        }
        String number = trackingNumber.trim();
        String[] creds = trackingCredentials();
        if (creds == null) {
            return urlOnly(number, null);
        }
        try {
            String body = HttpClients.newBuilder().baseUrl(carrierProperties.getRoyalMail().getTrackingBaseUrl()).build()
                    .get().uri("/{id}/events", number)
                    .header("X-IBM-Client-Id", creds[0])
                    .header("X-IBM-Client-Secret", creds[1])
                    .header("X-Accept-RMG-Terms", "yes")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            return parseTrackingResponse(body, number);
        } catch (CarrierException cex) {
            log.warn("Royal Mail tracking {} rejected: {}", number, cex.getMessage());
            return urlOnly(number, cex.getMessage());
        } catch (RestClientResponseException ex) {
            String detail = ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403
                    ? "Royal Mail Tracking API rejected the Client ID / Secret (" + ex.getStatusCode().value()
                            + "). Check them under Settings → System."
                    : "Royal Mail Tracking API answered " + ex.getStatusCode().value() + ": " + brief(ex.getResponseBodyAsString());
            log.warn("Royal Mail tracking {} failed: {}", number, detail);
            return urlOnly(number, detail);
        } catch (Exception ex) {
            log.warn("Royal Mail tracking {} unreachable: {}", number, ex.getMessage());
            return urlOnly(number, "Could not reach the Royal Mail Tracking API: " + ex.getMessage());
        }
    }

    /** The per-account token is the Click &amp; Drop key, which the Tracking API doesn't accept — same path as the 1-arg form. */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        return trackShipment(trackingNumber);
    }

    /** Settings first (rotatable from the admin UI), env vars second; null when either half is missing. */
    private String[] trackingCredentials() {
        String id = systemSettingService.getDecrypted(TRACKING_CLIENT_ID_KEY).filter(StringUtils::hasText)
                .orElse(envTrackingClientId);
        String secret = systemSettingService.getDecrypted(TRACKING_CLIENT_SECRET_KEY).filter(StringUtils::hasText)
                .orElse(envTrackingClientSecret);
        return StringUtils.hasText(id) && StringUtils.hasText(secret) ? new String[]{id.trim(), secret.trim()} : null;
    }

    /** URL-only result; {@code detail} (why live tracking didn't happen) rides in rawResponse for diagnostics. */
    private static TrackingResult urlOnly(String number, String detail) {
        return new TrackingResult(number, "UNKNOWN", TRACKING_URL + number, null, null, false, detail);
    }

    /**
     * {@code GET /mailpieces/v2/{id}/events} → TrackingResult. Shape:
     * {@code mailPieces.summary.{lastEventName,statusCategory,statusDescription,lastEventLocationName}},
     * {@code mailPieces.estimatedDelivery.date}, {@code mailPieces.events[].{eventCode,eventName,eventDateTime,locationName}};
     * failures arrive as a top-level {@code errors[]} with {@code errorDescription}.
     */
    TrackingResult parseTrackingResponse(String body, String number) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode e = errors.get(0);
                throw new CarrierException(CARRIER_CODE, e.path("errorDescription").asText(e.path("errorCode").asText("error"))
                        + (e.path("errorResolution").isMissingNode() ? "" : " " + e.path("errorResolution").asText("")));
            }
            JsonNode piece = root.path("mailPieces");
            if (piece.isArray()) piece = piece.path(0);
            JsonNode summary = piece.path("summary");

            List<TrackingEvent> events = new ArrayList<>();
            boolean delivered = false;
            for (JsonNode ev : piece.path("events")) {
                String code = ev.path("eventCode").asText(null);
                if (code != null && DELIVERED_EVENT_CODES.contains(code)) delivered = true;
                events.add(new TrackingEvent(parseIso(ev.path("eventDateTime").asText(null)), code,
                        ev.path("eventName").asText(""), ev.path("locationName").asText(null)));
            }
            events.sort(Comparator.comparing(TrackingEvent::timestamp, Comparator.nullsFirst(Comparator.naturalOrder())));

            String category = summary.path("statusCategory").asText("");
            if ("DELIVERED".equalsIgnoreCase(category.trim())) delivered = true;
            String status = firstText(summary.path("lastEventName").asText(null), summary.path("statusDescription").asText(null),
                    category, "UNKNOWN");
            String location = firstText(summary.path("lastEventLocationName").asText(null),
                    events.isEmpty() ? null : events.get(events.size() - 1).location());
            LocalDateTime eta = null;
            String etaDate = piece.path("estimatedDelivery").path("date").asText(null);
            if (StringUtils.hasText(etaDate)) {
                try { eta = LocalDate.parse(etaDate.substring(0, 10)).atStartOfDay(); } catch (Exception ignored) { /* ETA is optional */ }
            }
            return new TrackingResult(number, status, TRACKING_URL + number, location, eta, delivered, body, List.copyOf(events));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CarrierException(CARRIER_CODE, "Royal Mail Tracking API returned invalid JSON: " + ex.getOriginalMessage());
        }
    }

    private static LocalDateTime parseIso(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ex) {
            try { return LocalDateTime.parse(value); } catch (Exception ignored) { return null; }
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private RestClient client() {
        return HttpClients.newBuilder().baseUrl(carrierProperties.getRoyalMail().getApiBaseUrl()).build();
    }

    private static boolean isRealToken(String token) {
        return StringUtils.hasText(token) && !token.contains("-local-");
    }

    private static boolean isSandbox(String environment) {
        return environment != null && environment.toUpperCase(Locale.ROOT).startsWith("SAND");
    }

    /** Click &amp; Drop error bodies never echo the API key; just keep log lines bounded. */
    private static String brief(String body) {
        if (body == null) return "";
        return body.length() > 600 ? body.substring(0, 600) + "…" : body;
    }

    private static void putIfText(Map<String, Object> m, String key, String value) {
        if (StringUtils.hasText(value)) m.put(key, value.trim());
    }

    private static String firstText(String... values) {
        for (String v : values) if (StringUtils.hasText(v)) return v.trim();
        return null;
    }
}
