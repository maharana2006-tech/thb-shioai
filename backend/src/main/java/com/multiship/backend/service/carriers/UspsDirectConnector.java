package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import com.multiship.backend.util.UnitConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * USPS Direct v3 connector — talks straight to USPS's own OAuth-based v3
 * APIs at {@code apis.usps.com} (CAT sandbox: {@code apis-tem.usps.com}).
 *
 * <p>Sits alongside {@link StampsConnector} — both carriers publish the
 * {@code USPS} carrier code because they both print USPS labels. Which
 * connector actually handles the shipment is decided by
 * {@code CarrierServiceImpl} based on the account row's routing flag
 * (Agent C's territory). This class only worries about the wire format.
 *
 * <p>PR-A scope is domestic-only:
 * <ul>
 *   <li>OAuth token minting via {@link UspsOAuthTokenCache} (platform
 *       credentials from {@code /settings/system}).</li>
 *   <li>Per-tenant payment-authorization token via
 *       {@link UspsPaymentAuthCache} (needs CRID + MID + account from
 *       {@code CarrierAccountRef}).</li>
 *   <li>Rate shop via {@code POST /prices/v3/total-rates/search}.</li>
 *   <li>Label create via {@code POST /labels/v3/label} with dual-token
 *       headers.</li>
 * </ul>
 *
 * <p>PR-B adds tracking + webhook parse/verify:
 * <ul>
 *   <li>Tracking via {@code GET /tracking/v3.2/tracking/{trackingNumber}?expand=DETAIL}
 *       (v3.2 — v3.0 retires 2027-07-31).</li>
 *   <li>Webhook parse/verify for USPS Subscriptions-Tracking v3.2 push
 *       notifications. Signature is {@code X-HMAC} =
 *       {@code base64(HMAC-SHA256(timestamp + rawBody, secret))}.</li>
 * </ul>
 * Void, close-out, international labels, address validation and pickup
 * all inherit the {@code CarrierConnector} default NOT_SUPPORTED — they
 * land in follow-up PRs (C-E) on this same integration track.
 *
 * <p>Boundary guards mirror the F7 / FDX-I2 shape established by every
 * other connector: blank OAuth token or {@code -local-} placeholder →
 * {@link IllegalStateException} pointing at {@code /settings/system};
 * missing CRID / MID / account for the tenant →
 * {@link IllegalArgumentException} pointing at {@code /settings/carriers}.
 *
 * <p><b>Note on {@link CarrierAccountRef} coupling</b>: Agent C is
 * adding {@code usps_direct_account_number} / {@code usps_direct_crid} /
 * {@code usps_direct_mid} columns to the entity. Because we can't
 * compile against getters that don't exist yet, tenant identifiers are
 * fetched via a small {@link JdbcTemplate} native query. Once Agent C's
 * PR merges the resolver can be swapped over to JPA in a follow-up.
 */
@Slf4j
@Component
public class UspsDirectConnector implements CarrierConnector {

    /** Same USPS carrier code the routing table already uses — the branch
     *  between UspsDirectConnector and StampsConnector happens upstream
     *  in CarrierServiceImpl (Agent C). */
    static final String CARRIER_CODE = "USPS";

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;
    private final UspsOAuthTokenCache tokenCache;
    private final UspsPaymentAuthCache paymentAuthCache;
    private final JdbcTemplate jdbcTemplate;

    /** Field-injected because most unit tests build the connector with the
     *  primary constructor and don't exercise the system-setting fallback. */
    @Autowired(required = false)
    private SystemSettingService systemSettingService;

    /** System-setting keys for the platform OAuth credentials. Owned by
     *  Agent A's {@code /settings/system} controller; consumed here to
     *  overlay onto the connector's runtime auth. */
    public static final String SETTING_CLIENT_ID = "USPS_PLATFORM_CLIENT_ID";
    public static final String SETTING_CLIENT_SECRET = "USPS_PLATFORM_CLIENT_SECRET";

    /** Base back-off for 429 responses on rate + label endpoints. Sequence:
     *  2s, 4s, 8s + 0..500ms jitter. Independent from the OAuth cache's
     *  retry loop so both surfaces can be tuned separately if USPS
     *  differentiates their quotas. */
    static final long RETRY_BASE_MILLIS = 2_000L;
    static final int MAX_RATE_LIMIT_RETRIES = 3;

    /** USPS webhook signature header (PR-B). USPS Subscriptions-Tracking
     *  v3.2 sends {@code X-HMAC} = base64(HMAC-SHA256(timestamp + rawBody, secret)). */
    static final String WEBHOOK_HEADER_HMAC = "X-HMAC";
    /** Timestamp header USPS pairs with X-HMAC. Rebroadcasted from USPS's
     *  own push envelope; if absent we fall back to the payload's
     *  {@code eventTimestamp} field (docs are inconsistent — real fixtures
     *  in {@code src/test/resources/usps/v3/webhooks/} verify both paths). */
    static final String WEBHOOK_HEADER_TIMESTAMP = "X-USPS-Timestamp";

    /** Primary constructor — Spring picks this up. Explicit rather than
     *  Lombok so tests can build without SystemSettingService. */
    public UspsDirectConnector(CarrierProperties carrierProperties,
                                ObjectMapper objectMapper,
                                UspsOAuthTokenCache tokenCache,
                                UspsPaymentAuthCache paymentAuthCache,
                                JdbcTemplate jdbcTemplate) {
        this.carrierProperties = carrierProperties;
        this.objectMapper = objectMapper;
        this.tokenCache = tokenCache;
        this.paymentAuthCache = paymentAuthCache;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "USPS Direct";
    }

    // ================================================================
    // Availability / packaging — cheap "not supported" surfaces that
    // let /settings/carriers still render this connector before the
    // richer live catalog work lands in follow-up PRs.
    // ================================================================

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
        // The connector doesn't yet call the USPS v3 shipping-options
        // endpoint (Sprint TBD). Return the built-in USPS domestic ladder
        // so the settings UI still lists services for a verified account.
        return new ServiceAvailability(domesticServiceMatrix(), false,
                "USPS Direct — built-in service catalog (v3 shipping-options not yet wired)");
    }

    @Override
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        // No live packaging call yet — empty list until PR-C wires the
        // v3 packages endpoint. Callers fall back to the shared
        // {@code carrier_package_catalog} for USPS rows meanwhile.
        return new PackageAvailability(List.of(), false,
                "USPS Direct — built-in packaging catalog (v3 packages not yet wired)");
    }

    private static List<ServiceOffering> domesticServiceMatrix() {
        return List.of(
                new ServiceOffering("USPS_GROUND_ADVANTAGE", "USPS Ground Advantage", "DOMESTIC"),
                new ServiceOffering("PRIORITY_MAIL", "USPS Priority Mail", "DOMESTIC"),
                new ServiceOffering("PRIORITY_MAIL_EXPRESS", "USPS Priority Mail Express", "DOMESTIC")
        );
    }

    // ================================================================
    // Auth — the (clientId, clientSecret) args are IGNORED. USPS Direct
    // is a platform-wide integration; the credentials live in the
    // encrypted system_setting table (Agent A). Callers on the legacy
    // 2/3-arg overloads still get routed through the same cache.
    // ================================================================

    @Override
    public String getAccessToken(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret, null, null);
    }

    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber) {
        return getAccessToken(clientId, clientSecret, accountNumber, null);
    }

    /**
     * Mint a USPS v3 OAuth token from the platform credentials configured
     * in {@code /settings/system}. The {@code clientId} / {@code clientSecret}
     * arguments the caller passes are DELIBERATELY ignored — USPS Direct
     * runs off a single platform OAuth client, not per-tenant credentials.
     *
     * <p>When either platform credential is missing (fresh install, ops
     * hasn't pasted them yet) we return a {@code usps-direct-local-{accountNumber}}
     * placeholder so downstream boundary guards produce an actionable
     * error message instead of a null-pointer.
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        String platformClientId = readSystemSetting(SETTING_CLIENT_ID);
        String platformClientSecret = readSystemSetting(SETTING_CLIENT_SECRET);
        if (!StringUtils.hasText(platformClientId) || !StringUtils.hasText(platformClientSecret)) {
            log.debug("USPS Direct platform credentials not configured; using local fallback token.");
            return localFallback(accountNumber);
        }
        Optional<String> token = tokenCache.getToken(platformClientId, platformClientSecret, environment);
        return token.orElseGet(() -> localFallback(accountNumber));
    }

    private String readSystemSetting(String key) {
        if (systemSettingService == null) return null;
        try {
            return systemSettingService.getDecrypted(key).orElse(null);
        } catch (Exception ex) {
            log.warn("SystemSetting[{}] read failed: {}", key, ex.getMessage());
            return null;
        }
    }

    static String localFallback(String accountNumber) {
        String suffix = StringUtils.hasText(accountNumber) ? accountNumber.trim() : "unknown";
        return "usps-direct-local-" + suffix;
    }

    @Override
    public CarrierConnectionResult connect(String clientId, String clientSecret, String accountNumber) {
        // Ignore the per-account credentials — USPS Direct authenticates
        // against the platform OAuth client. The "connection" just proves
        // the platform creds are configured and USPS accepts them.
        String token = getAccessToken(null, null, accountNumber, carrierProperties.getDefaultEnvironment());
        boolean connected = StringUtils.hasText(token) && !token.contains("-local-");
        String message = connected
                ? "USPS Direct connection established using platform credentials."
                : "USPS Direct is not configured platform-wide — set USPS_PLATFORM_CLIENT_ID / "
                        + "USPS_PLATFORM_CLIENT_SECRET in /settings/system.";
        LocalDateTime tokenExpiresAt = connected
                ? LocalDateTime.now(ZoneOffset.UTC).plusHours(8)
                : null;
        return new CarrierConnectionResult(
                CARRIER_CODE,
                getCarrierName(),
                connected,
                accountNumber,
                carrierProperties.getDefaultEnvironment(),
                connected ? token : null,
                tokenExpiresAt,
                message
        );
    }

    @Override
    public boolean validateCredentials(String clientId, String clientSecret) {
        // USPS Direct is platform-authenticated — the per-account credentials
        // aren't relevant. Report true when the platform token can be
        // minted (any environment; SANDBOX first because CAT credentials
        // are more likely to be present during rollout).
        String token = getAccessToken(null, null, null, carrierProperties.getDefaultEnvironment());
        return StringUtils.hasText(token) && !token.contains("-local-");
    }

    // ================================================================
    // Rate shop — POST /prices/v3/total-rates/search (domestic only)
    // ================================================================

    @Override
    public List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        // Fail-fast boundary guards first — mirror F7 shape from
        // StampsConnector / UpsConnector so operators get an actionable
        // message instead of a cryptic 400 from USPS.
        assertRealToken(accessToken, request);
        assertRecipientCountry(request, "rate-shop");
        TenantIdentifiers tenant = requireTenantIdentifiers(request, "rate-shop");

        List<PackageDetailDTO> pkgs = request.effectivePackages();
        if (pkgs.isEmpty()) {
            log.warn("USPS Direct rate-shop skipped: request has no packages.");
            return List.of();
        }

        // v3 total-rates/search is single-piece — loop N calls, then let
        // the caller (CarrierServiceImpl fan-out) do whatever aggregation
        // it needs. Mirroring the StampsConnector approach so a MPS
        // request doesn't silently return the first-piece rate.
        List<RateOption> allRates = new ArrayList<>();
        for (PackageDetailDTO pkg : pkgs) {
            Map<String, Object> body = buildRateRequestBody(request, pkg, tenant);
            String response = callWithRetry(
                    UspsOAuthTokenCache.baseUrl(environment) + "/prices/v3/total-rates/search",
                    body, accessToken, null,
                    "rate-shop");
            if (response == null) continue;
            try {
                allRates.addAll(parseRateResponse(response));
            } catch (Exception ex) {
                log.warn("USPS Direct rate-shop response unparseable: {}", ex.getMessage());
            }
        }
        return allRates;
    }

    /**
     * Build the USPS v3 rate-search body from a ShipmentRequestDTO + one
     * package. Package-visible for the payload-shape test.
     */
    Map<String, Object> buildRateRequestBody(ShipmentRequestDTO request, PackageDetailDTO pkg,
                                              TenantIdentifiers tenant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originZIPCode", nullSafe(request.getShipperPostalCode()));
        body.put("destinationZIPCode", nullSafe(request.getRecipientPostalCode()));
        body.put("weight", toPoundsScalar(pkg));
        // Dimensions — USPS v3 wants a length/width/height triple in
        // inches. Fall back to the request-level dims if the per-package
        // block doesn't carry them.
        BigDecimal length = firstNonNull(pkg.getLength(), request.getLength());
        BigDecimal width = firstNonNull(pkg.getWidth(), request.getWidth());
        BigDecimal height = firstNonNull(pkg.getHeight(), request.getHeight());
        String dimUnit = firstNonBlank(pkg.getDimUnit(), request.getDimUnit());
        if (length != null) body.put("length", UnitConverter.toInches(length, dimUnit));
        if (width != null) body.put("width", UnitConverter.toInches(width, dimUnit));
        if (height != null) body.put("height", UnitConverter.toInches(height, dimUnit));
        body.put("mailClass", "USPS_GROUND_ADVANTAGE");
        body.put("processingCategory", "MACHINABLE");
        body.put("rateIndicator", "SP");
        body.put("destinationEntryFacilityType", "NONE");
        body.put("priceType", "COMMERCIAL");
        // Tenant CRID/MID/account attach the rate quote to the correct
        // account for pricing tiers (USPS returns commercial rates for
        // negotiated accounts based on these).
        body.put("accountNumber", tenant.accountNumber());
        body.put("CRID", tenant.crid());
        body.put("MID", tenant.mid());
        return body;
    }

    private static Double toPoundsScalar(PackageDetailDTO pkg) {
        BigDecimal weight = pkg.getWeight();
        if (weight == null) return 0.0;
        BigDecimal lbs = UnitConverter.toPounds(weight, pkg.getWeightUnit());
        return lbs.doubleValue();
    }

    /** Parse a USPS v3 total-rates/search response into RateOptions.
     *  Package-visible for the parser tests. */
    List<RateOption> parseRateResponse(String responseJson) throws Exception {
        List<RateOption> out = new ArrayList<>();
        JsonNode root = objectMapper.readTree(responseJson == null ? "{}" : responseJson);
        // USPS docs describe the shape as either `rateOptions[]` (v3) or
        // `rates[]` in early sandbox builds. Accept both.
        JsonNode rateOptions = root.path("rateOptions");
        if (rateOptions.isMissingNode() || rateOptions.isNull()) {
            rateOptions = root.path("rates");
        }
        if (!rateOptions.isArray()) return out;
        for (JsonNode opt : rateOptions) {
            // Each option carries a `totalPrice` plus a `rates[]` array
            // with one entry per component service. The `mailClass` from
            // the first entry is enough to identify the service.
            BigDecimal totalPrice = readDecimal(opt, "totalPrice");
            JsonNode rates = opt.path("rates");
            String mailClass = null;
            String description = null;
            if (rates.isArray() && rates.size() > 0) {
                mailClass = rates.get(0).path("mailClass").asText(null);
                description = rates.get(0).path("description").asText(null);
                if (totalPrice == null) totalPrice = readDecimal(rates.get(0), "price");
            }
            if (!StringUtils.hasText(mailClass)) {
                mailClass = opt.path("mailClass").asText(null);
            }
            if (!StringUtils.hasText(mailClass) || totalPrice == null) continue;
            out.add(new RateOption(
                    CARRIER_CODE,
                    mailClass,
                    StringUtils.hasText(description) ? description : mailClass,
                    totalPrice,
                    "USD",
                    null,
                    null
            ));
        }
        return out;
    }

    private static BigDecimal readDecimal(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual()) {
            try { return new BigDecimal(n.asText()); }
            catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    // ================================================================
    // Create shipment — POST /labels/v3/label
    // ================================================================

    @Override
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        assertRealToken(accessToken, request);
        assertRecipientCountry(request, "shipment");
        TenantIdentifiers tenant = requireTenantIdentifiers(request, "shipment");

        // Mint the payment-authorization token — required as a second
        // header on every label call. Missing tenant identifiers were
        // already caught above; a null return here means USPS's payment
        // endpoint rejected the tenant. Surface it as IllegalStateException
        // so CarrierServiceImpl records FAILED_CARRIER and the operator
        // sees an actionable message instead of an NPE.
        String paymentAuthToken = paymentAuthCache
                .getToken(tenant.crid(), tenant.mid(), tenant.accountNumber(),
                        accessToken, environment)
                .orElseThrow(() -> new IllegalStateException(
                        "USPS Direct payment-authorization mint failed for account "
                                + tenant.accountNumber() + " (order "
                                + request.getReferenceNumber() + "). Check EPS balance and "
                                + "that CRID / MID / account match the values registered on "
                                + "USPS's Enterprise Payment System."));

        Map<String, Object> body = buildLabelRequestBody(request, tenant);
        Map<String, String> extraHeaders = Map.of(
                "X-Payment-Authorization-Token", paymentAuthToken,
                "X-USPS-CRID", tenant.crid());

        String url = UspsOAuthTokenCache.baseUrl(environment) + "/labels/v3/label";
        String response = callWithRetry(url, body, accessToken, extraHeaders, "createShipment");
        if (response == null) {
            throw new IllegalStateException("USPS Direct label call returned no response for order "
                    + request.getReferenceNumber() + ".");
        }
        try {
            return parseLabelResponse(response, request);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "USPS Direct label response unparseable for order "
                            + request.getReferenceNumber() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Build the USPS v3 label body. Package-visible for the payload-shape
     * test — pin the {@code senderInfo.CRID}, {@code paymentInfo.accountNumber}
     * and top-level fields against USPS's contract.
     */
    Map<String, Object> buildLabelRequestBody(ShipmentRequestDTO request, TenantIdentifiers tenant) {
        Map<String, Object> body = new LinkedHashMap<>();

        // Label imaging — PDF 4x6 by default. Operators can override via
        // labelImageFormat when they wire ZPL support in a later PR.
        Map<String, Object> imageInfo = new LinkedHashMap<>();
        String imageType = firstNonBlank(request.getLabelImageFormat(), "PDF").toUpperCase(Locale.ROOT);
        imageInfo.put("imageType", imageType);
        imageInfo.put("labelType", "4X6");
        imageInfo.put("receiptOption", "NONE");
        body.put("imageInfo", imageInfo);

        body.put("toAddress", buildAddress(
                request.getRecipientAddressLine1(),
                request.getRecipientAddressLine2(),
                request.getRecipientCity(),
                request.getRecipientState(),
                request.getRecipientPostalCode(),
                request.getRecipientCountryCode(),
                request.getRecipientName(),
                request.getRecipientCompany(),
                request.getRecipientPhone()));

        body.put("fromAddress", buildAddress(
                request.getShipperAddressLine1(),
                request.getShipperAddressLine2(),
                request.getShipperCity(),
                request.getShipperState(),
                request.getShipperPostalCode(),
                request.getShipperCountryCode(),
                request.getShipperName(),
                request.getShipperCompany(),
                request.getShipperPhone()));

        Map<String, Object> senderInfo = new LinkedHashMap<>();
        senderInfo.put("CRID", tenant.crid());
        senderInfo.put("MID", tenant.mid());
        body.put("senderInfo", senderInfo);

        // Package details — pull from the first effectivePackage. PR-B or
        // PR-C will add real multi-package fan-out (USPS labels API is
        // single-piece so it needs the same loop-and-aggregate pattern
        // Stamps uses today).
        PackageDetailDTO firstPkg = request.effectivePackages().get(0);
        Map<String, Object> packageDescription = new LinkedHashMap<>();
        String mailClass = firstNonBlank(request.getServiceType(), "USPS_GROUND_ADVANTAGE");
        packageDescription.put("mailClass", mailClass);
        packageDescription.put("processingCategory", "MACHINABLE");
        packageDescription.put("rateIndicator", "SP");
        packageDescription.put("destinationEntryFacilityType", "NONE");
        packageDescription.put("weight", toPoundsScalar(firstPkg));
        BigDecimal length = firstNonNull(firstPkg.getLength(), request.getLength());
        BigDecimal width = firstNonNull(firstPkg.getWidth(), request.getWidth());
        BigDecimal height = firstNonNull(firstPkg.getHeight(), request.getHeight());
        String dimUnit = firstNonBlank(firstPkg.getDimUnit(), request.getDimUnit());
        if (length != null) packageDescription.put("length", UnitConverter.toInches(length, dimUnit));
        if (width != null) packageDescription.put("width", UnitConverter.toInches(width, dimUnit));
        if (height != null) packageDescription.put("height", UnitConverter.toInches(height, dimUnit));
        packageDescription.put("extraServices", List.of());
        body.put("packageDescription", packageDescription);

        if (StringUtils.hasText(request.getReferenceNumber())) {
            body.put("customerReference", request.getReferenceNumber());
        }

        Map<String, Object> paymentInfo = new LinkedHashMap<>();
        paymentInfo.put("paymentMethod", "USPS_ACCOUNT");
        paymentInfo.put("accountType", "EPS");
        paymentInfo.put("accountNumber", tenant.accountNumber());
        body.put("paymentInfo", paymentInfo);

        return body;
    }

    private static Map<String, Object> buildAddress(String line1, String line2, String city,
                                                     String state, String zip, String country,
                                                     String name, String company, String phone) {
        Map<String, Object> addr = new LinkedHashMap<>();
        addr.put("streetAddress", nullSafe(line1));
        if (StringUtils.hasText(line2)) addr.put("secondaryAddress", line2);
        addr.put("city", nullSafe(city));
        addr.put("state", nullSafe(state));
        addr.put("ZIPCode", nullSafe(zip));
        addr.put("countryCode", nullSafe(country));
        if (StringUtils.hasText(name)) addr.put("firstName", name);
        if (StringUtils.hasText(company)) addr.put("firm", company);
        if (StringUtils.hasText(phone)) addr.put("phone", phone);
        return addr;
    }

    /** Parse a USPS v3 label response into a ShipmentResult. Package-visible
     *  for the parser tests. */
    ShipmentResult parseLabelResponse(String responseJson, ShipmentRequestDTO request) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson == null ? "{}" : responseJson);
        JsonNode metadata = root.path("labelMetadata");
        String tracking = metadata.path("trackingNumber").asText(null);
        BigDecimal postage = readDecimal(metadata, "postage");
        // USPS returns the label as base64-encoded bytes in `labelImage`.
        String labelBase64 = root.path("labelImage").asText(null);
        String trackingUrl = StringUtils.hasText(tracking)
                ? "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + tracking
                : null;

        // Master-identity ShipmentResult. PR-B/-C will populate the
        // packages[] list from a multi-piece labels call.
        return new ShipmentResult(
                tracking,
                trackingUrl,
                null,
                labelBase64,
                postage,
                null,
                responseJson);
    }

    // ================================================================
    // Boundary guards
    // ================================================================

    private static void assertRealToken(String accessToken, ShipmentRequestDTO request) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            throw new IllegalStateException(
                    "USPS Direct is not configured platform-wide — no OAuth token available. "
                            + "Set USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET in "
                            + "/settings/system.");
        }
    }

    /** Overload used by non-shipment surfaces (tracking) that don't carry an
     *  order to name in the guard message. Same actionable text — mirrors
     *  the shipment-path guard so operators see one consistent phrasing. */
    private static void assertRealTokenForTracking(String accessToken) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            throw new IllegalStateException(
                    "USPS Direct is not configured platform-wide — no OAuth token available. "
                            + "Set USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET in "
                            + "/settings/system.");
        }
    }

    private static void assertRecipientCountry(ShipmentRequestDTO request, String context) {
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "USPS Direct " + context + " requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the recipient's country on "
                            + "the Order before " + context
                            + " — quotes without a destination silently fall to US-domestic.");
        }
    }

    /**
     * Resolve (CRID, MID, EPS account) for a tenant by looking up
     * {@code carrier_account_ref} via native SQL. We can't call the
     * entity getters because Agent C is adding those columns in a
     * parallel PR — once it merges the resolver flips to JPA.
     */
    TenantIdentifiers requireTenantIdentifiers(ShipmentRequestDTO request, String context) {
        String accountNumber = request.getAccountNumber();
        if (!StringUtils.hasText(accountNumber)) {
            throw new IllegalArgumentException(
                    "USPS Direct " + context + " requires the shipper account number (order "
                            + request.getReferenceNumber() + "). Fill it in /settings/carriers "
                            + "before " + context + " USPS.");
        }
        TenantIdentifiers tenant = lookupTenantIdentifiers(accountNumber);
        List<String> missing = new ArrayList<>();
        if (tenant == null || !StringUtils.hasText(tenant.accountNumber())) missing.add("account");
        if (tenant == null || !StringUtils.hasText(tenant.crid())) missing.add("crid");
        if (tenant == null || !StringUtils.hasText(tenant.mid())) missing.add("mid");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "USPS Direct: this tenant is missing USPS identifiers ("
                            + String.join("/", missing) + "). Fill them in /settings/carriers "
                            + "before " + context + " USPS. Order " + request.getReferenceNumber() + ".");
        }
        return tenant;
    }

    /**
     * SQL projection over {@code carrier_account_ref}. Returns null when
     * no matching row exists. Package-visible so tests can stub / spy.
     */
    TenantIdentifiers lookupTenantIdentifiers(String accountNumber) {
        // Query columns Agent A is adding in V58__usps_direct_account_columns.sql.
        // Coalesce to blank strings so a missing column ≠ SQL exception during
        // the PR-A merge window.
        String sql = """
                SELECT
                    COALESCE(usps_direct_account_number, '') AS acct,
                    COALESCE(usps_direct_crid, '')           AS crid,
                    COALESCE(usps_direct_mid, '')            AS mid
                FROM carrier_account_ref
                WHERE account_number = ?
                  AND UPPER(carrier_code) = 'USPS'
                ORDER BY updated_at DESC
                LIMIT 1
                """;
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) return null;
                String acct = rs.getString("acct");
                String crid = rs.getString("crid");
                String mid = rs.getString("mid");
                return new TenantIdentifiers(
                        StringUtils.hasText(acct) ? acct : null,
                        StringUtils.hasText(crid) ? crid : null,
                        StringUtils.hasText(mid) ? mid : null);
            }, accountNumber);
        } catch (org.springframework.dao.DataAccessException ex) {
            // V58 migration hasn't landed yet on this branch — treat as
            // "no identifiers" so the boundary guard produces an
            // actionable error rather than a SQL stack trace to the client.
            log.warn("USPS Direct tenant lookup failed (V58 probably not yet applied): {}",
                    ex.getMessage());
            return null;
        }
    }

    /**
     * The three USPS identifiers required to charge / print a label:
     * CRID (Customer Registration ID), MID (Mailer ID), and EPS account.
     * Package-visible record because payload builders + guard tests both
     * consume it.
     */
    public record TenantIdentifiers(String accountNumber, String crid, String mid) {
    }

    // ================================================================
    // HTTP call with 429 back-off
    // ================================================================

    /**
     * POST {@code body} to {@code url} with the shared Bearer auth plus
     * any extra per-endpoint headers (dual-token for labels). Returns
     * null on unrecoverable failure; retries with exponential back-off
     * on 429.
     */
    private String callWithRetry(String url, Map<String, Object> body, String accessToken,
                                  Map<String, String> extraHeaders, String context) {
        int attempt = 0;
        while (true) {
            try {
                RestClient client = HttpClients.newBuilder().baseUrl(url).build();
                RestClient.RequestBodySpec req = client.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken);
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                        req = req.header(h.getKey(), h.getValue());
                    }
                }
                return req.body(body).retrieve().body(String.class);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long delay = backoffDelayMillis(attempt);
                    log.warn("USPS Direct {} 429 (attempt {}/{}); backing off {}ms.",
                            context, attempt + 1, MAX_RATE_LIMIT_RETRIES, delay);
                    sleepQuietly(delay);
                    attempt++;
                    continue;
                }
                log.warn("USPS Direct {} rejected (HTTP {}): {}",
                        context, status, safeBody(ex.getResponseBodyAsString()));
                return null;
            } catch (Exception ex) {
                log.warn("USPS Direct {} failed: {}", context, ex.getMessage());
                return null;
            }
        }
    }

    static long backoffDelayMillis(int attempt) {
        long base = RETRY_BASE_MILLIS * (1L << attempt);
        long jitter = ThreadLocalRandom.current().nextLong(0, 500);
        return base + jitter;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeBody(String body) {
        if (body == null) return "null";
        return body.length() > 800 ? body.substring(0, 800) + "…(truncated)" : body;
    }

    // ================================================================
    // Void — PR-D. Optimistic model. USPS APIs v3 have NO synchronous
    // void / cancel endpoint (documented gotcha #9 in
    // docs/usps-direct-integration.md). Cancellations happen batch-only
    // via USPS's eVS Refund workflow: the label is marked "cancelled"
    // on the platform side immediately, then a nightly reconciliation
    // job ({@link com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService})
    // reads the eVS Refund report and either confirms (APPROVED — keep
    // VOIDED) or reverses the local status (DENIED → VOID_FAILED) once
    // USPS decides. See decision #19 in the design doc for the UX
    // rationale.
    // ================================================================

    /**
     * USPS_DIRECT void — <strong>optimistic, never touches USPS at
     * call-time</strong>. Returns {@code voided=true} with status
     * {@code VOID_PENDING_RECONCILIATION} so the existing
     * {@code VoidServiceImpl} flow flips
     * {@link com.multiship.backend.model.OrderTracking#getStatus()}
     * to {@code VOIDED} immediately and the order page shows the
     * cancellation right away.
     *
     * <p>The real accept/reject decision happens asynchronously:
     * {@code UspsDirectVoidReconciliationService.reconcile(csv)}
     * processes the eVS Refund report (uploaded by the platform admin
     * via {@code POST /api/v1/admin/usps-direct/void-reconciliation/run})
     * and either marks
     * {@code void_reconciliation_status = RECONCILED_APPROVED}
     * (leaving {@code status = VOIDED}) or flips
     * {@code status = VOID_FAILED} on {@code RECONCILED_DENIED} (label
     * scanned in transit).
     *
     * <p>Boundary guards:
     * <ul>
     *   <li>Blank / null tracking number → {@link IllegalArgumentException}
     *       — refusing to queue a nameless refund keeps the audit trail
     *       honest.</li>
     *   <li>{@code -local-} tokens are ACCEPTABLE here — no USPS call is
     *       made, so an unconfigured platform doesn't block the local
     *       flip. Differs from every other USPS_DIRECT method (all others
     *       reject {@code -local-} with {@link IllegalStateException}).</li>
     * </ul>
     *
     * <p><b>REGULATORY_REFERENCE</b>: USPS APIs v3 offer no
     * label-void / label-cancel endpoint as of 2026-09-16 (Web Tools
     * sunset 2026-01-25 removed the last synchronous path). All refunds
     * flow through the batch PS Form 3533 process on the USPS Business
     * Customer Gateway — see
     * <a href="https://about.usps.com/forms/ps3533.pdf">PS 3533
     * Application for Refund of Fees, Products and Withdrawal of Customer
     * Accounts</a>. Changing this method's semantics requires re-checking
     * that USPS has not since shipped a synchronous void endpoint.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                    String accountNumber, String senderCountryCode) {
        if (!StringUtils.hasText(trackingNumber)) {
            throw new IllegalArgumentException(
                    "USPS Direct void requires a tracking number.");
        }
        log.info("USPS Direct void queued (optimistic) for tracking {} — awaiting eVS Refund report reconciliation.",
                trackingNumber);
        return new VoidResult(
                trackingNumber,
                true,
                "VOID_PENDING_RECONCILIATION",
                "Void queued for reconciliation. USPS APIs v3 have no synchronous void endpoint; "
                        + "cancellation is confirmed by the eVS Refund report (typically 1-2 business days). "
                        + "If USPS rejects the refund (label scanned in transit), the order will flip to VOID_FAILED.",
                null);
    }

    // ================================================================
    // Tracking (PR-B) — GET /tracking/v3.2/tracking/{trackingNumber}
    // ================================================================

    /**
     * URL-only fallback for pre-PR-B callers on the 1-arg overload. Kept
     * as a soft "not found" URL result so any surface that hasn't been
     * upgraded to the 3-arg (authenticated) overload still gets a
     * clickable USPS tools link.
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber) {
        String url = StringUtils.hasText(trackingNumber)
                ? "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber
                : null;
        return new TrackingResult(trackingNumber, "UNKNOWN", url, null, null, false, null);
    }

    /**
     * Real USPS Direct v3.2 tracking call — {@code GET
     * /tracking/v3.2/tracking/{trackingNumber}?expand=DETAIL} with a
     * platform Bearer.
     *
     * <p>Guards mirror the rate-shop/label path: blank tracking number →
     * {@link IllegalArgumentException}; blank / {@code -local-} token →
     * {@link IllegalStateException} pointing at {@code /settings/system}.
     *
     * <p>404 responses (tracking number not yet visible to USPS — common
     * in the ~15min after a label creates before the first scan) are
     * handled as a SOFT "not found" TrackingResult with status
     * {@code NOT_FOUND} + the URL-only tracking link — same shape a fresh
     * label would produce from the 1-arg stub, so downstream doesn't
     * need to distinguish the two paths.
     *
     * <p>429 responses retry with the shared exponential-backoff-with-
     * jitter sequence used by rate-shop + label (2s / 4s / 8s).
     */
    @Override
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(trackingNumber)) {
            throw new IllegalArgumentException(
                    "USPS Direct tracking requires a tracking number.");
        }
        assertRealTokenForTracking(accessToken);

        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        String url = UspsOAuthTokenCache.baseUrl(environment)
                + "/tracking/v3.2/tracking/" + trackingNumber + "?expand=DETAIL";

        int attempt = 0;
        while (true) {
            try {
                String response = executeTrackingGet(url, accessToken);
                if (response == null) {
                    return notFoundTrackingResult(trackingNumber, trackingUrl);
                }
                return parseTrackingResponse(response, trackingNumber, trackingUrl);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long delay = backoffDelayMillis(attempt);
                    log.warn("USPS Direct tracking 429 (attempt {}/{}); backing off {}ms.",
                            attempt + 1, MAX_RATE_LIMIT_RETRIES, delay);
                    sleepBeforeTrackingRetry(delay);
                    attempt++;
                    continue;
                }
                if (status == 404) {
                    // USPS returns 404 for tracking numbers it hasn't seen
                    // yet — brand-new labels take up to 15 min to appear in
                    // the tracking system. Treat as soft "not found" so
                    // the caller can retry later without an exception.
                    log.debug("USPS Direct tracking 404 for {} — likely not yet visible in USPS.",
                            trackingNumber);
                    return notFoundTrackingResult(trackingNumber, trackingUrl);
                }
                log.warn("USPS Direct tracking rejected (HTTP {}): {}",
                        status, safeBody(ex.getResponseBodyAsString()));
                // Any other 4xx/5xx — return the URL-only stub rather
                // than throw, matching FedEx/UPS/Stamps convention on
                // tracking failures.
                return trackShipment(trackingNumber);
            } catch (Exception ex) {
                log.warn("USPS Direct tracking failed for {}: {}", trackingNumber, ex.getMessage());
                return trackShipment(trackingNumber);
            }
        }
    }

    /**
     * Isolated seam over the actual USPS HTTP call so tests can override
     * without a MockWebServer dependency. Default: {@code GET url} with
     * the Bearer token and {@code Accept: application/json}. Returns the
     * raw response body; throws {@link RestClientResponseException} for
     * non-2xx so the caller can inspect the status code.
     *
     * <p>Package-visible / non-final; the tracking-test subclasses this
     * to return canned bodies or throw synthetic HTTP-status exceptions.
     */
    String executeTrackingGet(String url, String accessToken) {
        RestClient client = HttpClients.newBuilder().baseUrl(url).build();
        return client.get()
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(String.class);
    }

    /**
     * Sleep between tracking retry attempts. Package-visible / non-final
     * so tests can override to skip the real 2/4/8s delays — production
     * behavior unchanged (delegates to the shared quiet-sleep). Bounded
     * separately from the rate-shop / label loop so a future test can
     * tune retries per surface without affecting production sequencing.
     */
    void sleepBeforeTrackingRetry(long millis) {
        sleepQuietly(millis);
    }

    private static TrackingResult notFoundTrackingResult(String trackingNumber, String trackingUrl) {
        return new TrackingResult(trackingNumber, "NOT_FOUND", trackingUrl, null, null, false, null);
    }

    /**
     * Parse a USPS v3.2 tracking response into a carrier-neutral
     * {@link TrackingResult}. Package-visible for the parser tests.
     *
     * <p>USPS response fields we care about:
     * <ul>
     *   <li>{@code trackingNumber} — the tracker (echoed).</li>
     *   <li>{@code statusCategory} — one of {@code Pre-Shipment},
     *       {@code In Transit}, {@code Out for Delivery},
     *       {@code Delivered}, {@code Alert}, {@code Return to Sender}.</li>
     *   <li>{@code statusSummary} — human-readable status text.</li>
     *   <li>{@code expectedDeliveryDate} + {@code expectedDeliveryTime}
     *       — used for {@code estimatedDelivery}.</li>
     *   <li>{@code trackingEvents[]} — per-scan detail (only present
     *       when {@code expand=DETAIL} was requested).</li>
     * </ul>
     */
    TrackingResult parseTrackingResponse(String responseJson, String trackingNumber, String trackingUrl)
            throws Exception {
        JsonNode root = objectMapper.readTree(responseJson == null ? "{}" : responseJson);
        String statusCategory = root.path("statusCategory").asText(null);
        String statusSummary = root.path("statusSummary").asText(null);
        String status = mapStatusCategory(statusCategory, statusSummary);
        boolean delivered = "Delivered".equalsIgnoreCase(statusCategory);

        LocalDateTime estimatedDelivery = joinExpectedDelivery(
                root.path("expectedDeliveryDate").asText(null),
                root.path("expectedDeliveryTime").asText(null));

        List<TrackingEvent> events = parseTrackingEvents(root.path("trackingEvents"));
        String currentLocation = null;
        // Latest event = last entry in USPS's oldest → newest ordering.
        // That's the "current location" reported at the top of the timeline.
        if (!events.isEmpty()) {
            currentLocation = events.get(events.size() - 1).location();
        }

        return new TrackingResult(trackingNumber, status, trackingUrl, currentLocation,
                estimatedDelivery, delivered, responseJson, events);
    }

    /**
     * Map USPS's human-readable {@code statusCategory} to a canonical
     * shorter status token that matches what the other connectors emit.
     * When USPS gives us nothing, fall back to the summary text (or
     * "UNKNOWN" as a last resort).
     */
    static String mapStatusCategory(String statusCategory, String statusSummary) {
        if (!StringUtils.hasText(statusCategory)) {
            return StringUtils.hasText(statusSummary) ? statusSummary : "UNKNOWN";
        }
        String normalized = statusCategory.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PRE-SHIPMENT", "PRE_SHIPMENT" -> "PRE_SHIPMENT";
            case "IN TRANSIT", "IN_TRANSIT" -> "IN_TRANSIT";
            case "OUT FOR DELIVERY", "OUT_FOR_DELIVERY" -> "OUT_FOR_DELIVERY";
            case "DELIVERED" -> "DELIVERED";
            case "ALERT" -> "ALERT";
            case "RETURN TO SENDER", "RETURN_TO_SENDER" -> "RETURN_TO_SENDER";
            default -> statusCategory;
        };
    }

    /**
     * Parse USPS's {@code trackingEvents[]} into carrier-neutral
     * {@link TrackingEvent}s. USPS lists events oldest → newest natively,
     * so no reversal needed here — matches SWSIM's convention.
     * Package-visible so parser tests can assert without HTTP.
     */
    List<TrackingEvent> parseTrackingEvents(JsonNode trackingEvents) {
        if (trackingEvents == null || !trackingEvents.isArray() || trackingEvents.isEmpty()) {
            return List.of();
        }
        List<TrackingEvent> out = new ArrayList<>();
        for (JsonNode ev : trackingEvents) {
            LocalDateTime ts = joinEventTimestamp(
                    ev.path("eventDate").asText(null),
                    ev.path("eventTime").asText(null));
            String description = ev.path("eventDescription").asText(
                    ev.path("eventType").asText(""));
            String status = ev.path("eventType").asText(null);
            String location = buildUspsLocation(
                    ev.path("eventCity").asText(null),
                    ev.path("eventState").asText(null),
                    ev.path("eventCountry").asText(null),
                    ev.path("eventZIP").asText(null));
            out.add(new TrackingEvent(ts, status, description, location));
        }
        return List.copyOf(out);
    }

    /** Build "City, ST US" style location from USPS event coordinates.
     *  Nulls / blanks are elided; entirely-blank input returns null so
     *  the UI can hide the location tag. Package-visible for tests. */
    static String buildUspsLocation(String city, String state, String country, String zip) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(city)) sb.append(city.trim());
        if (StringUtils.hasText(state)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(state.trim());
        }
        if (StringUtils.hasText(zip)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(zip.trim());
        }
        if (StringUtils.hasText(country)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(country.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * USPS emits {@code eventDate} = ISO local date, {@code eventTime} =
     * "HH:mm:ss" (24-hour). Combine into a {@link LocalDateTime}; missing
     * time defaults to midnight so we don't drop a whole event when USPS
     * omits the clock (they do for the initial "Shipping Label Created"
     * scan). Malformed date returns null.
     * Package-visible for tests.
     */
    static LocalDateTime joinEventTimestamp(String date, String time) {
        if (!StringUtils.hasText(date)) return null;
        try {
            LocalDate d = LocalDate.parse(date.trim());
            if (!StringUtils.hasText(time)) {
                return d.atStartOfDay();
            }
            try {
                return d.atTime(LocalTime.parse(time.trim()));
            } catch (DateTimeParseException ignore) {
                // Garbled time = date at midnight, matches UPS convention.
                return d.atStartOfDay();
            }
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Combine {@code expectedDeliveryDate} + {@code expectedDeliveryTime}
     *  into an ETA. USPS may return either or both. Missing date → null;
     *  missing time defaults to 17:00 (traditional USPS end-of-day). */
    static LocalDateTime joinExpectedDelivery(String date, String time) {
        if (!StringUtils.hasText(date)) return null;
        try {
            LocalDate d = LocalDate.parse(date.trim());
            if (!StringUtils.hasText(time)) {
                return d.atTime(17, 0);
            }
            try {
                return d.atTime(LocalTime.parse(time.trim()));
            } catch (DateTimeParseException ignore) {
                return d.atTime(17, 0);
            }
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    // ================================================================
    // Webhook parse + verify (PR-B)
    // ================================================================

    /**
     * Verify a USPS Subscriptions-Tracking v3.2 webhook signature.
     *
     * <p>USPS signs each push notification with
     * {@code X-HMAC = base64(HMAC-SHA256(timestamp + rawBody, secret))}.
     * The timestamp lives either in the {@code X-USPS-Timestamp} header
     * (preferred; matches USPS docs) or, as a documented fallback, in
     * the payload's {@code eventTimestamp} field — real fixtures cover
     * both forms.
     *
     * <p>Returns {@code false} — never throws — when the signature,
     * secret, header, or raw payload is missing. The
     * {@link com.multiship.backend.service.WebhookService} then treats
     * the row as unverified (401 back to USPS + audit trail).
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload,
                                           Map<String, String> headers,
                                           String secret) {
        if (!StringUtils.hasText(secret)) return false;
        if (rawPayload == null) return false;
        String provided = pickWebhookHeader(headers, WEBHOOK_HEADER_HMAC);
        if (!StringUtils.hasText(provided)) return false;

        String timestamp = pickWebhookHeader(headers, WEBHOOK_HEADER_TIMESTAMP);
        if (!StringUtils.hasText(timestamp)) {
            // Fall back to the payload's eventTimestamp — USPS docs are
            // inconsistent about which surface carries the timestamp, so
            // we accept either. If neither is present the signature can't
            // reproduce and we return false rather than skip the timestamp
            // (skipping would let a replayed payload verify).
            timestamp = extractPayloadTimestamp(rawPayload);
            if (!StringUtils.hasText(timestamp)) return false;
        }

        String expected = hmacSha256Base64(timestamp + rawPayload, secret);
        if (expected == null) return false;
        // Constant-time compare per OWASP. MessageDigest.isEqual is the
        // JDK's constant-time-safe byte-array comparator.
        return MessageDigest.isEqual(
                provided.trim().getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse a USPS push-notification payload into a carrier-neutral
     * {@link TrackingWebhookEvent}. Payload shape (documented + observed):
     * <pre>
     * {
     *   "trackingNumber": "...",
     *   "mailClass": "USPS_GROUND_ADVANTAGE",
     *   "eventType": "IN_TRANSIT",
     *   "eventTimestamp": "2026-09-15T13:45:00Z",
     *   "eventLocation": {"city": "...", "state": "...", "country": "US"},
     *   "eventDescription": "Arrived at USPS facility",
     *   "MID": "..."
     * }
     * </pre>
     *
     * <p>Missing optional fields → nulls (never throws). Malformed JSON →
     * WARN log + null so {@code WebhookServiceImpl} skips the delivery
     * (matches StampsConnector convention).
     */
    @Override
    public TrackingWebhookEvent parseWebhookEvent(String rawPayload,
                                                   Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(Optional.ofNullable(rawPayload).orElse("{}"));
            String tracking = root.path("trackingNumber").asText(null);
            if (!StringUtils.hasText(tracking)) {
                log.warn("USPS webhook parse: payload missing trackingNumber; skipping.");
                return null;
            }
            String eventType = root.path("eventType").asText(null);
            String description = root.path("eventDescription").asText(null);
            LocalDateTime occurred = parseWebhookTimestamp(root.path("eventTimestamp").asText(null));

            JsonNode loc = root.path("eventLocation");
            String location = null;
            if (!loc.isMissingNode() && !loc.isNull()) {
                location = buildUspsLocation(
                        loc.path("city").asText(null),
                        loc.path("state").asText(null),
                        loc.path("country").asText(null),
                        loc.path("zip").asText(null));
            }

            boolean delivered = "DELIVERED".equalsIgnoreCase(eventType)
                    || "DELIVERED".equalsIgnoreCase(description);
            return new TrackingWebhookEvent(
                    tracking,
                    eventType,
                    eventType,
                    occurred,
                    location,
                    delivered,
                    description == null ? "" : description);
        } catch (Exception ex) {
            // Try to salvage the tracking number for the log hint so ops
            // can correlate a bad payload with a specific delivery event.
            String hint = extractTrackingHint(rawPayload);
            log.warn("USPS webhook parse failed{}: {}",
                    hint == null ? "" : " (trackingNumber=" + hint + ")",
                    ex.getMessage());
            return null;
        }
    }

    private static String pickWebhookHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Fallback for signature verification when USPS didn't send the
     * timestamp header. Pull the {@code eventTimestamp} directly from
     * the raw payload without re-parsing all of it — signature verify
     * must operate on the RAW bytes, so we do a minimal string scan.
     * Returns null when the field isn't present.
     */
    static String extractPayloadTimestamp(String rawPayload) {
        if (!StringUtils.hasText(rawPayload)) return null;
        // Grep for "eventTimestamp":"..." — Jackson-safe: rawPayload will
        // never legitimately contain that key inside a nested string
        // literal because USPS's Subscriptions envelope is flat.
        int idx = rawPayload.indexOf("\"eventTimestamp\"");
        if (idx < 0) return null;
        int quoteStart = rawPayload.indexOf('"', idx + "\"eventTimestamp\"".length() + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = rawPayload.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return rawPayload.substring(quoteStart + 1, quoteEnd);
    }

    /** Salvage the tracking number for the WARN log hint even when the
     *  overall parse throws. Best-effort — returns null when we can't
     *  spot it. Same approach as {@link #extractPayloadTimestamp}. */
    static String extractTrackingHint(String rawPayload) {
        if (!StringUtils.hasText(rawPayload)) return null;
        int idx = rawPayload.indexOf("\"trackingNumber\"");
        if (idx < 0) return null;
        int quoteStart = rawPayload.indexOf('"', idx + "\"trackingNumber\"".length() + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = rawPayload.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return rawPayload.substring(quoteStart + 1, quoteEnd);
    }

    /** Parse USPS's {@code eventTimestamp} — usually ISO-8601 with a
     *  {@code Z} suffix, occasionally with a numeric offset. Returns null
     *  on any parse failure so the caller keeps the row (with a null
     *  timestamp) rather than dropping the whole event. */
    static LocalDateTime parseWebhookTimestamp(String value) {
        if (!StringUtils.hasText(value)) return null;
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (DateTimeParseException ignore) {
            // Fall through — try a local-only shape.
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ignore) {
            return null;
        }
    }

    /**
     * Compute {@code base64(HMAC-SHA256(message, secret))}. Package-visible
     * so the webhook HMAC test can assert the exact digest shape a real
     * USPS payload would produce. Returns null when the crypto init
     * fails — the caller then returns {@code false} from the verify
     * so we fail closed rather than accept an unsigned delivery.
     */
    static String hmacSha256Base64(String message, String secret) {
        if (message == null || secret == null || secret.isEmpty()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            log.warn("USPS webhook HMAC init failed: {}", ex.getMessage());
            return null;
        }
    }

    // ================================================================
    // Configuration — cosmetic; makes /settings/carriers list this
    // connector alongside the others.
    // ================================================================

    @Override
    public CarrierConfiguration getConfiguration() {
        return new CarrierConfiguration(
                CARRIER_CODE,
                getCarrierName(),
                UspsOAuthTokenCache.PROD_HOST,
                UspsOAuthTokenCache.PROD_HOST + "/oauth2/v3/token",
                "v3",
                UspsOAuthTokenCache.SANDBOX_HOST,
                "/labels/v3/label",
                "/tracking/v3.2/tracking",
                "/oauth2/v3/token",
                "https://developer.usps.com/logo.svg",
                "https://developer.usps.com/apis",
                "Register a platform OAuth client at developer.usps.com and paste the "
                        + "Client ID / Secret into /settings/system.",
                "USPS_GROUND_ADVANTAGE",
                "USPS_PACKAGE",
                "PDF",
                carrierProperties.getDefaultEnvironment(),
                true
        );
    }

    // ================================================================
    // Small string helpers
    // ================================================================

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }
}
