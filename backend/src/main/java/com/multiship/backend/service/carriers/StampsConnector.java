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

    /** Stamps.com SWSIM requires the IntegrationID to be a real GUID.
     *  Canonical shape: {@code 8-4-4-4-12} hex with hyphens. */
    private static final java.util.regex.Pattern GUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** 32-hex-no-hyphens form Stamps.com's dev portal occasionally emits (e.g. via
     *  its .NET SDK code samples). We insert the hyphens ourselves so the operator
     *  doesn't have to reshape the value before pasting. */
    private static final java.util.regex.Pattern GUID_NO_HYPHENS = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{32}$");
    /** Braced-GUID {@code {01234567-...}} form the Windows/registry-style portal
     *  copy button emits — very common paste variant. */
    private static final java.util.regex.Pattern BRACED_GUID_WRAPPER = java.util.regex.Pattern.compile(
            "^\\{(.*)\\}$");
    /** URN-form {@code urn:uuid:01234567-...} that IETF-style tooling produces. */
    private static final String URN_UUID_PREFIX = "urn:uuid:";

    /**
     * Normalise the IntegrationID the operator pasted into a canonical GUID.
     * Accepts + reshapes these variants (all seen in the wild pasting from the
     * Stamps.com developer portal or third-party SDK docs):
     * <ul>
     *   <li>canonical {@code 01234567-89ab-cdef-0123-456789abcdef} — pass-through</li>
     *   <li>braced {@code {01234567-89ab-cdef-0123-456789abcdef}} — strip braces</li>
     *   <li>URN {@code urn:uuid:01234567-...} — strip prefix</li>
     *   <li>no-hyphens {@code 0123456789abcdef0123456789abcdef} — insert hyphens</li>
     * </ul>
     * Returns {@code null} when the value can't be reshaped into a canonical GUID;
     * callers then surface an actionable error. All matches are case-insensitive
     * because the hex range in {@link #GUID_PATTERN} allows both cases.
     */
    static String normaliseIntegrationId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Strip URN prefix (case-insensitive) so callers pasting from IETF docs work.
        if (s.length() > URN_UUID_PREFIX.length()
                && s.substring(0, URN_UUID_PREFIX.length()).equalsIgnoreCase(URN_UUID_PREFIX)) {
            s = s.substring(URN_UUID_PREFIX.length()).trim();
        }
        // Strip braces {…}
        java.util.regex.Matcher braced = BRACED_GUID_WRAPPER.matcher(s);
        if (braced.matches()) {
            s = braced.group(1).trim();
        }
        // Insert hyphens for the 32-hex form.
        if (GUID_NO_HYPHENS.matcher(s).matches()) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                    + s.substring(12, 16) + "-" + s.substring(16, 20) + "-"
                    + s.substring(20);
        }
        return GUID_PATTERN.matcher(s).matches() ? s : null;
    }

    private final CarrierProperties carrierProperties;
    private final ObjectMapper objectMapper;

    /** Field injection (not constructor) so the many unit tests that build
     *  this connector directly with the two-arg constructor keep compiling;
     *  those tests don't exercise listPackages(). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.CarrierPackageCatalogRepository packageCatalogRepository;

    /** Field injection — same pattern as {@link #packageCatalogRepository}.
     *  Required by the SERA void + reprint paths to resolve a
     *  {@code label_id} from a tracking number (SERA voids by label_id,
     *  not by tracking, so the DB lookup bridges the two). Tests that
     *  drive SERA branches directly can set the field via reflection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.LabelPackageRepository labelPackageRepository;

    /** Field injection — SERA 3-legged OAuth token refresh. Absent in unit
     *  tests that drive SWSIM paths; when absent, {@code getAccessTokenSera}
     *  falls back to the legacy {@code client_credentials} attempt (which
     *  fails cleanly with the same LAST_AUTH_DETAIL surface). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StampsSeraOAuthService seraOAuthService;

    /** PR-T3 — SERA rate-shop component (sibling bean, not inline, to
     *  keep this class under the review-threshold line count). Null in
     *  unit tests that drive the SWSIM path or construct the connector
     *  directly; the {@link #getRates} dispatcher short-circuits to the
     *  SWSIM branch when this field is null even if the flavour is SERA. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StampsSeraRates stampsSeraRates;

    /**
     * PR-S4 (S-B2) — field injection of the shared fallback-alerts ring
     * buffer. Non-null in Spring runtime so a SERA auth failure surfaces
     * on the ops dashboard alongside USPS_DIRECT alerts. Null in unit
     * tests that construct the connector directly; the recorder call is
     * null-guarded so those tests keep passing without wiring the bean.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.carriers.usps.queue.UspsFallbackAlertService fallbackAlertService;

    /** Per-thread reason the last getAccessToken fell back — read by verify. */
    private static final ThreadLocal<String> LAST_AUTH_DETAIL = new ThreadLocal<>();

    /**
     * Per-thread SERA refresh_token — set by the caller (usually
     * AccountRefServiceImpl.verifyAccount, or a label/shipment path
     * loading the account row) BEFORE invoking {@code getAccessToken},
     * so the SERA branch uses {@code grant_type=refresh_token} instead
     * of the doomed {@code client_credentials} attempt.
     *
     * <p>Cleared after each call to prevent bleed between requests on
     * the same thread. Mirrors the LAST_AUTH_DETAIL pattern.
     */
    private static final ThreadLocal<String> SERA_REFRESH_TOKEN = new ThreadLocal<>();

    /**
     * Per-thread flag: the SERA branch discovered that no refresh token
     * is available for the account (needs the operator to complete the
     * browser authorize flow). AccountRefServiceImpl reads this to
     * surface {@code needsAuthorization: true} in the verify response
     * so the FE can open the popup.
     */
    private static final ThreadLocal<Boolean> SERA_NEEDS_AUTHORIZATION = new ThreadLocal<>();

    /**
     * Push a refresh_token onto the thread-local before a subsequent
     * {@code getAccessToken} call. Cleared on the connector's exit
     * path — callers should still {@link #consumeSeraNeedsAuthorization()}
     * on the way out to catch any residual state.
     */
    public static void pushSeraRefreshToken(String refreshToken) {
        if (StringUtils.hasText(refreshToken)) {
            SERA_REFRESH_TOKEN.set(refreshToken);
        } else {
            SERA_REFRESH_TOKEN.remove();
        }
        SERA_NEEDS_AUTHORIZATION.remove();
    }

    /**
     * True when the last {@code getAccessToken} call on the SERA branch
     * fell back because no refresh_token was available on the thread —
     * consumed once, then cleared.
     */
    public static boolean consumeSeraNeedsAuthorization() {
        Boolean v = SERA_NEEDS_AUTHORIZATION.get();
        SERA_NEEDS_AUTHORIZATION.remove();
        return Boolean.TRUE.equals(v);
    }

    @Override
    public String consumeAuthFailureDetail() {
        String detail = LAST_AUTH_DETAIL.get();
        LAST_AUTH_DETAIL.remove();
        return detail;
    }

    @Override
    public String getCarrierCode() {
        return CARRIER_CODE;
    }

    @Override
    public String getCarrierName() {
        return "USPS via Stamps.com";
    }

    @Override
    public ServiceAvailability listServices(String originCountry, String accessToken, String environment) {
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
            List<ServiceOffering> live = fetchLiveServices(originCountry, accessToken, environment);
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
    private List<ServiceOffering> fetchLiveServices(String originCountry, String accessToken, String environment) throws Exception {
        String baseUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        String url = baseUrl + "/shipments/v3/options/search";
        String response = HttpClients.newBuilder().baseUrl(url).build()
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
    public PackageAvailability listPackages(String originCountry, String accessToken, String environment) {
        String o = originCountry == null ? "US" : originCountry.trim().toUpperCase(Locale.ROOT);
        // USPS Flat Rate packaging is US-domestic only; from any other origin
        // USPS offers nothing (US-only carrier).
        if (!"US".equals(o) && !"PR".equals(o)) {
            return new PackageAvailability(List.of(), false, "USPS published packaging (US-only carrier)");
        }
        // Catalogue now lives in carrier_package_catalog (V32) instead of being
        // hardcoded here.
        List<PackageOffering> pkgs = CarrierPackageCatalogSupport.toOfferings(
                packageCatalogRepository.findByCarrierCodeIgnoreCaseAndActiveTrueOrderBySortOrderAsc(CARRIER_CODE),
                o);
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
        // Bug fix: previously called the 2-arg getAccessToken(clientId, clientSecret)
        // which forwarded accountNumber=null to the 4-arg version, which then threw
        // CarrierConnectionException("Stamps.com verification needs the account number
        // as the SWSIM Username."). Effect: `POST /carriers/connect` for USPS
        // always 500'd regardless of what the caller supplied — the accountNumber
        // sitting in the argument list was silently discarded. Now passing it
        // through to the 4-arg overload where SWSIM AuthenticateUser needs it.
        // Environment left null so the default-environment property drives the
        // sandbox-vs-prod routing (matches the pre-fix behaviour for the
        // environment field on CarrierConnectionResult at line 218).
        String accessToken = getAccessToken(clientId, clientSecret, accountNumber, null);
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
     * Stamps.com / Endicia has TWO wire APIs — the connector picks between
     * them via {@code carrier.stamps.api-flavor}:
     * <ul>
     *   <li><b>SWSIM (SOAP)</b> — legacy, still what most existing accounts
     *       use. Credential check calls {@code AuthenticateUser} on the SWSIM
     *       endpoint with:
     *       <ul>
     *         <li>{@code IntegrationID} = "Client ID" from the developer
     *             portal (must be a GUID; SWSIM's XML schema enforces this).</li>
     *         <li>{@code Username} = Stamps.com account number.</li>
     *         <li>{@code Password} = "Client Secret" from the developer portal.</li>
     *       </ul>
     *       SWSIM returns an {@code Authenticator} GUID that persists for a
     *       session and stands in as our "access token".</li>
     *   <li><b>SERA (OAuth 2.0 REST)</b> — Auctane's newer API at
     *       {@code signin.stampsendicia.com} / {@code api.stampsendicia.com}.
     *       {@code client_id} is an opaque string, NOT a GUID; the GUID
     *       validator is skipped on this path. We POST a client-credentials
     *       grant to the token endpoint and return the bearer token. SERA's
     *       public docs highlight the authorization-code flow, but the token
     *       endpoint is a plain OAuth 2.0 server — if the account has been
     *       provisioned for machine-to-machine, {@code client_credentials}
     *       works; if not, the server returns a proper
     *       {@code unsupported_grant_type} error that the operator sees on
     *       the Carriers page (much better UX than a hardcoded pre-flight
     *       rejection).</li>
     * </ul>
     *
     * <p>On failure both flavors fall back to a {@code -local-*} token so the
     * caller's "-local-" detection surfaces credential rejection uniformly.
     *
     * <p>Environment routing: SANDBOX hits {@code swsim.testing.stamps.com}
     * (SWSIM) or {@code signin.testing.stampsendicia.com} (SERA); everything
     * else hits the production hosts.
     */
    @Override
    public String getAccessToken(String clientId, String clientSecret, String accountNumber, String environment) {
        if (isSeraFlavor()) {
            return getAccessTokenSera(clientId, clientSecret, environment);
        }
        return getAccessTokenSwsim(clientId, clientSecret, accountNumber, environment);
    }

    /**
     * DB-backed override of {@link CarrierProperties.Stamps#getApiFlavor()}.
     * The admin picks SWSIM vs SERA on /settings/system; the setting is
     * stored under {@code carrier.stamps.api-flavor} and overrides the
     * property value app-wide. Optional injection so unit tests that
     * construct the connector directly keep working — with the service
     * absent, the property-based flavor is authoritative.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.SystemSettingService systemSettingService;

    /** Setting key mirrored from {@link CarrierProperties.Stamps#getApiFlavor()}. */
    public static final String FLAVOR_SETTING_KEY = "carrier.stamps.api-flavor";

    /** @return true when the effective flavor (DB override → property fallback)
     *  is SERA (case-insensitive). */
    private boolean isSeraFlavor() {
        String flavor = null;
        if (systemSettingService != null) {
            try {
                flavor = systemSettingService.getDecrypted(FLAVOR_SETTING_KEY).orElse(null);
            } catch (Exception ex) {
                // System setting unavailable (e.g. encryption key absent) —
                // fall back to the property. Never let a settings-DB hiccup
                // break shipping calls.
                log.debug("SystemSetting[{}] read failed; using property fallback: {}",
                        FLAVOR_SETTING_KEY, ex.getMessage());
            }
        }
        if (!StringUtils.hasText(flavor)) {
            flavor = carrierProperties.getStamps().getApiFlavor();
        }
        return flavor != null && "SERA".equalsIgnoreCase(flavor.trim());
    }

    /**
     * SERA OAuth 2.0 path — a plain {@code client_credentials} grant against
     * {@code signin.stampsendicia.com/oauth/token} (or the sandbox host on
     * SANDBOX). {@code client_id} is opaque; no GUID validation. accountNumber
     * is not part of the OAuth exchange (SERA scopes token authority via the
     * client_id itself), so a blank accountNumber is allowed here — unlike
     * SWSIM which needs it as the Username. Any 2xx-with-access_token is a
     * successful verification; anything else falls back to a -local-* token
     * with the server's error surfaced via LAST_AUTH_DETAIL.
     */
    String getAccessTokenSera(String clientId, String clientSecret, String environment) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            LAST_AUTH_DETAIL.set("SERA requires a non-blank Client ID and Client Secret. "
                    + "Copy the values from your Stamps.com / Endicia developer portal.");
            return buildFallbackToken(clientId, clientSecret);
        }

        // 3-legged OAuth path: when the caller pushed a refresh_token onto
        // SERA_REFRESH_TOKEN, use the refresh_token grant — this is the
        // grant Stamps.com developer accounts are actually provisioned for.
        // The prior client_credentials fallback below is kept as a
        // last-resort attempt for accounts that DID get provisioned for
        // machine-to-machine (rare), but the primary path is refresh.
        String refresh = SERA_REFRESH_TOKEN.get();
        SERA_REFRESH_TOKEN.remove();  // one-shot; caller re-pushes for the next call
        if (StringUtils.hasText(refresh) && seraOAuthService != null) {
            StampsSeraOAuthService.TokenExchangeResult r =
                    seraOAuthService.refreshToken(refresh, clientId.trim(), clientSecret, environment);
            if (r.success()) {
                LAST_AUTH_DETAIL.remove();
                return r.accessToken();
            }
            LAST_AUTH_DETAIL.set("Stamps.com SERA refresh_token exchange failed: " + r.errorMessage()
                    + ". The operator may need to re-authorize the account.");
            log.warn("Stamps SERA refresh_token grant failed: {}", r.errorMessage());
            // PR-S4 (S-B2) — surface this on the /dashboard/fallback-alerts
            // ring buffer so ops see it without tailing the log file.
            // WARN log stays as the durable record; alert is transient
            // telemetry. Null-guard for pure-Mockito test paths that
            // construct the connector without wiring the alert bean.
            if (fallbackAlertService != null) {
                fallbackAlertService.record(null, null, null,
                        "STAMPS_SERA_REFRESH_FAILED",
                        "Stamps.com SERA refresh_token grant failed: " + r.errorMessage()
                                + " — operator must re-authorize the account.");
            }
            // Flag needs-authorization so the FE can prompt the operator
            // to click "Reconnect" and complete the browser authorize flow
            // again (typical cause: refresh_token revoked by Stamps.com or
            // expired past the account's inactivity window).
            SERA_NEEDS_AUTHORIZATION.set(true);
            return buildFallbackToken(clientId, clientSecret);
        }

        // No refresh_token on the thread → the account hasn't completed the
        // browser authorize flow yet. Signal needs-authorization so verify
        // returns { needsAuthorization: true, authorizeUrl } and the FE
        // opens the SERA authorize popup instead of showing a rejection.
        if (seraOAuthService != null) {
            SERA_NEEDS_AUTHORIZATION.set(true);
            LAST_AUTH_DETAIL.set("Stamps.com SERA accounts require a one-time browser authorization. "
                    + "Click 'Authorize with Stamps.com' to complete the OAuth consent flow — "
                    + "we'll store a refresh token and mint access tokens on demand from that point.");
            return buildFallbackToken(clientId, clientSecret);
        }

        // Legacy fallback: unit tests that construct the connector directly
        // don't wire seraOAuthService; keep the old client_credentials call
        // so those tests continue to exercise the token endpoint (they
        // expect the unsupported_grant_type rejection surface).
        CarrierProperties.Stamps cfg = carrierProperties.getStamps();
        String tokenUrl = isSandbox(environment) ? cfg.getSeraSandboxAuthUrl() : cfg.getSeraAuthUrl();
        if (!StringUtils.hasText(tokenUrl)) {
            LAST_AUTH_DETAIL.set("SERA is enabled (carrier.stamps.api-flavor=SERA) but the "
                    + (isSandbox(environment) ? "sandbox" : "production")
                    + " token URL is not configured. Set carrier.stamps.sera-"
                    + (isSandbox(environment) ? "sandbox-" : "") + "auth-url.");
            log.warn("Stamps SERA: token URL is blank on {} — cannot verify credentials.",
                    isSandbox(environment) ? "SANDBOX" : "PRODUCTION");
            return buildFallbackToken(clientId, clientSecret);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId.trim());
        form.add("client_secret", clientSecret);
        try {
            String response = HttpClients.newBuilder().baseUrl(tokenUrl).build()
                    .post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode json = objectMapper.readTree(Optional.ofNullable(response).orElse("{}"));
            String accessToken = json.path("access_token").asText(null);
            if (StringUtils.hasText(accessToken)) {
                LAST_AUTH_DETAIL.remove();
                return accessToken;
            }
            String err = json.path("error_description").asText(json.path("error").asText("no error field"));
            LAST_AUTH_DETAIL.set("Stamps.com SERA returned no access_token: " + err);
            log.warn("Stamps SERA {} returned no access_token: {}", tokenUrl, safeHead(response));
            return buildFallbackToken(clientId, clientSecret);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            String errMsg = extractOAuthError(body);
            log.warn("Stamps SERA token endpoint {} rejected (HTTP {}): {} · body head: {}",
                    tokenUrl, status, errMsg, safeHead(body));
            LAST_AUTH_DETAIL.set(StringUtils.hasText(errMsg)
                    ? "Stamps.com SERA rejected the credentials (HTTP " + status + "): " + errMsg
                    : "Stamps.com SERA returned HTTP " + status + ".");
            return buildFallbackToken(clientId, clientSecret);
        } catch (Exception ex) {
            log.warn("Stamps SERA token call to {} failed; using local fallback token. Reason: {}",
                    tokenUrl, ex.getMessage());
            LAST_AUTH_DETAIL.set("could not reach the Stamps.com SERA token endpoint (" + ex.getMessage() + ")");
            return buildFallbackToken(clientId, clientSecret);
        }
    }

    /**
     * Extract a human-readable message from an OAuth 2.0 error body.
     * Standard shape (RFC 6749 §5.2) is a JSON object with
     * {@code error} and optional {@code error_description}; some servers
     * return HTML on infrastructure failures. Never throws — returns null
     * when the body is blank or not JSON.
     */
    private String extractOAuthError(String body) {
        if (!StringUtils.hasText(body)) return null;
        try {
            JsonNode j = objectMapper.readTree(body);
            String desc = j.path("error_description").asText(null);
            if (StringUtils.hasText(desc)) return desc;
            String err = j.path("error").asText(null);
            if (StringUtils.hasText(err)) return err;
        } catch (Exception parseIgnored) {
            // Fall through to raw-body truncation.
        }
        return safeHead(body);
    }

    /**
     * SWSIM SOAP path — historical behaviour. Requires accountNumber (used as
     * SWSIM Username) and a GUID IntegrationID (Stamps.com's XML schema
     * enforces the {@code 8-4-4-4-12} hex shape at request-parse time). The
     * GUID validator here catches non-GUID input up-front with an actionable
     * message instead of firing a doomed SOAP call.
     */
    String getAccessTokenSwsim(String clientId, String clientSecret, String accountNumber, String environment) {
        CarrierProperties.Stamps cfg = carrierProperties.getStamps();
        String swsimUrl = isSandbox(environment) ? cfg.getSandboxAuthUrl() : cfg.getAuthUrl();

        if (!StringUtils.hasText(accountNumber)) {
            throw new CarrierConnectionException(
                    "Stamps.com verification needs the account number as the SWSIM Username. "
                            + "Enter the Stamps.com account number in the Account number field. "
                            + "(If your account is on the newer SERA REST API, set "
                            + "carrier.stamps.api-flavor=SERA — SERA doesn't need an account number.)");
        }

        // Stamps.com rejects a non-GUID IntegrationID at XML-schema validation
        // (HTTP 500 "value is invalid according to its datatype 'guid'") before
        // it ever checks the credentials. Catch that here with an actionable
        // message instead of firing a request we know the schema will bounce.
        // Also normalise common paste variants (braces, urn:uuid:, no-hyphens)
        // so operators don't fail auth over registry-style copy-paste — the
        // Stamps.com developer portal's "copy" button emits the braced form on
        // Windows, which the strict pattern used to reject.
        String normalisedGuid = normaliseIntegrationId(clientId);
        if (normalisedGuid == null) {
            LAST_AUTH_DETAIL.set("the Stamps.com SWSIM Client ID (IntegrationID) must be a GUID like "
                    + "\"01234567-89ab-cdef-0123-456789abcdef\" (braces {...}, urn:uuid: prefix, "
                    + "or 32-hex-no-hyphens are also accepted and auto-normalised). The value entered "
                    + "isn't recognisable as a GUID — copy the IntegrationID from your Stamps.com "
                    + "developer portal, OR if your account is on the newer SERA REST API set "
                    + "carrier.stamps.api-flavor=SERA (SERA client_ids are opaque strings, not GUIDs).");
            log.warn("Stamps SWSIM: IntegrationID '{}' is not a GUID after normalisation; "
                    + "skipping call and returning fallback token.", clientId);
            return buildFallbackToken(clientId, clientSecret);
        }
        if (!normalisedGuid.equals(clientId == null ? null : clientId.trim())) {
            log.info("Stamps SWSIM: IntegrationID normalised from '{}' → '{}' (strip braces / "
                    + "urn:uuid: / insert hyphens).", clientId, normalisedGuid);
        }

        String soap = buildAuthenticateUserEnvelope(normalisedGuid, accountNumber.trim(), clientSecret);

        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/AuthenticateUser\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);

            String authenticator = extractAuthenticator(response);
            if (StringUtils.hasText(authenticator)) {
                LAST_AUTH_DETAIL.remove();
                return authenticator;
            }
            String fault = extractSoapFault(response);
            log.warn("Stamps SWSIM AuthenticateUser succeeded (HTTP 200) but returned no Authenticator. Fault: {} · Response head: {}",
                    fault, safeHead(response));
            LAST_AUTH_DETAIL.set(StringUtils.hasText(fault)
                    ? "Stamps.com SWSIM returned: " + fault
                    : "Stamps.com SWSIM returned no Authenticator token.");
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
            LAST_AUTH_DETAIL.set(StringUtils.hasText(fault)
                    ? "Stamps.com rejected the credentials (HTTP " + status + "): " + fault
                    : "Stamps.com SWSIM returned HTTP " + status + ".");
            return buildFallbackToken(clientId, clientSecret);
        } catch (Exception ex) {
            log.warn("Stamps SWSIM AuthenticateUser call to {} failed; using local fallback token. Reason: {}",
                    swsimUrl, ex.getMessage());
            LAST_AUTH_DETAIL.set("could not reach the Stamps.com SWSIM endpoint (" + ex.getMessage() + ")");
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
    public ShipmentResult createShipment(ShipmentRequestDTO request, String accessToken, String environment) {
        if (isSeraFlavor()) {
            return createShipmentSera(request, accessToken, environment);
        }
        return createShipmentSwsim(request, accessToken, environment);
    }

    /**
     * Legacy SWSIM {@code CreateIndicium} path — untouched from the pre-SERA
     * shape. Every existing tenant that hasn't flipped
     * {@code carrier.stamps.api-flavor=SERA} continues to hit this. See the
     * class-level SERA doc on {@link #getAccessToken} for background on the
     * two wire APIs Stamps.com / Endicia exposes and why the branch is here.
     */
    ShipmentResult createShipmentSwsim(ShipmentRequestDTO request, String accessToken, String environment) {
        // Sibling-parity guard (F4 fix): trackShipment, getRates, voidShipment,
        // validateAddress, schedulePickup, closeOutDay all short-circuit when
        // the accessToken is a `-local-*` fallback (unverified/rejected creds).
        // createShipment was the only outlier — pre-fix it fired the CreateIndicium
        // SOAP with the fallback token and let SWSIM 500 with a generic auth
        // fault. Now failing fast at the boundary with an actionable message
        // that tells the operator to re-verify the account. Same message shape
        // and errorCode as the NOT_SUPPORTED sibling returns so downstream
        // handlers stay consistent.
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            throw new com.multiship.backend.exception.CarrierConnectionException(
                    "USPS via Stamps.com: this account's credentials aren't verified "
                            + "(no live SWSIM Authenticator). Re-verify the account on "
                            + "the Carriers page before shipping — SWSIM will reject any "
                            + "label call with a fallback token.");
        }
        // F7 fix — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream in buildCreateIndiciumEnvelope, which
        // shipped international parcels as domestic USPS (wrong service,
        // wrong price, wrong customs). Now failing at the boundary.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "USPS shipment requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before generating a label.");
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> packages = request.effectivePackages();

        // Sprint 29 — multi-package USPS. SWSIM CreateIndicium is single-
        // package by design (one label PDF per call), so a shipment with N
        // packages issues N SOAP calls. We aggregate the results:
        //   trackingNumber  — comma-joined tracking numbers, package 1 first
        //   trackingUrl     — first package's URL (all N are the same lane)
        //   labelUrl/PDF    — first package's label (operator can click
        //                     through per-package via rawResponse if needed)
        //   shippingCost    — sum across all packages
        //   rawResponse     — every response envelope concatenated with
        //                     "<!-- pkg N -->" separators, so debugging can
        //                     see each SWSIM reply.
        java.util.List<ShipmentResult> perPackage = new java.util.ArrayList<>();
        for (int i = 0; i < packages.size(); i++) {
            String soap = buildCreateIndiciumEnvelope(request, packages.get(i),
                    i + 1, packages.size(), accessToken);
            try {
                String response = HttpClients.newBuilder().baseUrl(swsimUrl).build()
                        .post()
                        .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                        .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/CreateIndicium\"")
                        .body(soap)
                        .retrieve()
                        .body(String.class);
                perPackage.add(parseCreateIndiciumResponse(response, request));
            } catch (com.multiship.backend.service.carriers.exceptions.CarrierException cex) {
                // Sprint 49 Tier 2 Fix 4 — rollback any successful pieces
                // BEFORE propagating so the customer isn't charged for real
                // labels that will never ship.
                rollbackSuccessfulPieces(perPackage, accessToken, environment);
                throw cex;
            } catch (Exception ex) {
                // Sprint 49 Tier 2 — no silent fake-label fallback. Throw typed
                // exception after compensating cancel on all pieces we
                // successfully created so far.
                String fault = ex instanceof org.springframework.web.client.RestClientResponseException resp
                        ? extractSoapFault(resp.getResponseBodyAsString())
                        : ex.getMessage();
                log.warn("Stamps CreateIndicium failed for package {}/{}: {}",
                        i + 1, packages.size(), fault);
                rollbackSuccessfulPieces(perPackage, accessToken, environment);
                throw com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper
                        .map("STAMPS", ex, "createShipment[pkg " + (i + 1) + "/" + packages.size() + "]");
            }
        }

        return aggregateStampsShipmentResults(perPackage);
    }

    /**
     * Sprint 49 Tier 2 Fix 4 — compensating CancelIndicium for pieces the
     * MPS loop created before a later piece failed. Best-effort: each
     * cancel call is independently wrapped so one failure doesn't block
     * the others, and none of them mask the original createShipment error
     * (which is what the caller sees).
     */
    void rollbackSuccessfulPieces(java.util.List<ShipmentResult> succeeded,
                                   String accessToken, String environment) {
        if (succeeded == null || succeeded.isEmpty()) return;
        log.warn("Stamps MPS partial failure: rolling back {} successful piece(s) via CancelIndicium.",
                succeeded.size());
        for (ShipmentResult piece : succeeded) {
            String tracking = piece != null ? piece.trackingNumber() : null;
            if (tracking == null || tracking.isBlank()) continue;
            try {
                voidShipment(tracking, accessToken, environment, null, null);
            } catch (Exception cancelEx) {
                // Log and continue — we do NOT want the rollback failure to
                // mask the original createShipment exception the caller
                // wants to see. Ops needs to reconcile these manually.
                log.warn("Stamps rollback CancelIndicium failed for {}: {}",
                        tracking, cancelEx.getMessage());
            }
        }
    }

    /**
     * Aggregate per-package CreateIndicium results into a single
     * {@link ShipmentResult}. Master fields come from piece 1 (Stamps has
     * no explicit shipment identity — piece 1's tracking doubles as the
     * master, matching what customer-facing systems expect). The full
     * per-piece breakdown lives in {@link ShipmentResult#packages()}.
     */
    ShipmentResult aggregateStampsShipmentResults(java.util.List<ShipmentResult> perPackage) {
        if (perPackage.isEmpty()) {
            return new ShipmentResult(null, null, null, null, null, null, null, java.util.List.of());
        }

        java.math.BigDecimal totalCost = perPackage.stream()
                .map(ShipmentResult::shippingCost)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (totalCost.signum() == 0) totalCost = null;

        StringBuilder raw = new StringBuilder(perPackage.size() * 2048);
        for (int i = 0; i < perPackage.size(); i++) {
            raw.append("<!-- pkg ").append(i + 1).append(" -->\n")
                    .append(perPackage.get(i).rawResponse() == null ? "" : perPackage.get(i).rawResponse())
                    .append('\n');
        }

        // Per-piece list — one PackageTracking per SWSIM CreateIndicium
        // response. Sequence number matches the request's package order.
        // Preserve carrierLabelRef from the piece's own PackageTracking
        // when present (SERA populates it with the label_id UUID; SWSIM
        // leaves it null since it voids by tracking).
        java.util.List<PackageTracking> pieces = new java.util.ArrayList<>();
        for (int i = 0; i < perPackage.size(); i++) {
            ShipmentResult r = perPackage.get(i);
            String labelRef = null;
            if (r.packages() != null && !r.packages().isEmpty()) {
                labelRef = r.packages().get(0).carrierLabelRef();
            }
            pieces.add(new PackageTracking(i + 1,
                    r.trackingNumber(), r.trackingUrl(),
                    r.labelUrl(), r.labelPdf(), r.shippingCost(), labelRef));
        }

        ShipmentResult first = perPackage.get(0);
        return new ShipmentResult(
                first.trackingNumber(),   // master = piece 1's tracking (no separate master concept in USPS)
                first.trackingUrl(),
                first.labelUrl(),
                first.labelPdf(),
                totalCost,
                first.estimatedDelivery(),
                raw.toString(),
                pieces);
    }

    // ==========================================================================
    // SERA REST API — the newer Auctane / Stamps.com wire.
    //
    // Wire shape (per developer.stamps.com/rest-api/reference/serav1.html):
    //   Endpoint:     POST {base}/labels
    //   Auth:         Authorization: Bearer {access_token}
    //   Content-Type: application/json
    //
    // Request envelope (only the fields we populate — the API accepts more):
    //   from_address / to_address / return_address (SERA uses these, not
    //     "sender"/"recipient" — the docs' inline table names differ from
    //     the request shape; the request payload uses from_address /
    //     to_address, verified against the reference JSON on the page).
    //   service_type   → snake-case service code (usps_priority_mail, ...)
    //   package        → packaging_type / weight / weight_unit /
    //                    length / width / height / dimension_unit
    //   customs        → contents_type / contents_description /
    //                    non_delivery_option / customs_items[]
    //   insurance      → insurance_provider ("stamps_com" or "carrier") +
    //                    insured_value.amount + insured_value.currency
    //   delivery_confirmation_type → none | tracking | signature | adult_signature
    //   label_options  → label_size / label_format / label_output_type
    //   ship_date      → ISO date (shipper timezone)
    //   is_return_label
    //
    // Response envelope fields we consume:
    //   label_id           → UUID for void + reprint (persisted into
    //                        LabelPackage.carrier_label_ref via V44)
    //   tracking_number    → carrier tracking id
    //   labels[0].href     → base64 blob (when label_output_type=base64)
    //                        or a signed URL (when label_output_type=url)
    //   shipment_cost.total_amount / .currency → net charge
    //   estimated_delivery_date → ISO datetime
    // ==========================================================================

    /**
     * SERA {@code POST /sera/v1/labels} — creates a live label and returns
     * a base64-encoded PDF/ZPL/PNG plus the SERA {@code label_id} UUID
     * that void + reprint key off. Multi-package by design: SERA is
     * single-package per call (same as SWSIM CreateIndicium), so we loop
     * N calls and aggregate per-piece results into one {@link ShipmentResult}
     * exactly like {@link #createShipmentSwsim}.
     *
     * <p>Rollback: if piece K fails after 1..K-1 succeeded, we call SERA
     * void on the succeeded pieces before propagating the exception —
     * same compensating-transaction pattern as the SWSIM path uses via
     * {@link #rollbackSuccessfulPieces}.
     */
    ShipmentResult createShipmentSera(ShipmentRequestDTO request, String accessToken, String environment) {
        // Same fallback-token guard as the SWSIM path — a `-local-*` token
        // is a rejected credential, calling SERA with it just wastes an
        // API call and returns a bewildering 401 to the operator.
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            throw new com.multiship.backend.exception.CarrierConnectionException(
                    "USPS via Stamps.com (SERA): this account's credentials aren't verified "
                            + "(no live SERA access_token). Re-verify the account on the "
                            + "Carriers page before shipping.");
        }
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "USPS shipment requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before generating a label.");
        }
        String baseUrl = seraApiBaseUrl(environment);
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> packages = request.effectivePackages();
        java.util.List<ShipmentResult> perPackage = new java.util.ArrayList<>();
        // PR-T2 — ship-date pulled once outside the loop so the Idempotency-Key
        // and the request body share the same date (midnight-boundary race).
        java.time.LocalDate shipDate = com.multiship.backend.util.LabelDates
                .today(request.getShipperTimezone());
        for (int i = 0; i < packages.size(); i++) {
            String jsonBody = buildSeraCreateLabelBody(request, packages.get(i),
                    i + 1, packages.size(), shipDate);
            try {
                // Audit: SERA requires Idempotency-Key on POSTs (developer.stamps.com
                // §Idempotency). PR-T2 — deterministic key keyed on
                // (reference, piece, shipDate) so a crash-replay hits SERA's
                // 24h dedup cache instead of double-charging. UUID v3
                // keeps the canonical UUID wire shape.
                String response = HttpClients.newBuilder().baseUrl(baseUrl + "/labels").build()
                        .post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key",
                                StringUtils.hasText(request.getReferenceNumber())
                                        ? com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys
                                                .forStampsLabel(request.getReferenceNumber(), i + 1, shipDate)
                                        : java.util.UUID.randomUUID().toString())
                        // PR-T9 (audit T-MPS6) — diagnostic piece context,
                        // kept out of the request body so SERA can tighten
                        // its JSON schema without rejecting a non-spec field.
                        .header("X-Piece-Context",
                                packages.size() > 1 ? (i + 1) + "/" + packages.size() : "1/1")
                        .body(jsonBody)
                        .retrieve()
                        .body(String.class);
                perPackage.add(parseSeraCreateLabelResponse(response, request));
            } catch (com.multiship.backend.service.carriers.exceptions.CarrierException cex) {
                rollbackSuccessfulPiecesSera(perPackage, accessToken, environment);
                throw cex;
            } catch (Exception ex) {
                // PR-T1 — classify by SERA error_code (800xxx) when
                // available. Idempotency-key failures (800001 / 800002)
                // are caller bugs that must NOT auto-retry; 800000
                // validation errors must surface verbatim to the operator.
                StampsSeraErrorCode.SeraError seraErr = ex instanceof org.springframework.web.client.RestClientResponseException resp
                        ? parseSeraError(resp.getResponseBodyAsString())
                        : new StampsSeraErrorCode.SeraError(
                                StampsSeraErrorCode.UNKNOWN, null, ex.getMessage());
                String errMsg = seraErr.message();
                // Distinguish 429 rate-limits from other 4xx/5xx —
                // Auctane rate-limits the /labels endpoint separately
                // from /oauth/token so a bulk batch that clears OAuth
                // can still hit label-side throttling. Retry-After
                // (RFC 7231 §7.1.3) logged verbatim.
                if (ex instanceof org.springframework.web.client.RestClientResponseException rlex
                        && CarrierRateLimit.isRateLimited(rlex)) {
                    log.warn("Stamps SERA /labels {} for package {}/{} — {}",
                            CarrierRateLimit.describe(rlex),
                            i + 1, packages.size(), errMsg);
                } else if (seraErr.code() != StampsSeraErrorCode.UNKNOWN) {
                    log.warn("Stamps SERA /labels failed for package {}/{}: {}",
                            i + 1, packages.size(), seraErr.describe());
                } else {
                    log.warn("Stamps SERA /labels failed for package {}/{}: {}",
                            i + 1, packages.size(), errMsg);
                }
                rollbackSuccessfulPiecesSera(perPackage, accessToken, environment);
                throw com.multiship.backend.service.carriers.exceptions.CarrierExceptionMapper
                        .map("STAMPS", ex, "createShipmentSera[pkg " + (i + 1) + "/" + packages.size() + "]");
            }
        }
        return aggregateStampsShipmentResults(perPackage);
    }

    /**
     * Best-effort rollback for SERA — voids every already-created piece
     * by its {@code label_id}. Same compensating-transaction pattern as
     * {@link #rollbackSuccessfulPieces} (the SWSIM path), except we call
     * SERA's {@code /labels/{label_id}/void} directly instead of going
     * through the tracking-based path (piece rows haven't been persisted
     * to {@code label_package} yet at rollback time, so
     * {@link #resolveSeraLabelId} would return null — bypass the DB and
     * use the label_id we already have from the create response).
     */
    void rollbackSuccessfulPiecesSera(java.util.List<ShipmentResult> succeeded,
                                       String accessToken, String environment) {
        if (succeeded == null || succeeded.isEmpty()) return;
        log.warn("Stamps SERA MPS partial failure: rolling back {} successful piece(s) via /labels/{{label_id}}/void.",
                succeeded.size());
        // PR-T9-X2 (audit T-MPS5) — surface the MPS partial failure on the
        // ops dashboard. One top-level event per rollback (visible even if
        // every piece's void succeeds — ops needs to know an MPS flipped
        // partial), then per-piece events only when the rollback void
        // itself fails (that's the data-loss case: label was paid for and
        // we can't void it, so a human has to reconcile).
        if (fallbackAlertService != null) {
            fallbackAlertService.record(null, null, null,
                    "STAMPS_SERA_MPS_PARTIAL_ROLLBACK",
                    "SERA MPS partial failure — rolling back " + succeeded.size()
                            + " successful piece(s).");
        }
        String baseUrl;
        try {
            baseUrl = seraApiBaseUrl(environment);
        } catch (Exception ex) {
            log.warn("Stamps SERA rollback: cannot resolve base URL ({}), skipping — "
                    + "operator must void the pieces manually.", ex.getMessage());
            if (fallbackAlertService != null) {
                fallbackAlertService.record(null, null, null,
                        "STAMPS_SERA_MPS_ROLLBACK_VOID_FAILED",
                        "Rollback aborted — SERA base URL unresolved (" + ex.getMessage()
                                + "). Operator must void " + succeeded.size()
                                + " piece(s) manually.");
            }
            return;
        }
        for (ShipmentResult piece : succeeded) {
            String labelId = null;
            if (piece != null && piece.packages() != null && !piece.packages().isEmpty()) {
                labelId = piece.packages().get(0).carrierLabelRef();
            }
            if (!StringUtils.hasText(labelId)) continue;
            try {
                // Audit: SERA void is PUT /v1/labels/{label_id}/void per
                // developer.stamps.com — POST returns 405 Method Not Allowed.
                HttpClients.newBuilder().baseUrl(baseUrl + "/labels/" + labelId + "/void").build()
                        .put()
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(String.class);
            } catch (Exception cancelEx) {
                // Log and continue — see the SWSIM rollback pattern for
                // why we don't propagate cancel failures.
                log.warn("Stamps SERA rollback void failed for label_id {}: {}",
                        labelId, cancelEx.getMessage());
                // PR-T9-X2 — per-piece void failure is the data-loss signal.
                // Rolled-back shipment left a paid-for label that nobody
                // will void — ops reconciles manually from this alert.
                if (fallbackAlertService != null) {
                    fallbackAlertService.record(null, null, null,
                            "STAMPS_SERA_MPS_ROLLBACK_VOID_FAILED",
                            "SERA rollback void failed for label_id " + labelId
                                    + ": " + cancelEx.getMessage() + ". Manual void required.");
                }
            }
        }
    }

    /** Resolve the SERA REST base URL for the given environment. Falls back
     *  to production when the caller passed a blank env. */
    String seraApiBaseUrl(String environment) {
        CarrierProperties.Stamps cfg = carrierProperties.getStamps();
        String base = isSandbox(environment) ? cfg.getSeraSandboxApiBaseUrl() : cfg.getSeraApiBaseUrl();
        if (!StringUtils.hasText(base)) {
            throw new com.multiship.backend.exception.CarrierConnectionException(
                    "Stamps.com SERA is enabled (carrier.stamps.api-flavor=SERA) but the "
                            + (isSandbox(environment) ? "sandbox" : "production")
                            + " REST API base URL is not configured. Set carrier.stamps.sera-"
                            + (isSandbox(environment) ? "sandbox-" : "") + "api-base-url.");
        }
        // Strip trailing slash so callers can concatenate paths without
        // producing double-slashes.
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * Build the JSON body for {@code POST /sera/v1/labels} for ONE package
     * in a multi-piece shipment. Uses Jackson so downstream field-name
     * changes are easy to audit and quoting/escaping is centralised.
     */
    /** PR-T2 — convenience overload for tests + callers that don't already
     *  hold a {@code shipDate}; preserves the pre-T2 signature by computing
     *  the date internally. The MPS loop in {@link #createShipmentSera}
     *  passes the pre-computed date so key + body share one value. */
    String buildSeraCreateLabelBody(ShipmentRequestDTO request,
                                     com.multiship.backend.dto.PackageDetailDTO pkg,
                                     int packageIndex, int packageTotal) {
        return buildSeraCreateLabelBody(request, pkg, packageIndex, packageTotal,
                com.multiship.backend.util.LabelDates.today(request.getShipperTimezone()));
    }

    String buildSeraCreateLabelBody(ShipmentRequestDTO request,
                                     com.multiship.backend.dto.PackageDetailDTO pkg,
                                     int packageIndex, int packageTotal,
                                     java.time.LocalDate shipDate) {
        Map<String, Object> body = new LinkedHashMap<>();

        // Addresses. SERA uses from_address / to_address (snake_case, mirroring
        // the reference JSON body on the SERA v1 endpoint doc).
        body.put("from_address", buildSeraAddress(
                request.getShipperName(), request.getShipperCompany(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(), null,
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getShipperPhone(), request.getShipperEmail(),
                null));
        body.put("to_address", buildSeraAddress(
                request.getRecipientName(), request.getRecipientCompany(),
                request.getRecipientAddressLine1(), request.getRecipientAddressLine2(),
                request.getRecipientAddressLine3(),
                request.getRecipientCity(), request.getRecipientState(),
                request.getRecipientPostalCode(), request.getRecipientCountryCode(),
                request.getRecipientPhone(), request.getRecipientEmail(),
                Boolean.TRUE.equals(request.getRecipientResidential()) ? "residential" : null));

        // Service code — SERA speaks a snake-case vocabulary
        // (usps_priority_mail, usps_ground_advantage, ...). We map when the
        // request carries a SWSIM-style code, otherwise pass the value
        // through so operators using SERA-native codes aren't punished.
        String service = mapSwsimServiceToSera(request.getServiceType());
        if (StringUtils.hasText(service)) body.put("service_type", service);

        // Package block: SERA's packaging_type + dimensional fields.
        Map<String, Object> pkgBlock = new LinkedHashMap<>();
        String packagingType = mapPackagingTypeToSera(
                nonBlank(pkg.getPackageType(), request.getPackageType()));
        if (StringUtils.hasText(packagingType)) pkgBlock.put("packaging_type", packagingType);
        // Weight — SERA accepts pound / ounce / gram / kilogram. Our DTO
        // carries lb/oz/kg/g; normalise to the SERA vocabulary.
        java.math.BigDecimal weight = pkg.getWeight();
        String weightUnit = nonBlank(pkg.getWeightUnit(), request.getWeightUnit());
        if (weight != null) {
            pkgBlock.put("weight", weight);
            pkgBlock.put("weight_unit", normaliseSeraWeightUnit(weightUnit));
        }
        if (pkg.getLength() != null) pkgBlock.put("length", pkg.getLength());
        if (pkg.getWidth() != null) pkgBlock.put("width", pkg.getWidth());
        if (pkg.getHeight() != null) pkgBlock.put("height", pkg.getHeight());
        String dimUnit = nonBlank(pkg.getDimUnit(), request.getDimUnit());
        if (StringUtils.hasText(dimUnit)) pkgBlock.put("dimension_unit", normaliseSeraDimUnit(dimUnit));
        body.put("package", pkgBlock);

        // Signature options — SERA's delivery_confirmation_type enum.
        String sig = mapSignatureToSera(request.getSignatureOption());
        if (StringUtils.hasText(sig)) body.put("delivery_confirmation_type", sig);

        // Insurance — SERA's insurance block. Only emit when the caller
        // supplied a positive insured value; SERA rejects zero-value blocks.
        // PR-T9 (audit T-MPS1) — split declared value across pieces so a
        // 3-piece MPS doesn't pay 3× the premium. Even split with the LAST
        // piece absorbing cent-level rounding so the per-piece sum equals
        // the operator-declared total exactly.
        if (request.getInsuredValue() != null && request.getInsuredValue().signum() > 0) {
            java.math.BigDecimal perPieceValue;
            if (packageTotal <= 1) {
                perPieceValue = request.getInsuredValue();
            } else {
                java.math.BigDecimal share = request.getInsuredValue().divide(
                        java.math.BigDecimal.valueOf(packageTotal),
                        2, java.math.RoundingMode.DOWN);
                if (packageIndex == packageTotal) {
                    // Last piece — absorb the rounding remainder so N× share
                    // reconciles exactly against the declared total.
                    java.math.BigDecimal distributed = share.multiply(
                            java.math.BigDecimal.valueOf(packageTotal - 1));
                    perPieceValue = request.getInsuredValue().subtract(distributed);
                } else {
                    perPieceValue = share;
                }
            }
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("insurance_provider", "stamps_com");
            Map<String, Object> val = new LinkedHashMap<>();
            val.put("amount", perPieceValue);
            val.put("currency", nonBlank(request.getInsuredValueCurrency(), "usd")
                    .toLowerCase(Locale.ROOT));
            ins.put("insured_value", val);
            body.put("insurance", ins);
        }

        // Customs — international only, and only when the block is ready.
        // PR-T11 (audit T-TR1 + T-TR2) — US territory + military guards:
        //   · US → PR/VI/GU/MP/AS/UM is DOMESTIC USPS (SERA would reject the
        //     customs block as 800000 validation). Skip even if the FE flagged
        //     the shipment as intl (common operator anti-pattern — PR is
        //     commonly treated as intl for FedEx/UPS).
        //   · US → APO/FPO/DPO (state=AA/AE/AP) IS domestic routing but DOES
        //     need a CN22 customs form (parcel crosses foreign customs
        //     borders en route to the overseas on-base mailroom). WARN when
        //     customs block absent so ops can detect under-declaring.
        boolean recipientIsUsTerritory = com.multiship.backend.util.UsTerritoryNormalizer
                .isUsTerritory(request.getRecipientCountryCode(), request.getRecipientState());
        boolean recipientIsMilitary = com.multiship.backend.util.UsTerritoryNormalizer
                .isMilitaryState(request.getRecipientState());
        boolean intlBlockReady = request.getIntl() != null && request.getIntl().isReadyForCarrier();
        if (intlBlockReady && !recipientIsUsTerritory) {
            body.put("customs", buildSeraCustoms(request, packageIndex, packageTotal));
        } else if (intlBlockReady && recipientIsUsTerritory) {
            log.info("SERA /labels: US-territory destination ({}, state={}) — intl block "
                            + "present but customs omitted (USPS treats territory as domestic). "
                            + "Order {}.",
                    request.getRecipientCountryCode(), request.getRecipientState(),
                    request.getReferenceNumber());
        } else if (recipientIsMilitary && !intlBlockReady) {
            log.warn("SERA /labels: APO/FPO/DPO destination (state={}) typically requires a CN22 "
                            + "customs declaration — intl block missing on order {}. The label will "
                            + "ship but USPS may reject at acceptance.",
                    request.getRecipientState(), request.getReferenceNumber());
        }

        // Ship date — SERA-side calendar convention matches the shipper's
        // local day (same as SWSIM's ShipDate). PR-T2 passes the date in
        // from the MPS loop so key + body share one value across a
        // midnight boundary. Emit as ISO-8601 string (yyyy-MM-dd) so the
        // wire body doesn't depend on Jackson's JavaTimeModule being
        // registered on the ObjectMapper.
        body.put("ship_date", shipDate.toString());

        if (Boolean.TRUE.equals(request.getIsReturn())) body.put("is_return_label", true);

        // Label preferences — size / format / output type. Format follows
        // the request's labelImageFormat override; default is 4x6 PDF as
        // base64 so the downstream persister writes bytes to disk (matches
        // pre-SERA SWSIM behaviour where the label URL was pre-fetched to
        // base64 by PR #550).
        Map<String, Object> labelOpts = new LinkedHashMap<>();
        labelOpts.put("label_size", nonBlank(request.getLabelStockType(), "4x6"));
        labelOpts.put("label_format", normaliseSeraLabelFormat(request.getLabelImageFormat()));
        labelOpts.put("label_output_type", "base64");
        body.put("label_options", labelOpts);

        // PR-T9 (audit T-MPS6) — diagnostic piece context moved to the
        // X-Piece-Context HTTP header (see createShipmentSera call site)
        // so SERA's body schema can tighten over time without rejecting
        // our diagnostic marker. Nothing added here.

        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Jackson only throws for cycles / non-serializable objects; every
            // field above is a String/Number/Boolean/Map so this is unreachable
            // in practice. Re-throw as unchecked so the caller's exception
            // mapper can classify it uniformly.
            throw new IllegalStateException("Failed to serialize SERA label body", ex);
        }
    }

    /** SERA address block — snake-case field names matching the SERA
     *  v1 reference. Nulls omitted so the wire body is tight. */
    private Map<String, Object> buildSeraAddress(String name, String company,
                                                  String line1, String line2, String line3,
                                                  String city, String state, String postal,
                                                  String country, String phone, String email,
                                                  String residentialIndicator) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) a.put("name", name);
        if (StringUtils.hasText(company)) a.put("company_name", company);
        if (StringUtils.hasText(line1)) a.put("address_line1", line1);
        if (StringUtils.hasText(line2)) a.put("address_line2", line2);
        if (StringUtils.hasText(line3)) a.put("address_line3", line3);
        if (StringUtils.hasText(city)) a.put("city", city);
        if (StringUtils.hasText(state)) a.put("state_province", state);
        if (StringUtils.hasText(postal)) a.put("postal_code", postal);
        if (StringUtils.hasText(country)) a.put("country_code", country);
        if (StringUtils.hasText(phone)) a.put("phone", phone);
        if (StringUtils.hasText(email)) a.put("email", email);
        if (StringUtils.hasText(residentialIndicator)) a.put("residential_indicator", residentialIndicator);
        return a;
    }

    /** SERA customs block. Auto-picks {@code contents_type} from the
     *  request's reason-for-export; commodities go into {@code customs_items[]}
     *  with per-item value/weight/HS/COO/SKU fields.
     *
     *  <p>PR-T10 — fields normalised via {@link CustomsCommodityNormaliser}:
     *  HS codes stripped to digits + 10-digit coerce, country_of_origin
     *  coerced to ISO alpha-2, descriptions truncated to CN22 cap,
     *  item-weight × qty reconciled against the package weight (WARN
     *  only; USPS Customs flags for inspection on mismatch).
     *
     *  <p>PR-T9 (audit T-MPS2) — commodities are filtered by
     *  {@link com.multiship.backend.dto.CustomsCommodityDTO#getBoxSeq()}
     *  so each piece of an MPS declares WHAT'S IN THAT PIECE, not the
     *  whole shipment manifest. Commodities with {@code boxSeq == null}
     *  are treated as "unassigned, belongs to every piece" per the
     *  CustomsCommodityDTO javadoc. */
    private Map<String, Object> buildSeraCustoms(ShipmentRequestDTO request) {
        return buildSeraCustoms(request, 1, 1);
    }

    private Map<String, Object> buildSeraCustoms(ShipmentRequestDTO request,
                                                  int packageIndex, int packageTotal) {
        com.multiship.backend.dto.IntlShipmentBlockDTO intl = request.getIntl();
        // PR-T10 — customs currency is regulatory, not best-guess. Fail-fast
        // when blank instead of silently claiming USD.
        if (!StringUtils.hasText(intl.getCustomsCurrency())) {
            throw new IllegalArgumentException(
                    "USPS SERA intl shipment requires IntlShipmentBlockDTO.customsCurrency "
                            + "(order " + request.getReferenceNumber() + "). ISO-4217 code.");
        }
        String currency = intl.getCustomsCurrency().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> customs = new LinkedHashMap<>();
        customs.put("contents_type", mapContentsTypeToSera(intl.getReasonForExport()));
        // SERA gives us free-form contents_description — surface the
        // consolidated summary the operator entered on the order, else the
        // first commodity's description as a sensible fallback.
        String contentsDesc = null;
        if (intl.getCommodities() != null && !intl.getCommodities().isEmpty()) {
            contentsDesc = intl.getCommodities().get(0).getDescription();
        }
        if (StringUtils.hasText(contentsDesc)) {
            customs.put("contents_description", CustomsCommodityNormaliser.truncateDescription(contentsDesc));
        }
        // PR-T7 (audit T-C2) — non_delivery_option is operator-configurable.
        // DTO value wins; fall back to the safer return-to-sender default.
        // SERA accepts only `return_to_sender` and `treat_as_abandoned`;
        // anything else is coerced to the default so a stale enum value
        // doesn't break the whole label.
        String nonDelivery = intl.getNonDeliveryOption();
        if (!"treat_as_abandoned".equalsIgnoreCase(nonDelivery)) {
            nonDelivery = "return_to_sender";
        } else {
            nonDelivery = "treat_as_abandoned";
        }
        customs.put("non_delivery_option", nonDelivery);
        // PR-T7 (audit T-C1) — sender_info carries the export-compliance
        // references required for EEI / AES filings on US exports > USD 2,500
        // (or any export-controlled commodity). Non-US origins thread the
        // generic exportDeclarationReference as the license_number fallback.
        // Empty block omitted entirely so SERA doesn't surface a half-filled
        // customs declaration to the operator.
        Map<String, Object> senderInfo = new LinkedHashMap<>();
        String licenseNumber = nonBlank(intl.getAesCitation(), intl.getExportDeclarationReference());
        if (StringUtils.hasText(licenseNumber)) senderInfo.put("license_number", licenseNumber);
        if (StringUtils.hasText(intl.getFtrExemption())) senderInfo.put("certificate_number", intl.getFtrExemption());
        if (!senderInfo.isEmpty()) customs.put("sender_info", senderInfo);
        // PR-T7 (audit T-C3) — recipient_info.tax_id for EU IOSS / UK VAT /
        // BR CPF / etc. required post-ICS2. Precedence: generic importerTaxId
        // (set by the operator), then VAT, IOSS, EORI, Indian GSTIN. Empty
        // when none configured.
        String recipientTax = firstNonBlankStr(
                intl.getImporterTaxId(), intl.getImporterVat(),
                intl.getImporterIoss(), intl.getImporterEori(),
                intl.getImporterGstin());
        if (StringUtils.hasText(recipientTax)) {
            Map<String, Object> recipientInfo = new LinkedHashMap<>();
            recipientInfo.put("tax_id", recipientTax);
            customs.put("recipient_info", recipientInfo);
        }
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        String weightUnit = normaliseSeraWeightUnit(intl.getWeightUnit());
        // PR-T9 — filter commodities for THIS piece. boxSeq == null → unassigned,
        // belongs to every piece. boxSeq == packageIndex → this piece. Non-MPS
        // (packageTotal == 1) short-circuits to "every commodity" so pre-T9
        // single-piece behaviour is byte-identical.
        java.util.List<com.multiship.backend.dto.CustomsCommodityDTO> pieceCommodities =
                packageTotal > 1
                        ? intl.getCommodities().stream()
                                .filter(c -> c.getBoxSeq() == null
                                        || c.getBoxSeq().equals(packageIndex))
                                .toList()
                        : intl.getCommodities();
        for (com.multiship.backend.dto.CustomsCommodityDTO c : pieceCommodities) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item_description", CustomsCommodityNormaliser.truncateDescription(
                    nonBlank(c.getDescription(), "")));
            int qty = c.getQuantity() != null ? c.getQuantity() : 1;
            row.put("quantity", qty);
            java.math.BigDecimal unitVal = c.getUnitValue();
            if (unitVal != null) {
                Map<String, Object> uv = new LinkedHashMap<>();
                uv.put("amount", unitVal);
                uv.put("currency", currency);
                row.put("unit_value", uv);
            }
            if (c.getUnitWeight() != null) {
                row.put("item_weight", c.getUnitWeight());
                row.put("weight_unit", weightUnit);
            }
            // PR-T10 — HS code stripped of punctuation + 10-digit coerce.
            String hs = CustomsCommodityNormaliser.normaliseHsCode(c.getHsCode());
            if (StringUtils.hasText(hs)) row.put("harmonized_tariff_code", hs);
            // PR-T10 — country_of_origin coerced to ISO alpha-2. Null = we
            // couldn't recognise the input; omit the field so SERA uses its
            // own default rather than reject at validation.
            String coo = CustomsCommodityNormaliser.normaliseCountryOfOrigin(c.getCountryOfOrigin());
            if (StringUtils.hasText(coo)) row.put("country_of_origin", coo);
            if (StringUtils.hasText(c.getSku())) row.put("sku", c.getSku());
            items.add(row);
        }
        customs.put("customs_items", items);
        // PR-T10 — advisory weight reconciliation. Sum declared contents
        // weight across commodities, compare against the shipment weight;
        // outside ±20% logs a WARN so ops can spot filing-risk shipments.
        // PR-T9 — scoped to the piece-filtered subset for MPS.
        java.math.BigDecimal declaredTotal = java.math.BigDecimal.ZERO;
        for (com.multiship.backend.dto.CustomsCommodityDTO c : pieceCommodities) {
            if (c.getUnitWeight() != null && c.getQuantity() != null && c.getQuantity() > 0) {
                declaredTotal = declaredTotal.add(c.getUnitWeight()
                        .multiply(java.math.BigDecimal.valueOf(c.getQuantity())));
            }
        }
        if (declaredTotal.signum() > 0 && request.getWeight() != null) {
            boolean ok = CustomsCommodityNormaliser.weightsReconcile(
                    declaredTotal, 1, request.getWeight());
            if (!ok) {
                log.warn("SERA intl shipment {} — declared contents weight {} {} diverges from "
                                + "package weight {} {} by >20% (USPS Customs flags for inspection).",
                        request.getReferenceNumber(), declaredTotal, weightUnit,
                        request.getWeight(), weightUnit);
            }
        }
        return customs;
    }

    /** Map a SWSIM-style service code ("USPS PM", "USPS GA", ...) to
     *  SERA's snake-case vocabulary. When the input is already snake_case
     *  (SERA-native), pass through. Blank input → null. */
    static String mapSwsimServiceToSera(String service) {
        if (!StringUtils.hasText(service)) return null;
        String v = service.trim();
        // Pass through ONLY when the code already carries a SERA carrier
        // prefix (e.g. "usps_priority_mail", "ups_ground", "fedex_2day").
        // The pre-fix shape-check (just "has underscore + no space") was
        // too permissive — it let SWSIM-style upper_case codes like
        // GROUND_ADVANTAGE through verbatim; SERA then 400'd with 899999
        // "The service_type specified is invalid." Codes without the
        // prefix fall through to the SWSIM/historical-code mapping below.
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("usps_") || lower.startsWith("ups_")
                || lower.startsWith("fedex_") || lower.startsWith("dhl_")) {
            return lower;
        }
        return switch (v.toUpperCase(Locale.ROOT)) {
            case "USPS PM", "PRIORITY" -> "usps_priority_mail";
            case "USPS PME", "PRIORITY_EXPRESS" -> "usps_priority_mail_express";
            case "USPS GA", "GROUND_ADVANTAGE" -> "usps_ground_advantage";
            case "USPS FCM" -> "usps_first_class_mail";
            case "USPS MM" -> "usps_media_mail";
            case "USPS PMI", "PRIORITY_INTL" -> "usps_priority_mail_international";
            case "USPS PMEI", "EXPRESS_INTL" -> "usps_priority_mail_express_international";
            case "USPS GXG" -> "usps_global_express_guaranteed";
            case "USPS FCMI" -> "usps_first_class_mail_international";
            case "USPS FCPIS", "FIRST_CLASS_INTL" -> "usps_first_class_package_international_service";
            default -> v.toLowerCase(Locale.ROOT).replace(' ', '_');
        };
    }

    /** Map a SWSIM-style packaging code to SERA. Pass-through when
     *  snake-case, else best-effort translate. */
    static String mapPackagingTypeToSera(String pt) {
        if (!StringUtils.hasText(pt)) return "package";
        String v = pt.trim();
        // Same pre-fix bug as mapSwsimServiceToSera — "has underscore" is
        // too permissive and lets SWSIM-style YOUR_PACKAGING through as
        // your_packaging, which SERA 400s with 899999 "The packaging_type
        // specified is invalid." Pass through only for codes that already
        // carry a SERA shape (bare generic or carrier-prefixed).
        String lower = v.toLowerCase(Locale.ROOT);
        if ("package".equals(lower) || "letter".equals(lower) || "large_envelope".equals(lower)
                || lower.startsWith("usps_") || lower.startsWith("ups_")
                || lower.startsWith("fedex_") || lower.startsWith("dhl_")) {
            return lower;
        }
        return switch (v.toUpperCase(Locale.ROOT)) {
            case "PACKAGE", "YOUR_PACKAGING" -> "package";
            case "LETTER" -> "letter";
            case "LARGE_ENVELOPE", "LARGEENVELOPE" -> "large_envelope";
            case "SMALL_FLAT_RATE_BOX" -> "usps_small_flat_rate_box";
            case "MEDIUM_FLAT_RATE_BOX" -> "usps_medium_flat_rate_box";
            case "LARGE_FLAT_RATE_BOX" -> "usps_large_flat_rate_box";
            case "REGIONAL_RATE_BOX_A" -> "usps_regional_rate_box_a";
            case "REGIONAL_RATE_BOX_B" -> "usps_regional_rate_box_b";
            case "FLAT_RATE_ENVELOPE" -> "usps_flat_rate_envelope";
            default -> v.toLowerCase(Locale.ROOT).replace(' ', '_');
        };
    }

    /** Map a signature-option code to SERA's delivery_confirmation_type. */
    static String mapSignatureToSera(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty() || "NONE".equals(v)) return null;
        return switch (v) {
            case "INDIRECT", "DIRECT" -> "signature";
            case "ADULT" -> "adult_signature";
            case "TRACKING" -> "tracking";
            default -> null;
        };
    }

    /** Weight unit normalisation. Our DTO uses lb/oz/kg/g; SERA speaks
     *  pound/ounce/kilogram/gram. */
    static String normaliseSeraWeightUnit(String raw) {
        if (!StringUtils.hasText(raw)) return "ounce";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "LB", "LBS", "POUND", "POUNDS" -> "pound";
            case "OZ", "OUNCE", "OUNCES" -> "ounce";
            case "KG", "KILOGRAM", "KILOGRAMS" -> "kilogram";
            case "G", "GRAM", "GRAMS" -> "gram";
            default -> "ounce";
        };
    }

    /** Dim unit normalisation. IN → inch; CM → centimeter. */
    static String normaliseSeraDimUnit(String raw) {
        if (!StringUtils.hasText(raw)) return "inch";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "IN", "INCH", "INCHES" -> "inch";
            case "CM", "CENTIMETER", "CENTIMETERS", "CENTIMETRE", "CENTIMETRES" -> "centimeter";
            default -> "inch";
        };
    }

    /** Label format normalisation. Our request carries PDF / PNG / ZPL /
     *  GIF; SERA speaks pdf / png / zpl / zpl_ascii. GIF and JPG have no
     *  SERA equivalent — silently default to PDF (the safe universal). */
    static String normaliseSeraLabelFormat(String raw) {
        if (!StringUtils.hasText(raw)) return "pdf";
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PDF" -> "pdf";
            case "PNG" -> "png";
            case "ZPL" -> "zpl";
            case "ZPL_ASCII" -> "zpl_ascii";
            default -> "pdf";
        };
    }

    /** Reason-for-export → SERA contents_type enum. */
    static String mapContentsTypeToSera(String reason) {
        if (reason == null) return "merchandise";
        return switch (reason.trim().toUpperCase(Locale.ROOT)) {
            case "SALE" -> "merchandise";
            case "GIFT" -> "gift";
            case "SAMPLE" -> "sample";
            case "RETURN" -> "returned_goods";
            case "DOCUMENTS" -> "documents";
            case "REPAIR" -> "other";
            default -> "merchandise";
        };
    }

    /**
     * Parse a SERA {@code POST /sera/v1/labels} response into a
     * single-piece {@link ShipmentResult}. The label_id UUID is stashed
     * in the piece's {@link PackageTracking#carrierLabelRef()} so
     * downstream persistence (V44 column) can key void + reprint off it.
     *
     * <p>Fault-first: SERA returns a plain JSON body with a
     * {@code label_id} + {@code tracking_number} on success. Missing
     * either field means the server didn't produce a live label; we
     * throw so the createShipment loop routes through the exception
     * mapper + rollback, matching the SWSIM parseCreateIndicium contract.
     */
    ShipmentResult parseSeraCreateLabelResponse(String responseJson, ShipmentRequestDTO request) {
        if (!StringUtils.hasText(responseJson)) {
            throw new IllegalStateException(
                    "SERA /labels returned an empty response for order "
                            + (request == null ? "?" : request.getReferenceNumber()));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "SERA /labels returned non-JSON: " + safeHead(responseJson), ex);
        }
        String labelId = root.path("label_id").asText(null);
        String tracking = root.path("tracking_number").asText(null);
        if (!StringUtils.hasText(tracking) || !StringUtils.hasText(labelId)) {
            String err = extractSeraError(responseJson);
            throw new IllegalStateException(
                    "SERA /labels returned no tracking_number/label_id for order "
                            + (request == null ? "?" : request.getReferenceNumber())
                            + ". Detail: " + err);
        }
        // SERA v1 label response: the spec documents labels[0].href but the
        // live/sandbox servers actually emit labels[0].label_data (base64 of
        // the raw PDF / ZPL / PNG bytes). Reading only `href` dropped the
        // artifact — tracking + label_id persisted correctly but
        // label_file_path landed NULL, forcing the FE to fall back to the
        // local PDFBox facsimile. Prefer label_data (observed shape); fall
        // back to href for forward-compat if Auctane ever ships that
        // variant on the response.
        String labelHref = null;
        JsonNode labels = root.path("labels");
        if (labels.isArray() && labels.size() > 0) {
            JsonNode first = labels.get(0);
            labelHref = first.path("label_data").asText(null);
            if (!StringUtils.hasText(labelHref)) {
                labelHref = first.path("href").asText(null);
            }
        }
        java.math.BigDecimal cost = null;
        JsonNode costNode = root.path("shipment_cost").path("total_amount");
        if (costNode.isNumber()) cost = costNode.decimalValue();
        else if (costNode.isTextual()) {
            try { cost = new java.math.BigDecimal(costNode.asText()); }
            catch (NumberFormatException ignored) { /* leave null */ }
        }
        LocalDateTime estimatedDelivery = parseSeraTimestamp(
                root.path("estimated_delivery_date").asText(null));
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + tracking;

        // Attach carrier_label_ref to the single-piece PackageTracking so
        // aggregateStampsShipmentResults preserves it downstream.
        PackageTracking piece = new PackageTracking(1, tracking, trackingUrl,
                labelHref, labelHref, cost, labelId);
        return new ShipmentResult(tracking, trackingUrl, labelHref, labelHref, cost,
                estimatedDelivery, responseJson, java.util.List.of(piece));
    }

    /** SERA error extraction. PR-T1 — delegates to
     *  {@link StampsSeraErrorCode#parseMessage} so the classified enum
     *  path and the string-message path share one parser. Returns the
     *  free-form {@code error_message} (or best-effort fallback) for log
     *  / exception-message use; callers who want the numeric code should
     *  use {@link #parseSeraError} directly. */
    String extractSeraError(String body) {
        return StampsSeraErrorCode.parseMessage(body, objectMapper);
    }

    /** PR-T1 — full SERA error envelope: classified code + raw code +
     *  message. Callers use this to branch on retriability / idempotency
     *  / pickup-family vs. generic failure. */
    StampsSeraErrorCode.SeraError parseSeraError(String body) {
        return StampsSeraErrorCode.parse(body, objectMapper);
    }

    /** SERA emits ISO-8601 timestamps (with offset). Reuse the SWSIM parser
     *  which already handles both LocalDateTime and OffsetDateTime shapes. */
    private LocalDateTime parseSeraTimestamp(String value) {
        return parseSwsimTimestamp(value);
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
    public TrackingResult trackShipment(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return trackShipment(trackingNumber);
        }
        // PR-T4 — SERA accounts route to GET /sera/v1/tracking before
        // falling to SWSIM. Falls back to the URL-only stub on any
        // transport failure, matching the SWSIM path's recovery shape.
        if (isSeraFlavor()) {
            return trackShipmentSera(trackingNumber, accessToken, environment);
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildTrackShipmentEnvelope(trackingNumber, accessToken);
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
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

    /**
     * PR-T4 — SERA {@code GET /sera/v1/tracking} path. Mirrors the SWSIM
     * trackShipment branch's recovery shape: on any transport / SERA
     * failure, falls back to the URL-only stub so callers never see an
     * exception from the dispatcher.
     *
     * <p>SERA response:
     * <pre>
     * {
     *   "tracking_number": "9400...",
     *   "status_code": "delivered",
     *   "estimated_delivery_date": "2026-10-10T18:00:00-07:00",
     *   "destination": { ... address ... },
     *   "tracking_events": [
     *     {"occurred_at":"...","event_description":"...","location":"...","signed_by":"..."}
     *   ]
     * }
     * </pre>
     * {@code status_code} enum: pre_transit, in_transit, out_for_delivery,
     * delivered, exception, returned. SERA keeps timestamps in ISO-8601
     * with offset — reuse {@link #parseSeraTimestamp}. {@code signed_by}
     * (optional) is folded into the event description since
     * {@link TrackingEvent} has no signee slot.
     */
    TrackingResult trackShipmentSera(String trackingNumber, String accessToken, String environment) {
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + trackingNumber;
        String baseUrl;
        try {
            baseUrl = seraApiBaseUrl(environment);
        } catch (Exception ex) {
            log.warn("Stamps SERA /tracking: base URL not configured ({}); URL-only fallback.",
                    ex.getMessage());
            return trackShipment(trackingNumber);
        }
        try {
            String response = HttpClients.newBuilder().baseUrl(
                    baseUrl + "/tracking?carrier=stamps_usps&tracking_number="
                            + java.net.URLEncoder.encode(trackingNumber, java.nio.charset.StandardCharsets.UTF_8)
            ).build()
                    .get()
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return parseSeraTrackingResponse(trackingNumber, trackingUrl, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404) {
                // Unknown tracking — URL-only stub (same shape SWSIM path uses on 4xx).
                return trackShipment(trackingNumber);
            }
            StampsSeraErrorCode.SeraError seraErr = parseSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA /tracking rejected for {} (HTTP {}): {}",
                    trackingNumber, status, seraErr.describe());
            return trackShipment(trackingNumber);
        } catch (Exception ex) {
            log.warn("Stamps SERA /tracking call failed for {}: {}", trackingNumber, ex.getMessage());
            return trackShipment(trackingNumber);
        }
    }

    /** PR-T4 — parse SERA /tracking JSON into a {@link TrackingResult}.
     *  Package-private for tests that drive the parse path with a
     *  canned response body. */
    TrackingResult parseSeraTrackingResponse(String trackingNumber, String trackingUrl, String responseJson) {
        if (!StringUtils.hasText(responseJson)) {
            return new TrackingResult(trackingNumber, "UNKNOWN", trackingUrl, null, null, false, null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            String statusCode = j.path("status_code").asText("UNKNOWN");
            String status = StringUtils.hasText(statusCode)
                    ? statusCode.toUpperCase(Locale.ROOT) : "UNKNOWN";
            LocalDateTime eta = parseSeraTimestamp(j.path("estimated_delivery_date").asText(null));
            boolean delivered = "delivered".equalsIgnoreCase(statusCode);
            java.util.List<TrackingEvent> events = new java.util.ArrayList<>();
            JsonNode arr = j.path("tracking_events");
            if (arr.isArray()) {
                for (JsonNode e : arr) {
                    LocalDateTime ts = parseSeraTimestamp(e.path("occurred_at").asText(null));
                    String desc = e.path("event_description").asText("");
                    String signedBy = e.path("signed_by").asText(null);
                    if (StringUtils.hasText(signedBy)) {
                        desc = desc.isEmpty() ? "signed by: " + signedBy : desc + " (signed by: " + signedBy + ")";
                    }
                    String evStatus = e.path("status_code").asText(status);
                    String location = e.path("location").asText(null);
                    events.add(new TrackingEvent(ts, evStatus == null ? status
                            : evStatus.toUpperCase(Locale.ROOT), desc, location));
                }
            }
            String currentLocation = events.isEmpty() ? null : events.get(events.size() - 1).location();
            return new TrackingResult(trackingNumber, status, trackingUrl, currentLocation,
                    eta, delivered, responseJson, events);
        } catch (Exception parseEx) {
            log.warn("Stamps SERA /tracking: parse failed for {} — {}", trackingNumber, parseEx.getMessage());
            return new TrackingResult(trackingNumber, "UNKNOWN", trackingUrl, null, null, false, responseJson);
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
        } catch (Exception ex) {
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (Exception ex2) {
                log.debug("Stamps parseSwsimTimestamp: unparseable timestamp '{}' (neither LocalDateTime nor OffsetDateTime matched)", value);
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
    public java.util.List<RateOption> getRates(ShipmentRequestDTO request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            // Surface the placeholder-token case so the rate-shop caller
            // labels this as ERROR (with a pointer to Stamps.com auth)
            // instead of the confusing "returned no rates for this lane"
            // an empty-return produced pre-fix. Same visibility policy
            // as the FedEx/UPS rate paths.
            throw new IllegalStateException(
                    "Stamps.com is not authorized on this account — no live SWSIM "
                            + "token available. Complete the Stamps.com Authorization "
                            + "flow before rate-shopping USPS.");
        }
        // PR-T3 — SERA accounts route to POST /sera/v1/rates via the sibling
        // bean. Null-guard keeps unit-test paths (which construct the
        // connector directly and don't wire the SERA rates bean) on the
        // SWSIM branch. is_customs_required flag is carried on the
        // SeraRateQuote wrapper; .toRateOption() unwraps it for the
        // dispatcher return type (expand to a wider RateOption slot in a
        // follow-up once concurrent write pressure on CarrierConnector.java
        // clears).
        if (isSeraFlavor() && stampsSeraRates != null) {
            return stampsSeraRates.getRates(request, accessToken, environment)
                    .stream()
                    .map(StampsSeraRates.SeraRateQuote::toRateOption)
                    .toList();
        }
        // FDX-B4 — recipient country is required. Pre-fix, blank silently
        // defaulted to "US" downstream in the SWSIM envelope builders
        // (appendServiceRate line 1434 + rate envelope line 1688 both use
        // nonBlank(recipientCountry, "US")), so an intl rate-shop returned
        // believable US-domestic USPS quotes even though USPS only offers
        // limited intl services. Operator would ship and get rejected at
        // manifest time. Same F7 guard shape.
        if (!StringUtils.hasText(request.getRecipientCountryCode())) {
            throw new IllegalArgumentException(
                    "USPS rate-shop requires a recipient country code (order "
                            + request.getReferenceNumber() + "). Set the "
                            + "recipient's country on the Order before rate-shopping — "
                            + "quotes without a destination silently fall to US-domestic.");
        }
        java.util.List<com.multiship.backend.dto.PackageDetailDTO> pkgList = request.effectivePackages();
        if (pkgList.isEmpty()) {
            log.warn("Stamps rate-shop skipped: request has no packages.");
            return java.util.List.of();
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();

        // SWSIM GetRates is single-piece — loop N calls, then aggregate
        // per-service totals. Cost scales linearly with package count; a
        // 100-piece rate-shop is 100 SWSIM roundtrips.
        long startedAt = System.currentTimeMillis();
        java.util.List<java.util.List<RateOption>> perPackage = new java.util.ArrayList<>();
        for (int i = 0; i < pkgList.size(); i++) {
            String soap = buildGetRatesEnvelope(request, pkgList.get(i), accessToken);
            try {
                String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                        .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                        .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/GetRates\"")
                        .body(soap)
                        .retrieve()
                        .body(String.class);
                perPackage.add(parseGetRatesResponse(response));
            } catch (org.springframework.web.client.RestClientResponseException ex) {
                String fault = extractSoapFault(ex.getResponseBodyAsString());
                log.warn("Stamps SWSIM GetRates rejected for pkg {}/{} (HTTP {}): {}",
                        i + 1, pkgList.size(), ex.getStatusCode().value(), fault);
                // One bad piece shouldn't kill the whole rate-shop — treat as
                // empty for that piece; aggregator drops services not on every piece.
                perPackage.add(java.util.List.of());
            } catch (Exception ex) {
                log.warn("Stamps SWSIM GetRates failed for pkg {}/{}: {}",
                        i + 1, pkgList.size(), ex.getMessage());
                perPackage.add(java.util.List.of());
            }
        }
        java.util.List<RateOption> aggregated = aggregateStampsRates(perPackage);
        if (pkgList.size() > 1) {
            log.info("Stamps rate-shop for {}-pkg request: {} SWSIM calls, {} ms, {} aggregate services",
                    pkgList.size(), pkgList.size(), System.currentTimeMillis() - startedAt, aggregated.size());
        }
        return aggregated;
    }

    /**
     * Sum per-service rates across every package's response. A service is
     * only included in the output when EVERY package quoted it — otherwise
     * the aggregate misrepresents cost (some pieces would need a fallback
     * service, changing the price).
     *
     * <p>Package-visible so tests can drive the aggregator with canned
     * per-package rate lists.
     */
    java.util.List<RateOption> aggregateStampsRates(java.util.List<java.util.List<RateOption>> perPackage) {
        if (perPackage.isEmpty()) return java.util.List.of();
        if (perPackage.size() == 1) return perPackage.get(0);

        // Determine services present on EVERY package.
        java.util.Map<String, RateOption> firstByService = new java.util.LinkedHashMap<>();
        for (RateOption r : perPackage.get(0)) {
            if (r != null && StringUtils.hasText(r.serviceCode())) {
                firstByService.putIfAbsent(r.serviceCode(), r);
            }
        }
        java.util.Set<String> commonServices = new java.util.LinkedHashSet<>(firstByService.keySet());
        for (int i = 1; i < perPackage.size(); i++) {
            java.util.Set<String> here = new java.util.HashSet<>();
            for (RateOption r : perPackage.get(i)) {
                if (r != null && StringUtils.hasText(r.serviceCode())) here.add(r.serviceCode());
            }
            commonServices.retainAll(here);
        }

        // Sum totalAmount + max estimatedDelivery / transitDays per common service.
        java.util.List<RateOption> out = new java.util.ArrayList<>();
        for (String svc : commonServices) {
            RateOption template = firstByService.get(svc);
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            String currency = template.currency();
            java.time.LocalDateTime latestDelivery = template.estimatedDelivery();
            Integer maxTransit = template.transitDays();
            for (java.util.List<RateOption> pkg : perPackage) {
                for (RateOption r : pkg) {
                    if (r == null || !svc.equals(r.serviceCode())) continue;
                    if (r.totalAmount() != null) total = total.add(r.totalAmount());
                    if (r.currency() != null) currency = r.currency();
                    if (r.estimatedDelivery() != null
                            && (latestDelivery == null || r.estimatedDelivery().isAfter(latestDelivery))) {
                        latestDelivery = r.estimatedDelivery();
                    }
                    if (r.transitDays() != null && (maxTransit == null || r.transitDays() > maxTransit)) {
                        maxTransit = r.transitDays();
                    }
                    break;
                }
            }
            out.add(new RateOption(template.carrierCode(), svc, template.serviceName(),
                    total, currency, latestDelivery, maxTransit));
        }
        return out;
    }

    /**
     * SWSIM {@code CancelIndicium} — SOAP call to void a previously-issued
     * label. USPS refunds postage when the label hasn't been scanned in
     * transit; post-scan cancels still succeed but no refund is issued.
     *
     * <p>{@code -local-*} tokens short-circuit to {@code NOT_SUPPORTED}.
     */
    @Override
    public VoidResult voidShipment(String trackingNumber, String accessToken, String environment,
                                    String accountNumber, String senderCountryCode) {
        if (isSeraFlavor()) {
            return voidShipmentSera(trackingNumber, accessToken, environment);
        }
        return voidShipmentSwsim(trackingNumber, accessToken, environment);
    }

    /**
     * SWSIM {@code CancelIndicium} path — legacy. Unchanged from the
     * pre-SERA behaviour. Both accountNumber and senderCountryCode are
     * ignored (SWSIM keys off the Authenticator token).
     */
    VoidResult voidShipmentSwsim(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                    "USPS void needs live credentials; the account is on a fallback token.",
                    null);
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildCancelIndiciumEnvelope(trackingNumber, accessToken);
        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/CancelIndicium\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseCancelIndiciumResponse(trackingNumber, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps CancelIndicium rejected for {} (HTTP {}): {}",
                    trackingNumber, ex.getStatusCode().value(), fault);
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SWSIM CancelIndicium rejected: " + fault, ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps CancelIndicium failed for {}: {}", trackingNumber, ex.getMessage());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SWSIM CancelIndicium call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * SERA {@code PUT /sera/v1/labels/{label_id}/void} — cancels a label
     * SERA previously issued. Idempotent: voiding an already-voided label
     * returns success. Post-scan cancels succeed but SERA won't refund
     * postage.
     *
     * <p>Tracking-vs-label_id resolution: SERA keys the void call off the
     * {@code label_id} UUID returned from {@code POST /sera/v1/labels}, NOT
     * the tracking number. We look up {@code label_id} from
     * {@code label_package.carrier_label_ref} (persisted by PR 1's V44
     * migration) using the tracking as the search key.
     */
    VoidResult voidShipmentSera(String trackingNumber, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new VoidResult(trackingNumber, false, "NOT_SUPPORTED",
                    "USPS via Stamps.com (SERA) void needs live credentials; the account is on a fallback token.",
                    null);
        }
        String labelId = resolveSeraLabelId(trackingNumber);
        if (!StringUtils.hasText(labelId)) {
            // SERA can't void by tracking — without the UUID from the create
            // response, the call has no legal input shape. Surface an
            // actionable message so operators know why the void didn't
            // fire (typical cause: label was created BEFORE PR 1's V44
            // rolled out, so carrier_label_ref is null).
            log.warn("Stamps SERA void: no carrier_label_ref persisted for tracking {} — cannot call /labels/{{label_id}}/void.",
                    trackingNumber);
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SERA void needs the label_id (persisted in label_package.carrier_label_ref); "
                            + "none found for tracking " + trackingNumber
                            + ". Labels created before SERA support shipped can only be voided manually via the Stamps.com dashboard.",
                    null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        try {
            // Audit: SERA void endpoint is PUT /v1/labels/{label_id}/void per
            // developer.stamps.com/rest-api/reference/serav1.html — the previous
            // POST returned 405 Method Not Allowed. The endpoint takes no body
            // and is idempotent (repeated calls return 200/404 without
            // side-effects), so no Idempotency-Key needed here.
            String response = HttpClients.newBuilder()
                    .baseUrl(baseUrl + "/labels/" + labelId + "/void").build()
                    .put()
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return parseSeraVoidResponse(trackingNumber, labelId, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String err = extractSeraError(ex.getResponseBodyAsString());
            // 404 on a legit label_id usually means already voided — treat
            // as ALREADY_VOIDED (idempotent semantics the interface doc
            // promises) rather than a real error.
            if (status == 404) {
                log.info("Stamps SERA void: label_id {} not found (already voided?) — returning ALREADY_VOIDED.",
                        labelId);
                return new VoidResult(trackingNumber, true, "ALREADY_VOIDED",
                        "SERA reported label_id " + labelId + " as not found — treating as already voided.",
                        ex.getResponseBodyAsString());
            }
            log.warn("Stamps SERA void rejected for label_id {} (HTTP {}): {}",
                    labelId, status, err);
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SERA void rejected (HTTP " + status + "): " + err,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA void call failed for label_id {}: {}", labelId, ex.getMessage());
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SERA void call failed: " + ex.getMessage(), null);
        }
    }

    /** Resolve the SERA {@code label_id} for a tracking number by reading
     *  {@code label_package.carrier_label_ref} (V44). Returns null when the
     *  repository isn't wired (unit tests) or no row matches. */
    String resolveSeraLabelId(String trackingNumber) {
        if (!StringUtils.hasText(trackingNumber) || labelPackageRepository == null) return null;
        return labelPackageRepository.findByTrackingNumber(trackingNumber)
                .map(com.multiship.backend.model.LabelPackage::getCarrierLabelRef)
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    /**
     * Parse SERA's void response. The documented success shape is
     * {@code {"status": "success"}} but the endpoint may return a
     * plain 200 with no body — treat both as VOIDED. Non-200 paths land
     * in the RestClientResponseException branch above.
     */
    VoidResult parseSeraVoidResponse(String trackingNumber, String labelId, String responseJson) {
        // Empty body on 200 → success (SERA's success responses are minimal).
        if (!StringUtils.hasText(responseJson)) {
            return new VoidResult(trackingNumber, true, "VOIDED",
                    "SERA confirmed void for label_id " + labelId + ".", null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            String status = j.path("status").asText(null);
            // Explicit non-success status → surface as ERROR.
            if (StringUtils.hasText(status) && !"success".equalsIgnoreCase(status)) {
                return new VoidResult(trackingNumber, false, "ERROR",
                        "SERA void status=" + status + ": " + extractSeraError(responseJson),
                        responseJson);
            }
        } catch (Exception parseIgnored) {
            // Non-JSON 200 body — treat as success (SERA's own error paths
            // 4xx/5xx which are handled above; a 200 with weird body is
            // reliably success).
        }
        return new VoidResult(trackingNumber, true, "VOIDED",
                "SERA confirmed void for label_id " + labelId + ".", responseJson);
    }

    /**
     * Balance query. SWSIM {@code GetAccountInfo} exposes a
     * {@code PostageBalance} field but this connector never wired it —
     * SWSIM-flavored callers see NOT_SUPPORTED with a hint about the
     * dashboard, matching the pre-SERA behaviour. SERA has a first-class
     * {@code GET /sera/v1/balance} endpoint that returns
     * {@code amount_available} / {@code max_balance_amount_allowed} in
     * {@code currency} — this dispatches to it.
     */
    @Override
    public BalanceResult getAccountBalance(String accessToken, String environment) {
        if (isSeraFlavor()) {
            return getAccountBalanceSera(accessToken, environment);
        }
        return new BalanceResult(
                CARRIER_CODE, null, null, null,
                "NOT_SUPPORTED",
                "USPS via Stamps.com (SWSIM): balance query not wired on this connector. "
                        + "Check the Stamps.com dashboard for account balance, or flip "
                        + "carrier.stamps.api-flavor=SERA for a live balance endpoint.",
                null);
    }

    /**
     * SERA {@code GET /sera/v1/balance}. Response shape (per
     * developer.stamps.com/rest-api/reference/serav1.html):
     * <pre>
     * {
     *   "amount_available": number,
     *   "max_balance_amount_allowed": number,
     *   "currency": "usd"
     * }
     * </pre>
     *
     * <p>{@code -local-*} tokens short-circuit to NOT_SUPPORTED matching
     * the sibling-parity guard every other SERA branch uses.
     */

    /**
     * D5 — Stamps SERA {@code POST /balance/add-funds}. Purchases {@code amount}
     * of postage. {@code Idempotency-Key} header prevents double-buy under
     * concurrent retries — use {@link com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys#forStampsTopup(long, String)}
     * to mint one keyed on (accountId, hour bucket).
     *
     * @return same {@link BalanceResult} shape as {@link #getAccountBalance};
     *         status "OK" carries the post-top-up available balance.
     *         Any HTTP or parse error surfaces as status "ERROR" with the
     *         raw response body attached — callers alert on that.
     */
    public BalanceResult addFundsSera(String accessToken, java.math.BigDecimal amount,
                                      String currency, String idempotencyKey, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new BalanceResult(CARRIER_CODE, null, null, currency, "NOT_SUPPORTED",
                    "SERA add-funds needs live credentials; account is on a fallback token.", null);
        }
        if (amount == null || amount.signum() <= 0) {
            return new BalanceResult(CARRIER_CODE, null, null, currency, "ERROR",
                    "SERA add-funds: amount must be positive.", null);
        }
        String cur = currency == null || currency.isBlank() ? "usd" : currency.trim().toLowerCase(java.util.Locale.ROOT);
        String baseUrl = seraApiBaseUrl(environment);
        java.util.Map<String, Object> body = java.util.Map.of("amount", amount, "currency", cur);
        try {
            String response = HttpClients.newBuilder().baseUrl(baseUrl + "/balance/add-funds").build()
                    .post()
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", idempotencyKey == null ? "" : idempotencyKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            // Response echoes the new balance in the same shape as GET /balance.
            return parseSeraBalanceResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String err = extractSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA /balance/add-funds rejected (HTTP {}): {}", status, err);
            return new BalanceResult(CARRIER_CODE, null, null, cur, "ERROR",
                    "SERA add-funds rejected (HTTP " + status + "): " + err,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA /balance/add-funds call failed: {}", ex.getMessage());
            return new BalanceResult(CARRIER_CODE, null, null, cur, "ERROR",
                    "SERA add-funds call failed: " + ex.getMessage(), null);
        }
    }

    BalanceResult getAccountBalanceSera(String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new BalanceResult(
                    CARRIER_CODE, null, null, null,
                    "NOT_SUPPORTED",
                    "SERA balance needs live credentials; the account is on a fallback token.",
                    null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        try {
            String response = HttpClients.newBuilder().baseUrl(baseUrl + "/balance").build()
                    .get()
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return parseSeraBalanceResponse(response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String err = extractSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA /balance rejected (HTTP {}): {}", status, err);
            return new BalanceResult(
                    CARRIER_CODE, null, null, null, "ERROR",
                    "SERA balance rejected (HTTP " + status + "): " + err,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA /balance call failed: {}", ex.getMessage());
            return new BalanceResult(
                    CARRIER_CODE, null, null, null, "ERROR",
                    "SERA balance call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Parse the SERA balance response. Currency is uppercased to match
     * ISO-4217 convention downstream (SERA emits lowercase "usd" per its
     * doc; the rest of our system speaks "USD"). Missing / unparseable
     * amount fields surface as ERROR rather than a bogus zero balance —
     * silent zero would look like an empty account.
     */
    BalanceResult parseSeraBalanceResponse(String responseJson) {
        if (!StringUtils.hasText(responseJson)) {
            return new BalanceResult(CARRIER_CODE, null, null, null, "ERROR",
                    "SERA /balance returned an empty response.", null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            java.math.BigDecimal available = readSeraMoney(j.path("amount_available"));
            java.math.BigDecimal maxBalance = readSeraMoney(j.path("max_balance_amount_allowed"));
            String currency = j.path("currency").asText(null);
            if (StringUtils.hasText(currency)) currency = currency.toUpperCase(Locale.ROOT);
            if (available == null) {
                return new BalanceResult(CARRIER_CODE, null, null, currency, "ERROR",
                        "SERA /balance response missing amount_available: " + safeHead(responseJson),
                        responseJson);
            }
            return new BalanceResult(CARRIER_CODE, available, maxBalance, currency, "OK",
                    "USPS via Stamps.com balance: "
                            + available.toPlainString()
                            + (StringUtils.hasText(currency) ? " " + currency : "")
                            + (maxBalance != null ? " (cap " + maxBalance.toPlainString() + ")" : ""),
                    responseJson);
        } catch (Exception ex) {
            return new BalanceResult(CARRIER_CODE, null, null, null, "ERROR",
                    "SERA /balance returned non-JSON or malformed body: " + ex.getMessage(),
                    responseJson);
        }
    }

    /** Read a SERA money field. Accepts JSON number or string ("1.23")
     *  — SERA is documented as number but some carrier gateways proxy
     *  the response as strings. Null when the node is missing or blank. */
    private static java.math.BigDecimal readSeraMoney(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            String s = node.asText();
            if (!StringUtils.hasText(s)) return null;
            try { return new java.math.BigDecimal(s); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /**
     * Reprint / retrieve a label. SERA {@code GET /sera/v1/labels/{label_id}}
     * with query-string {@code label_size}, {@code label_format},
     * {@code label_output_type}. SWSIM has no first-class reprint endpoint
     * — the SWSIM branch returns NOT_SUPPORTED with a note pointing
     * operators to the URL persisted on the original label response
     * (label_url in label_batch.master_label_url / label_package.label_file_path).
     */
    @Override
    public LabelReprintResult reprintLabel(String trackingNumber, String labelSize, String labelFormat,
                                            String accessToken, String environment) {
        if (isSeraFlavor()) {
            return reprintLabelSera(trackingNumber, labelSize, labelFormat, accessToken, environment);
        }
        return new LabelReprintResult(
                CARRIER_CODE, null, null, "NOT_SUPPORTED",
                "USPS via Stamps.com (SWSIM) has no reprint endpoint. The original label URL is "
                        + "persisted in label_batch.master_label_url / label_package.label_file_path; "
                        + "print from there, or flip carrier.stamps.api-flavor=SERA for a live reprint API.",
                null);
    }

    /**
     * SERA {@code GET /sera/v1/labels/{label_id}}. Response shape:
     * <pre>
     * {
     *   "labels": [{"href": "https://..." | "BASE64..."}],
     *   "forms":  [{"href": "https://..."}]
     * }
     * </pre>
     * We request {@code label_output_type=base64} to match the persistence
     * convention (bytes on disk survive URL expiry — see PR #550).
     */
    LabelReprintResult reprintLabelSera(String trackingNumber, String labelSize, String labelFormat,
                                         String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "NOT_SUPPORTED",
                    "SERA reprint needs live credentials; the account is on a fallback token.",
                    null);
        }
        String labelId = resolveSeraLabelId(trackingNumber);
        if (!StringUtils.hasText(labelId)) {
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "ERROR",
                    "SERA reprint needs the label_id (persisted in label_package.carrier_label_ref); "
                            + "none found for tracking " + trackingNumber
                            + ". Labels created before SERA support shipped can only be reprinted from the persisted label_url.",
                    null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        // SERA accepts query params on GET /labels/{id}: label_size,
        // label_format, label_output_type. Build them via a MultiValueMap
        // so RestClient handles URL-encoding.
        String size = normaliseSeraLabelSize(labelSize);
        String format = normaliseSeraLabelFormat(labelFormat);
        String url = baseUrl + "/labels/" + labelId
                + "?label_size=" + size
                + "&label_format=" + format
                + "&label_output_type=base64";
        try {
            String response = HttpClients.newBuilder().baseUrl(url).build()
                    .get()
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return parseSeraReprintResponse(labelId, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String err = extractSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA reprint rejected for label_id {} (HTTP {}): {}",
                    labelId, status, err);
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "ERROR",
                    "SERA reprint rejected (HTTP " + status + "): " + err,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA reprint call failed for label_id {}: {}", labelId, ex.getMessage());
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "ERROR",
                    "SERA reprint call failed: " + ex.getMessage(), null);
        }
    }

    /** Parse SERA's reprint response. {@code labels[0].href} carries the
     *  document — either a signed URL (when output_type=url) or base64
     *  bytes (when output_type=base64). We always request base64 in
     *  {@link #reprintLabelSera} so the bytes path is the common case,
     *  but we tolerate the URL shape for cases where operators paste a
     *  pre-built request. Heuristic: if the value starts with "http" we
     *  treat as URL, otherwise as base64. */
    LabelReprintResult parseSeraReprintResponse(String labelId, String responseJson) {
        if (!StringUtils.hasText(responseJson)) {
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "ERROR",
                    "SERA reprint returned an empty response for label_id " + labelId + ".",
                    null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            JsonNode labels = j.path("labels");
            if (!labels.isArray() || labels.size() == 0) {
                return new LabelReprintResult(
                        CARRIER_CODE, null, null, "ERROR",
                        "SERA reprint response missing labels[]: " + safeHead(responseJson),
                        responseJson);
            }
            // Same spec-vs-wire mismatch as createShipmentSera: the spec
            // documents labels[0].href but SERA actually emits
            // labels[0].label_data. Prefer label_data; fall back to href.
            JsonNode first = labels.get(0);
            String href = first.path("label_data").asText(null);
            if (!StringUtils.hasText(href)) {
                href = first.path("href").asText(null);
            }
            if (!StringUtils.hasText(href)) {
                return new LabelReprintResult(
                        CARRIER_CODE, null, null, "ERROR",
                        "SERA reprint response missing labels[0].label_data/href: " + safeHead(responseJson),
                        responseJson);
            }
            String url = null;
            String base64 = null;
            if (href.startsWith("http://") || href.startsWith("https://")) {
                url = href;
            } else {
                base64 = href;
            }
            return new LabelReprintResult(
                    CARRIER_CODE, url, base64, "OK",
                    "SERA reprint delivered label for label_id " + labelId + ".",
                    responseJson);
        } catch (Exception ex) {
            return new LabelReprintResult(
                    CARRIER_CODE, null, null, "ERROR",
                    "SERA reprint returned malformed body: " + ex.getMessage(), responseJson);
        }
    }

    /** Normalise a label-size input to SERA's vocabulary. Blank → 4x6
     *  (the SERA default for thermal shipping labels). */
    static String normaliseSeraLabelSize(String raw) {
        if (!StringUtils.hasText(raw)) return "4x6";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "4x6", "4x6.75-doctab", "4x8.25-doctab", "letter" -> v;
            default -> "4x6";
        };
    }

    /** Build the SWSIM CancelIndicium SOAP envelope. */
    String buildCancelIndiciumEnvelope(String trackingNumber, String authenticator) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<CancelIndicium xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<StampsTxID>").append(xmlEscape(nonBlank(trackingNumber, ""))).append("</StampsTxID>");
        xml.append("</CancelIndicium>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse a CancelIndicium response. SWSIM returns a rotated
     * Authenticator on success + no fault. Presence of a {@code <faultstring>}
     * element in the body indicates rejection.
     */
    VoidResult parseCancelIndiciumResponse(String trackingNumber, String responseXml) {
        if (!StringUtils.hasText(responseXml)) {
            return new VoidResult(trackingNumber, false, "ERROR",
                    "SWSIM returned an empty CancelIndicium response.", null);
        }
        String fault = extractElement(responseXml, "faultstring");
        if (StringUtils.hasText(fault)) {
            return new VoidResult(trackingNumber, false, "ERROR",
                    "USPS void rejected: " + fault, responseXml);
        }
        // Any 200 without a fault is a success — SWSIM does not surface a
        // dedicated confirmation code beyond the rotated Authenticator.
        return new VoidResult(trackingNumber, true, "VOIDED",
                "SWSIM confirmed void.", responseXml);
    }

    /**
     * SWSIM {@code CleanseAddress} — validates + normalises a US address
     * against USPS's own database. Foreign addresses go through a separate
     * {@code ValidateForeignAddress} call; we route to the right one
     * based on {@code address.countryCode()}.
     *
     * <p>Response gives {@code CleanseHash}, {@code AddressMatch} (true =
     * exact), {@code CityStateZipOK} (true when at least the postal
     * region is valid), and echoes a normalised address block.
     *
     * <p>USPS doesn't return residential/commercial classification —
     * that requires the paid Residential Delivery Indicator (RDI)
     * add-on we don't wire here.
     */
    @Override
    public AddressValidationResult validateAddress(AddressToValidate address, String accessToken, String environment) {
        // PR-T6 — SERA accounts hit POST /sera/v1/addresses/validate instead of
        // SWSIM CleanseAddress. Both branches share the AddressValidationResult
        // shape; SERA additionally populates candidates[] when the match is
        // ambiguous.
        if (isSeraFlavor()) {
            return validateAddressSera(address, accessToken, environment);
        }
        return validateAddressSwsim(address, accessToken, environment);
    }

    /** SWSIM {@code CleanseAddress} / {@code ValidateForeignAddress} path —
     *  legacy behaviour, unchanged from pre-PR-T6. */
    AddressValidationResult validateAddressSwsim(AddressToValidate address, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new AddressValidationResult(false, "NOT_SUPPORTED", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM address validation needs live credentials; the account is on a fallback token.",
                    null);
        }
        // Env routing — mirrors every other SWSIM endpoint in this class
        // (getAccessToken, createShipment, getRates, trackShipment,
        // voidShipment, schedulePickup, closeOutDay). Previously hardcoded
        // to getApiBaseUrl() so a SANDBOX operator's address validation
        // hit prod SWSIM — real production request from a test session
        // (env bleed). Fixed by routing SANDBOX to the sandbox host.
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        boolean domestic = !StringUtils.hasText(address.countryCode())
                || "US".equalsIgnoreCase(address.countryCode().trim());
        String operation = domestic ? "CleanseAddress" : "ValidateForeignAddress";
        String soap = buildCleanseAddressEnvelope(address, accessToken, domestic);
        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/" + operation + "\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseCleanseAddressResponse(address, response, domestic);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps {} rejected (HTTP {}): {}",
                    operation, ex.getStatusCode().value(), fault);
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM " + operation + " rejected: " + fault,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps {} failed: {}", operation, ex.getMessage());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM " + operation + " call failed: " + ex.getMessage(), null);
        }
    }

    /** Build the SWSIM CleanseAddress / ValidateForeignAddress envelope. */
    String buildCleanseAddressEnvelope(AddressToValidate address, String authenticator,
                                        boolean domestic) {
        String op = domestic ? "CleanseAddress" : "ValidateForeignAddress";
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<").append(op).append(" xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<Address>");
        if (StringUtils.hasText(address.name())) {
            xml.append("<FullName>").append(xmlEscape(address.name())).append("</FullName>");
        }
        if (StringUtils.hasText(address.addressLine1())) {
            xml.append("<Address1>").append(xmlEscape(address.addressLine1())).append("</Address1>");
        }
        String line2 = joinSwsimAddress2(address.addressLine2(), address.addressLine3());
        if (StringUtils.hasText(line2)) {
            xml.append("<Address2>").append(xmlEscape(line2)).append("</Address2>");
        }
        if (StringUtils.hasText(address.city())) {
            xml.append("<City>").append(xmlEscape(address.city())).append("</City>");
        }
        if (StringUtils.hasText(address.state())) {
            xml.append("<State>").append(xmlEscape(address.state())).append("</State>");
        }
        if (StringUtils.hasText(address.postalCode())) {
            xml.append("<ZIPCode>").append(xmlEscape(address.postalCode())).append("</ZIPCode>");
        }
        if (!domestic && StringUtils.hasText(address.countryCode())) {
            xml.append("<Country>").append(xmlEscape(address.countryCode())).append("</Country>");
        }
        xml.append("</Address>");
        xml.append("</").append(op).append(">");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse a SWSIM CleanseAddress / ValidateForeignAddress response.
     * Domestic: {@code AddressMatch=true} = EXACT; {@code CityStateZipOK=true}
     * + AddressMatch=false = CORRECTED (USPS suggested a change);
     * else NOT_FOUND. Foreign: presence of a normalised response with
     * no fault = EXACT (SWSIM's foreign validator is coarser).
     */
    AddressValidationResult parseCleanseAddressResponse(AddressToValidate input, String responseXml,
                                                        boolean domestic) {
        if (!StringUtils.hasText(responseXml)) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM returned an empty address-validation response.", null);
        }
        String fault = extractElement(responseXml, "faultstring");
        if (StringUtils.hasText(fault)) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM address validation rejected: " + fault, responseXml);
        }

        if (!domestic) {
            // Foreign path — no AddressMatch flag, just a normalised echo.
            AddressToValidate suggested = readSwsimAddressEcho(responseXml, input);
            return new AddressValidationResult(true, "EXACT", "UNKNOWN", null,
                    java.util.List.of(),
                    "SWSIM validated the foreign address.", responseXml);
        }

        boolean addressMatch = "true".equalsIgnoreCase(
                extractElement(responseXml, "AddressMatch"));
        boolean cityStateZipOk = "true".equalsIgnoreCase(
                extractElement(responseXml, "CityStateZipOK"));

        if (addressMatch) {
            return new AddressValidationResult(true, "EXACT", "UNKNOWN", null,
                    java.util.List.of(),
                    "USPS confirmed this address is deliverable.", responseXml);
        }
        if (cityStateZipOk) {
            AddressToValidate suggested = readSwsimAddressEcho(responseXml, input);
            return new AddressValidationResult(true, "CORRECTED", "UNKNOWN", suggested,
                    java.util.List.of("USPS normalised the street address; review before shipping."),
                    "USPS suggested a corrected address.", responseXml);
        }
        return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                java.util.List.of(),
                "USPS couldn't find this address.", responseXml);
    }

    private static AddressToValidate readSwsimAddressEcho(String xml, AddressToValidate input) {
        return new AddressToValidate(
                extractElement(xml, "FullName"),
                null,
                extractElement(xml, "Address1"),
                extractElement(xml, "Address2"),
                null,
                extractElement(xml, "City"),
                extractElement(xml, "State"),
                extractElement(xml, "ZIPCode"),
                nonBlank(extractElement(xml, "Country"), input.countryCode()));
    }

    /**
     * PR-T6 (audit §5.4) — SERA {@code POST /sera/v1/addresses/validate}
     * path. Replaces SWSIM CleanseAddress for SERA-flavor accounts.
     *
     * <p>Request body is an ARRAY of address objects (SERA accepts a batch;
     * we send a 1-element array for the single-address call). Response is an
     * array in the same order; we read entry 0.
     *
     * <p>Response fields consumed:
     * <ul>
     *   <li>{@code matched_address}       → suggested canonical form (CORRECTED)</li>
     *   <li>{@code candidate_addresses[]} → ambiguous-match suggestions (AMBIGUOUS)</li>
     *   <li>{@code is_po_box}             → surfaced as a warning</li>
     *   <li>{@code is_apo_fpo}            → surfaced as a warning</li>
     *   <li>{@code validation_results.result_description} → status/message</li>
     * </ul>
     *
     * <p>{@code Idempotency-Key} is required on SERA POSTs. We mint one via
     * {@link com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys#forStampsAddressValidate}
     * keyed on (city, state, postal, country) so a double-click returns
     * SERA's cached verdict instead of counting against account quota.
     */
    AddressValidationResult validateAddressSera(AddressToValidate address, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new AddressValidationResult(false, "NOT_SUPPORTED", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA address validation needs live credentials; the account is on a fallback token.",
                    null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        String body = buildSeraValidateAddressBody(address);
        String idemKey = com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys
                .forStampsAddressValidate(address.city(), address.state(),
                        address.postalCode(), address.countryCode());
        try {
            String response = HttpClients.newBuilder().baseUrl(baseUrl + "/addresses/validate").build()
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", idemKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseSeraValidateAddressResponse(address, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // PR-T1 — SERA 400s with error_code 800000 carry an operator-
            // facing validation message; surface it verbatim.
            StampsSeraErrorCode.SeraError err = parseSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA /addresses/validate rejected (HTTP {}): {}",
                    status, err.describe());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA address validation rejected (HTTP " + status + "): " + err.message(),
                    ex.getResponseBodyAsString(),
                    java.util.List.of());
        } catch (Exception ex) {
            log.warn("Stamps SERA /addresses/validate failed: {}", ex.getMessage());
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA address validation call failed: " + ex.getMessage(), null,
                    java.util.List.of());
        }
    }

    /** Build the SERA {@code POST /sera/v1/addresses/validate} body.
     *  Top-level is a JSON ARRAY (SERA accepts batch validation; we send
     *  one entry for the single-address flow). Each entry re-uses
     *  {@link #buildSeraAddress} so the field-name vocabulary matches the
     *  create-label call. */
    String buildSeraValidateAddressBody(AddressToValidate a) {
        Map<String, Object> entry = buildSeraAddress(
                a.name(), a.company(),
                a.addressLine1(), a.addressLine2(), a.addressLine3(),
                a.city(), a.state(), a.postalCode(), a.countryCode(),
                null, null, null);
        try {
            return objectMapper.writeValueAsString(java.util.List.of(entry));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize SERA validate-address body", ex);
        }
    }

    /** Parse the SERA validate-address response. Expected shape (array of
     *  per-input results):
     *  <pre>
     *  [{
     *    "original_address":   { ... },
     *    "matched_address":    { ... },           // canonical form when confident
     *    "candidate_addresses": [ { ... }, ... ], // present when ambiguous
     *    "is_po_box":   false,
     *    "is_apo_fpo":  false,
     *    "validation_results": { "result_code": "verified", "result_description": "..." }
     *  }]
     *  </pre>
     *  Empty / malformed body → "could not validate" result (does not throw). */
    AddressValidationResult parseSeraValidateAddressResponse(AddressToValidate input, String responseJson) {
        if (!StringUtils.hasText(responseJson)) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA /addresses/validate returned an empty response.", null,
                    java.util.List.of());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception ex) {
            return new AddressValidationResult(false, "ERROR", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA /addresses/validate returned non-JSON: " + ex.getMessage(),
                    responseJson, java.util.List.of());
        }
        // SERA returns an array in input order. Pull entry 0.
        JsonNode entry = root.isArray() && root.size() > 0 ? root.get(0) : root;
        if (entry == null || entry.isMissingNode() || entry.isNull()) {
            return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                    java.util.List.of(),
                    "SERA /addresses/validate returned no entry for the input address.",
                    responseJson, java.util.List.of());
        }

        boolean isPoBox = entry.path("is_po_box").asBoolean(false);
        boolean isApoFpo = entry.path("is_apo_fpo").asBoolean(false);
        String description = entry.path("validation_results").path("result_description").asText(null);

        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (isPoBox) warnings.add("USPS reports this as a PO Box.");
        if (isApoFpo) warnings.add("USPS reports this as an APO/FPO/DPO address.");

        JsonNode matched = entry.path("matched_address");
        JsonNode candidatesNode = entry.path("candidate_addresses");
        java.util.List<AddressToValidate> candidates = new java.util.ArrayList<>();
        if (candidatesNode.isArray()) {
            for (JsonNode c : candidatesNode) {
                AddressToValidate parsed = readSeraAddress(c, input);
                if (parsed != null) candidates.add(parsed);
            }
        }

        // Ambiguous — SERA couldn't pick one; surface the candidate list.
        if (!candidates.isEmpty() && (matched.isMissingNode() || matched.isNull())) {
            return new AddressValidationResult(false, "AMBIGUOUS", "UNKNOWN", null,
                    java.util.List.copyOf(warnings),
                    nonBlank(description, "SERA couldn't confidently match this address; pick a candidate."),
                    responseJson, java.util.List.copyOf(candidates));
        }

        // Confident match — matched_address populated. If it echoes the
        // input verbatim, mark EXACT; else CORRECTED.
        if (!matched.isMissingNode() && !matched.isNull()) {
            AddressToValidate suggested = readSeraAddress(matched, input);
            boolean sameAsInput = suggested != null && seraAddressesEqual(input, suggested);
            String level = sameAsInput ? "EXACT" : "CORRECTED";
            String msg = nonBlank(description,
                    sameAsInput
                        ? "USPS confirmed this address is deliverable."
                        : "USPS suggested a corrected address.");
            if (!sameAsInput) {
                warnings.add("USPS normalised the address; review before shipping.");
            }
            return new AddressValidationResult(true, level, "UNKNOWN",
                    sameAsInput ? null : suggested,
                    java.util.List.copyOf(warnings), msg, responseJson,
                    java.util.List.copyOf(candidates));
        }

        // No match + no candidates — NOT_FOUND.
        return new AddressValidationResult(false, "NOT_FOUND", "UNKNOWN", null,
                java.util.List.copyOf(warnings),
                nonBlank(description, "USPS couldn't find this address."),
                responseJson, java.util.List.copyOf(candidates));
    }

    /** Read a SERA address object into our {@link AddressToValidate} record.
     *  Missing fields fall back to the input (so a partial echo doesn't
     *  erase the operator's entry). Returns null when the node is empty. */
    private static AddressToValidate readSeraAddress(JsonNode node, AddressToValidate input) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) return null;
        return new AddressToValidate(
                node.path("name").asText(input == null ? null : input.name()),
                node.path("company_name").asText(input == null ? null : input.company()),
                node.path("address_line1").asText(input == null ? null : input.addressLine1()),
                node.path("address_line2").asText(input == null ? null : input.addressLine2()),
                node.path("address_line3").asText(input == null ? null : input.addressLine3()),
                node.path("city").asText(input == null ? null : input.city()),
                node.path("state_province").asText(input == null ? null : input.state()),
                node.path("postal_code").asText(input == null ? null : input.postalCode()),
                node.path("country_code").asText(input == null ? null : input.countryCode()));
    }

    /** Field-level equality for the SERA matched_address echo. Case-
     *  insensitive + trim-tolerant on the comparable fields; nulls equal. */
    private static boolean seraAddressesEqual(AddressToValidate a, AddressToValidate b) {
        if (a == null || b == null) return false;
        return eqCI(a.addressLine1(), b.addressLine1())
                && eqCI(a.addressLine2(), b.addressLine2())
                && eqCI(a.city(), b.city())
                && eqCI(a.state(), b.state())
                && eqCI(a.postalCode(), b.postalCode())
                && eqCI(a.countryCode(), b.countryCode());
    }

    private static boolean eqCI(String a, String b) {
        String ta = a == null ? "" : a.trim();
        String tb = b == null ? "" : b.trim();
        return ta.equalsIgnoreCase(tb);
    }


    /**
     * USPS pickup. PR-T5 (audit §5.3) — SERA-flavor accounts hit
     * {@code POST /sera/v1/pickups} with Bearer auth + {@code Idempotency-Key};
     * SWSIM-flavor accounts keep the pre-existing SOAP {@code SchedulePickup}
     * path.
     *
     * <p>{@code -local-*} authenticators short-circuit to NOT_SUPPORTED on
     * both flavors.
     */
    @Override
    public PickupResult schedulePickup(PickupRequest request, String accessToken, String environment) {
        if (isSeraFlavor()) {
            return schedulePickupSera(request, accessToken, environment);
        }
        return schedulePickupSwsim(request, accessToken, environment);
    }

    /** SWSIM {@code SchedulePickup} — legacy SOAP path, unchanged from the
     *  pre-SERA behaviour. */
    PickupResult schedulePickupSwsim(PickupRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("USPS", null, null, null, null, "NOT_SUPPORTED",
                    "USPS pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildSchedulePickupEnvelope(request, accessToken);
        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/SchedulePickup\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseSchedulePickupResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps SchedulePickup rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), fault);
            return new PickupResult("USPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "SWSIM SchedulePickup rejected: " + fault,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SchedulePickup failed: {}", ex.getMessage());
            return new PickupResult("USPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "SWSIM SchedulePickup call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * SERA {@code POST /sera/v1/pickups} — books a USPS pickup via the REST
     * API. Request body carries {@code pickup_address} + {@code carrier} +
     * {@code pickup_window.start_at/end_at} (ISO-8601) + optional
     * {@code pickup_instructions}. The response's {@code pickup_id} UUID
     * is the handle future cancels key off (see {@link #cancelPickupSera}).
     *
     * <p>Client-side guards (fail fast, no round-trip) cover the two
     * USPS-pickup rules that are statically checkable: 800103 (Sunday) +
     * 800105 (not the next business day). Holidays (800104) + ineligibility
     * (800100-800102, 800106) need the server round-trip — see
     * {@link StampsSeraErrorCode#isPickup()}.
     *
     * <p>{@code Idempotency-Key} minted via
     * {@link com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys#forStampsPickup}
     * keyed on (accountNumber-or-shipperAddr, pickupDate) so a crash-replay
     * books the same pickup under the same key.
     */
    PickupResult schedulePickupSera(PickupRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("USPS", null, null, null, null, "NOT_SUPPORTED",
                    "USPS via Stamps.com (SERA) pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        if (request == null || request.pickupDate() == null) {
            throw new IllegalArgumentException(
                    "USPS SERA pickup requires a non-null pickupDate on PickupRequest.");
        }
        // Client-side guards for 800103 (USPS pickups prohibited on Sundays)
        // and 800105 (USPS pickup must be next business day). Fail fast with
        // an operator-friendly message instead of burning a server round-trip.
        if (request.pickupDate().getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException(
                    "USPS pickups aren't offered on Sundays — pick a weekday (received "
                            + request.pickupDate() + ").");
        }
        java.time.LocalDate expectedNext = nextBusinessDay(seraPickupToday());
        if (!request.pickupDate().equals(expectedNext)) {
            throw new IllegalArgumentException(
                    "USPS requires the pickup to be scheduled for the next business day ("
                            + expectedNext + "); got " + request.pickupDate() + ".");
        }

        String baseUrl = seraApiBaseUrl(environment);
        String body = buildSeraPickupBody(request);
        // Scope the Idempotency-Key on accountNumber when present, else fall
        // back to the shipper's address line so a bare-DTO caller still
        // dedupes stably.
        String scope = StringUtils.hasText(request.accountNumber())
                ? request.accountNumber()
                : (request.address() != null && StringUtils.hasText(request.address().addressLine1())
                        ? request.address().addressLine1()
                        : "unscoped");
        String idemKey = com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys
                .forStampsPickup(scope, request.pickupDate());
        try {
            String response = executeSeraPickupPost(baseUrl + "/pickups", body, accessToken, idemKey);
            return parseSeraPickupResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            return handleSeraPickupHttpError(request, ex);
        } catch (Exception ex) {
            log.warn("Stamps SERA /pickups call failed: {}", ex.getMessage());
            return new PickupResult("USPS", null, request.pickupDate(),
                    request.pickupWindowStart(), request.pickupWindowEnd(),
                    "ERROR",
                    "SERA pickup call failed: " + ex.getMessage(), null);
        }
    }

    /** Seam for tests — the raw POST so subclasses can inject canned
     *  responses / exceptions without a MockWebServer. Production
     *  implementation hits SERA's REST endpoint. */
    String executeSeraPickupPost(String url, String body, String accessToken, String idempotencyKey) {
        return HttpClients.newBuilder().baseUrl(url).build()
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** Seam for tests — the raw DELETE on {@code /sera/v1/pickups/{id}}.
     *  Subclasses can return a stubbed ResponseEntity or throw a mocked
     *  {@link org.springframework.web.client.RestClientResponseException}
     *  without a live HTTP transport. */
    org.springframework.http.ResponseEntity<String> executeSeraPickupDelete(String url, String accessToken) {
        return HttpClients.newBuilder().baseUrl(url).build()
                .delete()
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .toEntity(String.class);
    }

    /** Map a SERA pickup HTTP error into a PickupResult. Package-private so
     *  tests can drive the branch without a live transport. */
    PickupResult handleSeraPickupHttpError(PickupRequest request,
                                            org.springframework.web.client.RestClientResponseException ex) {
        StampsSeraErrorCode.SeraError seraErr = parseSeraError(ex.getResponseBodyAsString());
        String msg;
        if (seraErr.code() != StampsSeraErrorCode.UNKNOWN && seraErr.code().isPickup()) {
            // 800100-800106 — pickup-flow errors go verbatim to the
            // operator; retrying won't help without caller input.
            msg = "SERA pickup rejected: " + seraErr.describe();
        } else {
            msg = "SERA pickup rejected (HTTP " + ex.getStatusCode().value() + "): " + seraErr.message();
        }
        log.warn("Stamps SERA /pickups rejected (HTTP {}): {}",
                ex.getStatusCode().value(), seraErr.describe());
        return new PickupResult("USPS", null, request.pickupDate(),
                request.pickupWindowStart(), request.pickupWindowEnd(),
                "ERROR", msg, ex.getResponseBodyAsString());
    }

    /** Build the SERA {@code POST /sera/v1/pickups} JSON body. Uses the
     *  {@code pickup_address + carrier} form; the {@code label_ids} form
     *  would require threading tracking numbers through the PickupRequest
     *  record. */
    String buildSeraPickupBody(PickupRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        // carrier — SERA accepts "usps" (lower-case) for the Stamps-powered
        // USPS pickup channel; mirrors the service_type family prefix.
        body.put("carrier", "usps");
        CarrierConnector.AddressToValidate a = req.address();
        if (a != null) {
            body.put("pickup_address", buildSeraAddress(
                    req.contactName(), null,
                    a.addressLine1(), a.addressLine2(), null,
                    a.city(), a.state(), a.postalCode(), a.countryCode(),
                    req.contactPhone(), null, null));
        }
        // pickup_window — ISO-8601 timestamps per SERA spec. When the caller
        // didn't pin a window, default to 09:00-17:00 UTC on pickupDate so
        // the body is still well-formed.
        Map<String, Object> window = new LinkedHashMap<>();
        java.time.LocalTime start = req.pickupWindowStart() != null
                ? req.pickupWindowStart() : java.time.LocalTime.of(9, 0);
        java.time.LocalTime end = req.pickupWindowEnd() != null
                ? req.pickupWindowEnd() : java.time.LocalTime.of(17, 0);
        window.put("start_at", OffsetDateTime.of(req.pickupDate(), start, ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        window.put("end_at", OffsetDateTime.of(req.pickupDate(), end, ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        body.put("pickup_window", window);
        if (StringUtils.hasText(req.specialInstructions())) {
            body.put("pickup_instructions", req.specialInstructions());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise SERA pickup body: " + ex.getMessage(), ex);
        }
    }

    /** Parse the SERA pickup create response. Success carries
     *  {@code pickup_id} (UUID) + echoed {@code pickup_window} +
     *  {@code number_of_items_in_pickup} + {@code estimated_cost}. Missing
     *  {@code pickup_id} on a 2xx is an unexpected server shape — surface
     *  as ERROR so the operator rebooks. */
    PickupResult parseSeraPickupResponse(PickupRequest req, String responseJson) {
        if (!StringUtils.hasText(responseJson)) {
            return new PickupResult("USPS", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "SERA returned an empty /pickups response.", null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            String pickupId = j.path("pickup_id").asText(null);
            if (!StringUtils.hasText(pickupId)) {
                return new PickupResult("USPS", null,
                        req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                        "ERROR",
                        "SERA /pickups response missing pickup_id.", responseJson);
            }
            return new PickupResult("USPS", pickupId,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "SCHEDULED",
                    "SERA confirmed pickup — " + pickupId, responseJson);
        } catch (Exception ex) {
            return new PickupResult("USPS", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "SERA /pickups response unparseable: " + ex.getMessage(), responseJson);
        }
    }

    /**
     * SERA {@code DELETE /sera/v1/pickups/{pickup_id}} — cancels a pickup
     * SERA previously booked. No body; auth header only. 200/204 on success,
     * 404 when the id is unknown (likely already cancelled or expired) —
     * treated as ALREADY_CANCELLED, same idempotent semantics as
     * {@link #voidShipmentSera}'s 404 branch.
     */
    PickupResult cancelPickupSera(String pickupId, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new PickupResult("USPS", pickupId, null, null, null,
                    "NOT_SUPPORTED",
                    "USPS via Stamps.com (SERA) cancel-pickup needs live credentials; the account is on a fallback token.",
                    null);
        }
        if (!StringUtils.hasText(pickupId)) {
            return new PickupResult("USPS", null, null, null, null, "ERROR",
                    "SERA cancel-pickup requires a non-blank pickup_id.", null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        try {
            org.springframework.http.ResponseEntity<String> response =
                    executeSeraPickupDelete(baseUrl + "/pickups/" + pickupId, accessToken);
            boolean ok = response.getStatusCode().is2xxSuccessful();
            return new PickupResult("USPS", pickupId, null, null, null,
                    ok ? "CANCELLED" : "ERROR",
                    ok ? "SERA confirmed pickup cancellation for " + pickupId + "."
                            : "SERA cancel-pickup rejected: HTTP " + response.getStatusCode().value(),
                    response.getBody());
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404) {
                // Idempotent semantics — unknown pickup_id either expired or
                // was already cancelled; treat as success so a retry doesn't
                // leave the caller stuck in a loop.
                log.info("Stamps SERA cancel-pickup: pickup_id {} not found (already cancelled?) — returning ALREADY_CANCELLED.",
                        pickupId);
                return new PickupResult("USPS", pickupId, null, null, null,
                        "ALREADY_CANCELLED",
                        "SERA reported pickup_id " + pickupId + " as not found — treating as already cancelled.",
                        ex.getResponseBodyAsString());
            }
            StampsSeraErrorCode.SeraError err = parseSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA cancel-pickup rejected for {} (HTTP {}): {}",
                    pickupId, status, err.describe());
            return new PickupResult("USPS", pickupId, null, null, null,
                    "ERROR",
                    "SERA cancel-pickup rejected (HTTP " + status + "): " + err.message(),
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA cancel-pickup call failed for {}: {}", pickupId, ex.getMessage());
            return new PickupResult("USPS", pickupId, null, null, null,
                    "ERROR",
                    "SERA cancel-pickup call failed: " + ex.getMessage(), null);
        }
    }

    /** Seam for tests — "today" the next-business-day guard compares against.
     *  Prod uses UTC (SERA's own clock). Tests can override via a subclass
     *  or by passing a pickupDate equal to the real next business day. */
    java.time.LocalDate seraPickupToday() {
        return java.time.LocalDate.now(ZoneOffset.UTC);
    }

    /** Mon-Fri → next day. Fri → Mon. Sat → Mon. Sun → Mon.
     *  Holidays aren't modelled — those bounce via 800104 server-side. */
    static java.time.LocalDate nextBusinessDay(java.time.LocalDate base) {
        java.time.LocalDate d = base.plusDays(1);
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Build the SWSIM SchedulePickup envelope. */
    String buildSchedulePickupEnvelope(PickupRequest req, String authenticator) {
        StringBuilder xml = new StringBuilder(1536);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<SchedulePickup xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<PickupDate>")
                .append(req.pickupDate() == null ? "" : req.pickupDate().toString())
                .append("</PickupDate>");
        xml.append("<PackageCount>").append(Math.max(1, req.packageCount())).append("</PackageCount>");
        // SWSIM PackageLocation values: MailRoom | Other | FrontDoor | BackDoor | KnockOnDoor | InMailBox
        xml.append("<PackageLocation>Other</PackageLocation>");
        if (StringUtils.hasText(req.specialInstructions())) {
            xml.append("<SpecialInstructions>")
                    .append(xmlEscape(req.specialInstructions()))
                    .append("</SpecialInstructions>");
        }

        // PickupAddress block.
        CarrierConnector.AddressToValidate a = req.address();
        xml.append("<PickupAddress>");
        if (StringUtils.hasText(req.contactName())) {
            xml.append("<FullName>").append(xmlEscape(req.contactName())).append("</FullName>");
        }
        if (a != null) {
            if (StringUtils.hasText(a.addressLine1())) {
                xml.append("<Address1>").append(xmlEscape(a.addressLine1())).append("</Address1>");
            }
            if (StringUtils.hasText(a.addressLine2())) {
                xml.append("<Address2>").append(xmlEscape(a.addressLine2())).append("</Address2>");
            }
            if (StringUtils.hasText(a.city())) {
                xml.append("<City>").append(xmlEscape(a.city())).append("</City>");
            }
            if (StringUtils.hasText(a.state())) {
                xml.append("<State>").append(xmlEscape(a.state())).append("</State>");
            }
            if (StringUtils.hasText(a.postalCode())) {
                xml.append("<ZIPCode>").append(xmlEscape(a.postalCode())).append("</ZIPCode>");
            }
        }
        if (StringUtils.hasText(req.contactPhone())) {
            xml.append("<PhoneNumber>").append(xmlEscape(req.contactPhone())).append("</PhoneNumber>");
        }
        xml.append("</PickupAddress>");

        // TotalWeight in ounces — USPS convention.
        java.math.BigDecimal ozTotal = com.multiship.backend.util.UnitConverter
                .toOunces(req.totalWeight(), req.weightUnit());
        xml.append("<EstimatedWeight>")
                .append(ozTotal == null ? "0" : ozTotal.toPlainString())
                .append("</EstimatedWeight>");

        xml.append("</SchedulePickup>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse the SWSIM SchedulePickup response. Success carries
     * {@code ConfirmationNumber}; absence of {@code faultstring}
     * counts as success even when the number is absent.
     */
    PickupResult parseSchedulePickupResponse(PickupRequest req, String responseXml) {
        if (!StringUtils.hasText(responseXml)) {
            return new PickupResult("USPS", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "SWSIM returned an empty SchedulePickup response.", null);
        }
        String fault = extractElement(responseXml, "faultstring");
        if (StringUtils.hasText(fault)) {
            return new PickupResult("USPS", null,
                    req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                    "ERROR",
                    "USPS pickup rejected: " + fault, responseXml);
        }
        String number = extractElement(responseXml, "ConfirmationNumber");
        String status = StringUtils.hasText(number) ? "SCHEDULED" : "ERROR";
        String message = StringUtils.hasText(number)
                ? "USPS confirmed pickup — " + number
                : "SWSIM response missing ConfirmationNumber.";
        return new PickupResult("USPS", number,
                req.pickupDate(), req.pickupWindowStart(), req.pickupWindowEnd(),
                status, message, responseXml);
    }

    /**
     * SWSIM {@code CreateScanForm} — end-of-day USPS SCAN Form generation.
     * The SCAN Form (USPS Form 5630) carries a single master barcode that
     * driver scans; USPS then acknowledges every included Priority Mail /
     * Ground Advantage / Priority Mail Express label at once instead of
     * scanning each individually. Sprint 34.
     *
     * <p>{@code -local-*} authenticators short-circuit to NOT_SUPPORTED.
     */
    @Override
    public CloseOutResult closeOutDay(CloseOutRequest request, String accessToken, String environment) {
        if (isSeraFlavor()) {
            return closeOutDaySera(request, accessToken, environment);
        }
        return closeOutDaySwsim(request, accessToken, environment);
    }

    /** SWSIM {@code CreateScanForm} path — legacy behaviour, unchanged. */
    CloseOutResult closeOutDaySwsim(CloseOutRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new CloseOutResult("USPS", null, null, null, 0, "NOT_SUPPORTED",
                    "USPS SCAN Form needs live credentials; the account is on a fallback token.",
                    null);
        }
        java.util.List<String> tracking = request.trackingNumbers();
        if (tracking == null || tracking.isEmpty()) {
            return new CloseOutResult("USPS", null, null, null, 0, "ERROR",
                    "USPS SCAN Form requires at least one tracking number.", null);
        }
        String swsimUrl = isSandbox(environment)
                ? carrierProperties.getStamps().getSandboxUrl()
                : carrierProperties.getStamps().getApiBaseUrl();
        String soap = buildCreateScanFormEnvelope(request, accessToken);
        try {
            String response = HttpClients.newBuilder().baseUrl(swsimUrl).build().post()
                    .contentType(MediaType.parseMediaType("text/xml; charset=utf-8"))
                    .header("SOAPAction", "\"" + SWSIM_NAMESPACE + "/CreateScanForm\"")
                    .body(soap)
                    .retrieve()
                    .body(String.class);
            return parseCreateScanFormResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String fault = extractSoapFault(ex.getResponseBodyAsString());
            log.warn("Stamps CreateScanForm rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), fault);
            return new CloseOutResult("USPS", null, null, null, tracking.size(), "ERROR",
                    "SWSIM CreateScanForm rejected: " + fault,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps CreateScanForm failed: {}", ex.getMessage());
            return new CloseOutResult("USPS", null, null, null, tracking.size(), "ERROR",
                    "SWSIM CreateScanForm call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * SERA {@code POST /sera/v1/manifests} — end-of-day manifest. SERA
     * supports two shapes: by list of {@code label_ids} (preferred, exact
     * label targeting) or by carrier + ship_date (broader, catches any
     * unmanifested labels for that day). We use the label_ids path when
     * we can resolve them from {@code label_package.carrier_label_ref}
     * (the V44 column) and fall back to carrier + ship_date otherwise —
     * this keeps legacy pre-V44 labels manifestable via the same
     * endpoint. Response shape:
     * <pre>
     * {
     *   "manifest_id": "uuid",
     *   "carrier": "usps",
     *   "ship_date": "2026-09-08",
     *   "number_of_items_in_manifest": 12,
     *   "labels": [{"href": "https://..."}]
     * }
     * </pre>
     * {@code labels[0].href} is the manifest form (PDF or PNG); we save
     * it into {@link CloseOutResult#manifestPdfUrl()}.
     */
    CloseOutResult closeOutDaySera(CloseOutRequest request, String accessToken, String environment) {
        if (!StringUtils.hasText(accessToken) || accessToken.contains("-local-")) {
            return new CloseOutResult("USPS", null, null, null, 0, "NOT_SUPPORTED",
                    "USPS via Stamps.com (SERA) manifest needs live credentials; the account is on a fallback token.",
                    null);
        }
        java.util.List<String> tracking = request.trackingNumbers();
        if (tracking == null || tracking.isEmpty()) {
            return new CloseOutResult("USPS", null, null, null, 0, "ERROR",
                    "SERA manifest requires at least one tracking number (to resolve label_ids).",
                    null);
        }
        String baseUrl = seraApiBaseUrl(environment);
        Map<String, Object> body = buildSeraManifestBody(request);
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize SERA manifest body", ex);
        }
        try {
            // Audit: Idempotency-Key required on SERA POSTs. PR-T2 —
            // deterministic key scoped by (accountNumber, closeDate) so
            // a double-click / crash-replay re-manifests the SAME batch
            // under the SAME key instead of generating a duplicate
            // manifest. Falls back to a random UUID when the account
            // identifier is blank (shouldn't happen in practice).
            java.time.LocalDate manifestDate = request.closeDate() != null
                    ? request.closeDate()
                    : com.multiship.backend.util.LabelDates.today(null);
            String manifestKey = StringUtils.hasText(request.accountNumber())
                    ? com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys
                            .forStampsManifest(request.accountNumber(), manifestDate)
                    : java.util.UUID.randomUUID().toString();
            String response = HttpClients.newBuilder().baseUrl(baseUrl + "/manifests").build()
                    .post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", manifestKey)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);
            return parseSeraManifestResponse(request, response);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String err = extractSeraError(ex.getResponseBodyAsString());
            log.warn("Stamps SERA /manifests rejected (HTTP {}): {}", status, err);
            return new CloseOutResult("USPS", null, null, null, tracking.size(), "ERROR",
                    "SERA manifest rejected (HTTP " + status + "): " + err,
                    ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.warn("Stamps SERA /manifests call failed: {}", ex.getMessage());
            return new CloseOutResult("USPS", null, null, null, tracking.size(), "ERROR",
                    "SERA manifest call failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Build the JSON body for SERA {@code POST /sera/v1/manifests}. Prefers
     * the by-label_ids path when every tracking has a persisted
     * {@code carrier_label_ref}; falls back to the by-carrier+ship_date
     * shape when any tracking is missing its label_id (legacy labels).
     */
    Map<String, Object> buildSeraManifestBody(CloseOutRequest request) {
        java.util.List<String> tracking = request.trackingNumbers();
        java.util.List<String> labelIds = new java.util.ArrayList<>();
        if (labelPackageRepository != null) {
            for (String t : tracking) {
                String id = resolveSeraLabelId(t);
                if (StringUtils.hasText(id)) labelIds.add(id);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> labelOpts = new LinkedHashMap<>();
        labelOpts.put("label_format", "pdf");
        labelOpts.put("print_instructions", false);
        // If we got a label_id for every tracking, use the exact-target path.
        // Otherwise fall back to carrier + ship_date so legacy pre-V44 labels
        // still manifest (SERA sweeps every unmanifested USPS label for the
        // day at the shipper address).
        if (labelIds.size() == tracking.size() && !labelIds.isEmpty()) {
            body.put("label_ids", labelIds);
        } else {
            log.info("Stamps SERA manifest: {} of {} tracking numbers had a persisted label_id; "
                    + "falling back to by-carrier+ship_date manifest.",
                    labelIds.size(), tracking.size());
            body.put("carrier", "usps");
            String shipDate = request.closeDate() != null
                    ? request.closeDate().toString()
                    : com.multiship.backend.util.LabelDates.today(null).toString();
            body.put("ship_date", shipDate);
            if (request.address() != null) {
                body.put("from_address", buildSeraAddress(
                        request.address().name(), null,
                        request.address().addressLine1(),
                        request.address().addressLine2(),
                        request.address().addressLine3(),
                        request.address().city(),
                        request.address().state(),
                        request.address().postalCode(),
                        request.address().countryCode(),
                        null, null, null));
            }
        }
        body.put("label_options", labelOpts);
        return body;
    }

    /**
     * Parse SERA's manifest response. Missing {@code manifest_id} counts as
     * ERROR (mirrors parseSeraCreateLabelResponse's fault-first pattern).
     */
    CloseOutResult parseSeraManifestResponse(CloseOutRequest request, String responseJson) {
        int count = request.trackingNumbers() == null ? 0 : request.trackingNumbers().size();
        if (!StringUtils.hasText(responseJson)) {
            return new CloseOutResult("USPS", null, null, null, count, "ERROR",
                    "SERA /manifests returned an empty response.", null);
        }
        try {
            JsonNode j = objectMapper.readTree(responseJson);
            String manifestId = j.path("manifest_id").asText(null);
            if (!StringUtils.hasText(manifestId)) {
                return new CloseOutResult("USPS", null, null, null, count, "ERROR",
                        "SERA /manifests returned no manifest_id: " + extractSeraError(responseJson),
                        responseJson);
            }
            String manifestUrl = null;
            JsonNode labels = j.path("labels");
            if (labels.isArray() && labels.size() > 0) {
                manifestUrl = labels.get(0).path("href").asText(null);
            }
            int items = j.path("number_of_items_in_manifest").asInt(count);
            return new CloseOutResult("USPS", manifestId, manifestUrl, null, items, "MANIFESTED",
                    "SERA manifest " + manifestId + " covers " + items + " shipment(s)",
                    responseJson);
        } catch (Exception ex) {
            return new CloseOutResult("USPS", null, null, null, count, "ERROR",
                    "SERA /manifests returned malformed body: " + ex.getMessage(), responseJson);
        }
    }

    /** Build the SWSIM CreateScanForm envelope. */
    String buildCreateScanFormEnvelope(CloseOutRequest req, String authenticator) {
        StringBuilder xml = new StringBuilder(1536);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<CreateScanForm xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        xml.append("<TransactionId>").append(xmlEscape(
                "eod-" + java.util.UUID.randomUUID())).append("</TransactionId>");

        // TrackingNumbers block — one <TrackingNumber> per label.
        xml.append("<StampsTxIDs>");
        for (String t : req.trackingNumbers()) {
            xml.append("<string>").append(xmlEscape(t)).append("</string>");
        }
        xml.append("</StampsTxIDs>");

        CarrierConnector.AddressToValidate a = req.address();
        if (a != null) {
            xml.append("<FromAddress>");
            if (StringUtils.hasText(a.name())) {
                xml.append("<FullName>").append(xmlEscape(a.name())).append("</FullName>");
            }
            if (StringUtils.hasText(a.addressLine1())) {
                xml.append("<Address1>").append(xmlEscape(a.addressLine1())).append("</Address1>");
            }
            if (StringUtils.hasText(a.city())) {
                xml.append("<City>").append(xmlEscape(a.city())).append("</City>");
            }
            if (StringUtils.hasText(a.state())) {
                xml.append("<State>").append(xmlEscape(a.state())).append("</State>");
            }
            if (StringUtils.hasText(a.postalCode())) {
                xml.append("<ZIPCode>").append(xmlEscape(a.postalCode())).append("</ZIPCode>");
            }
            xml.append("</FromAddress>");
        }

        xml.append("<ImageType>Pdf</ImageType>");
        xml.append("<PrintInstructions>false</PrintInstructions>");

        xml.append("</CreateScanForm>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    /**
     * Parse the SWSIM CreateScanForm response. Success carries
     * {@code ScanFormUrl} (or {@code ScanFormBase64}) + a
     * {@code SubmissionID} identifier.
     */
    CloseOutResult parseCreateScanFormResponse(CloseOutRequest req, String responseXml) {
        int count = req.trackingNumbers() == null ? 0 : req.trackingNumbers().size();
        if (!StringUtils.hasText(responseXml)) {
            return new CloseOutResult("USPS", null, null, null, count, "ERROR",
                    "SWSIM returned an empty CreateScanForm response.", null);
        }
        String fault = extractElement(responseXml, "faultstring");
        if (StringUtils.hasText(fault)) {
            return new CloseOutResult("USPS", null, null, null, count, "ERROR",
                    "USPS SCAN Form rejected: " + fault, responseXml);
        }
        String submissionId = extractElement(responseXml, "SubmissionID");
        String scanFormUrl = extractElement(responseXml, "ScanFormUrl");
        String pdfBase64 = extractElement(responseXml, "ScanFormBase64");
        String status = StringUtils.hasText(submissionId) ? "MANIFESTED" : "ERROR";
        String message = StringUtils.hasText(submissionId)
                ? "USPS SCAN Form " + submissionId + " covers " + count + " shipment(s)"
                : "SWSIM response missing SubmissionID.";
        return new CloseOutResult("USPS", submissionId, scanFormUrl, pdfBase64, count,
                status, message, responseXml);
    }

    /**
     * SWSIM delivery notification webhook — HMAC-SHA256(body, secret) in
     * the {@code X-Stamps-Signature} header. SWSIM pushes delivery events
     * to a URL registered with the account.
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload,
                                           java.util.Map<String, String> headers,
                                           String secret) {
        String provided = pickWebhookHeader(headers, "X-Stamps-Signature");
        String expected = WebhookHmacUtil.hmacSha256Hex(rawPayload, secret);
        return provided != null && expected != null
                && WebhookHmacUtil.constantTimeEquals(provided, expected);
    }

    /**
     * Parse a SWSIM delivery notification webhook. SWSIM pushes JSON:
     * <pre>
     * {
     *   "TrackingNumber": "9400...",
     *   "EventType": "Delivered",
     *   "EventTimestamp": "2026-07-26T14:30:00",
     *   "City": "Louisville",
     *   "State": "KY",
     *   "Country": "US"
     * }
     * </pre>
     */
    @Override
    public TrackingWebhookEvent parseWebhookEvent(String rawPayload,
                                                   java.util.Map<String, String> headers) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(
                    java.util.Optional.ofNullable(rawPayload).orElse("{}"));
            String tracking = root.path("TrackingNumber").asText(null);
            if (!StringUtils.hasText(tracking)) return null;
            String eventType = root.path("EventType").asText(null);
            LocalDateTime occurred = parseSwsimTimestamp(root.path("EventTimestamp").asText(null));
            String location = buildSwsimLocation(
                    root.path("City").asText(null),
                    root.path("State").asText(null),
                    root.path("Country").asText(null));
            boolean delivered = "Delivered".equalsIgnoreCase(eventType);
            return new TrackingWebhookEvent(tracking, eventType, null, occurred,
                    location, delivered, eventType == null ? "" : eventType);
        } catch (Exception ex) {
            log.warn("Stamps webhook parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String pickWebhookHeader(java.util.Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (var e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Build the SWSIM GetRates SOAP envelope. No {@code ServiceType} — omitting
     * it asks SWSIM for the full rate ladder. Country only when non-US (SWSIM
     * treats absent Country as US and errors when both are set).
     */
    String buildGetRatesEnvelope(ShipmentRequestDTO request, String authenticator) {
        // BC overload — takes piece 1 (matches pre-Sprint-48 behaviour).
        return buildGetRatesEnvelope(request, request.effectivePackages().get(0), authenticator);
    }

    /**
     * Build the SWSIM GetRates SOAP envelope for a specific package. Sprint 48
     * B3 — multi-package rate-shopping loops N calls, one per package, so this
     * method now takes an explicit package rather than pulling piece 1.
     */
    String buildGetRatesEnvelope(ShipmentRequestDTO request,
                                 com.multiship.backend.dto.PackageDetailDTO pkg,
                                 String authenticator) {
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
        xml.append("<WeightOz>").append(xmlEscape(weightInOz(pkg))).append("</WeightOz>");
        xml.append("<PackageType>")
                .append(xmlEscape(nonBlank(
                        nonBlank(pkg.getPackageType(), request.getPackageType()), "Package")))
                .append("</PackageType>");
        // F6-E — SWSIM ShipDate follows the shipper's local calendar day;
        // USPS/Endicia service-selection rules key off it (Same-Day, next-
        // day cutoffs), so a UTC-1-day skew silently mis-quotes.
        xml.append("<ShipDate>")
                .append(com.multiship.backend.util.LabelDates.today(
                        request.getShipperTimezone(), request.getShipDateOverride()))
                .append("</ShipDate>");
        // Declared value: prefer per-package, else shipment-level.
        java.math.BigDecimal declared = pkg.getDeclaredValue() != null
                ? pkg.getDeclaredValue() : request.getDeclaredValue();
        if (declared != null) {
            xml.append("<DeclaredValue>")
                    .append(xmlEscape(declared.toPlainString()))
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
            if (totalAmount == null) {
                // Pre-fix this skipped silently — operator saw N-1 rates
                // when SWSIM returned N with no way to trace the missing
                // one. Log the offending service + raw Amount so ops can
                // reproduce; we still skip because a rate without a
                // parseable price can't be surfaced (customers rely on
                // total for rate-shop comparison).
                log.warn("Stamps GetRates: dropping unparseable Amount '{}' for service {} — "
                        + "rate omitted from response.", amount, serviceCode);
                continue;
            }

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
    /**
     * Build a {@code CreateIndicium} envelope for ONE specific package on
     * the shipment. Sprint 28 refactored the signature to take a package
     * explicitly (SWSIM is single-package per SOAP call); Sprint 29's
     * {@link #createShipment} loops effectivePackages() and issues one
     * call per package, aggregating tracking numbers into the returned
     * {@code ShipmentResult}.
     *
     * <p>Reference numbers get a {@code -pN} suffix for multi-package
     * shipments so SWSIM's IntegratorTxID (which must be unique per call)
     * doesn't collide across N calls on the same shipment.
     */
    String buildCreateIndiciumEnvelope(ShipmentRequestDTO request, String authenticator) {
        return buildCreateIndiciumEnvelope(request, request.effectivePackages().get(0), 1,
                request.effectivePackages().size(), authenticator);
    }

    private String buildCreateIndiciumEnvelope(ShipmentRequestDTO request,
                                                com.multiship.backend.dto.PackageDetailDTO packageDetail,
                                                int packageIndex, int packageTotal,
                                                String authenticator) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">");
        xml.append("<soap:Body>");
        xml.append("<CreateIndicium xmlns=\"").append(SWSIM_NAMESPACE).append("\">");
        xml.append("<Authenticator>").append(xmlEscape(authenticator)).append("</Authenticator>");
        String baseTxId = nonBlank(request.getReferenceNumber(), "TX-" + System.currentTimeMillis());
        // Multi-package: suffix -pN so SWSIM's per-call uniqueness holds.
        String txId = packageTotal > 1 ? baseTxId + "-p" + packageIndex : baseTxId;
        xml.append("<IntegratorTxID>")
                .append(xmlEscape(txId))
                .append("</IntegratorTxID>");

        // Rate: the class of service + package + weight. SWSIM re-validates
        // this against its own rate engine, so mismatches (weight over the
        // service's max) fail here before the label is printed.
        com.multiship.backend.dto.PackageDetailDTO firstPkg = packageDetail;
        String weightOz = weightInOz(firstPkg);
        xml.append("<Rate>");
        appendServiceRate(xml, request, firstPkg, weightOz, packageIndex, packageTotal);
        xml.append("</Rate>");

        // From/To are separate blocks; addresses appear twice (once inside
        // Rate, once here) — that's the SWSIM shape.
        xml.append("<From>");
        appendAddress(xml, "FullName", request.getShipperName(),
                request.getShipperCompany(), request.getShipperEmail(),
                request.getShipperAddressLine1(), request.getShipperAddressLine2(), null,
                request.getShipperCity(), request.getShipperState(),
                request.getShipperPostalCode(), request.getShipperCountryCode(),
                request.getShipperPhone());
        xml.append("</From>");
        xml.append("<To>");
        appendAddress(xml, "FullName", request.getRecipientName(),
                request.getRecipientCompany(), request.getRecipientEmail(),
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

        // Per-account/per-shipment label file format override (request →
        // account → SWSIM's own default). Pre-existing behavior omitted
        // this element entirely, so a null/blank override preserves that
        // exact behavior instead of forcing a specific format.
        if (StringUtils.hasText(request.getLabelImageFormat())) {
            String raw = request.getLabelImageFormat().trim().toUpperCase(java.util.Locale.ROOT);
            // SWSIM's ImageType enum is PascalCase (Pdf, Png, Gif, Jpg) —
            // capitalize just the first letter of the validated uppercase value.
            String imageType = raw.substring(0, 1) + raw.substring(1).toLowerCase(java.util.Locale.ROOT);
            xml.append("<ImageType>").append(imageType).append("</ImageType>");
        }

        // PR #543 — SWSIM prints <CustomerID> on the label's "Reference"
        // slot. USPS has no separate PO / DEPT fields, so concat both
        // values with prefixes (matches the DHL treatment above). Empty
        // string omitted so the label doesn't print a literal "PO= DEPT=".
        StringBuilder ref = new StringBuilder();
        if (StringUtils.hasText(request.getPoNumber())) {
            ref.append("PO=").append(request.getPoNumber());
        }
        if (StringUtils.hasText(request.getDepartmentNumber())) {
            if (ref.length() > 0) ref.append(' ');
            ref.append("DEPT=").append(request.getDepartmentNumber());
        }
        if (ref.length() > 0) {
            xml.append("<CustomerID>").append(xmlEscape(ref.toString())).append("</CustomerID>");
        }

        xml.append("</CreateIndicium>");
        xml.append("</soap:Body>");
        xml.append("</soap:Envelope>");
        return xml.toString();
    }

    private void appendServiceRate(StringBuilder xml, ShipmentRequestDTO request,
                                    com.multiship.backend.dto.PackageDetailDTO p, String weightOz,
                                    int packageIndex, int packageTotal) {
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
        // F6-E — see the sibling ShipDate emit in buildGetRatesEnvelope.
        xml.append("<ShipDate>")
                .append(com.multiship.backend.util.LabelDates.today(
                        request.getShipperTimezone(), request.getShipDateOverride()))
                .append("</ShipDate>");
        // Sprint 48 B11 — DeclaredValue resolution:
        //   1. per-box CI-derived value (grouped from OrderCustomsItem.boxSeq)
        //   2. explicit packageDetail.declaredValue (legacy override)
        //   3. shipment-level request.declaredValue
        // SWSIM is single-piece per call; each call gets THIS box's total.
        // Invariant: declared >= sum(customs items for this box) holds by
        // construction when items are the source (they'd be equal).
        com.multiship.backend.util.DeclaredValueContextBuilder.DeclaredValueContext dvCtx =
                com.multiship.backend.util.DeclaredValueContextBuilder.build(
                        request.getIntl() != null ? request.getIntl().getCommodities() : null,
                        Math.max(packageTotal, 1),
                        request.effectivePackages(),
                        nonBlank(request.getDeclaredValueCurrency(), "USD"),
                        request.getDeclaredValue());
        int boxIdx = Math.max(0, Math.min(packageIndex - 1, dvCtx.perPackage().size() - 1));
        java.math.BigDecimal declared = null;
        if (boxIdx >= 0 && boxIdx < dvCtx.perPackage().size()) {
            java.math.BigDecimal fromItems = dvCtx.perPackage().get(boxIdx);
            if (fromItems != null && fromItems.signum() > 0) declared = fromItems;
        }
        if (declared == null && p.getDeclaredValue() != null) declared = p.getDeclaredValue();
        if (declared == null) declared = request.getDeclaredValue();
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
        // Sprint 35 — signature + insurance. SWSIM's Rate block accepts
        // <ServiceType> add-ons via boolean flags AND a separate
        // <InsuredValue> element. USPS domestic signature levels:
        //   SignatureConfirmation — anyone at address can sign (~$2.85).
        //   AdultSignatureRequired — 21+ ID at address (~$5.90).
        // NONE / null → neither flag emitted (carrier default).
        String sig = normaliseSignatureOption(request.getSignatureOption());
        if ("ADULT".equals(sig)) {
            xml.append("<AdultSignatureRequired>true</AdultSignatureRequired>");
        } else if ("INDIRECT".equals(sig) || "DIRECT".equals(sig)) {
            xml.append("<SignatureConfirmation>true</SignatureConfirmation>");
        }
        if (request.getInsuredValue() != null && request.getInsuredValue().signum() > 0) {
            xml.append("<InsuredValue>")
                    .append(xmlEscape(request.getInsuredValue().toPlainString()))
                    .append("</InsuredValue>");
        }
    }

    /** Normalise signatureOption to INDIRECT / DIRECT / ADULT; blank /
     *  unknown / NONE → null (carrier default). */
    private static String normaliseSignatureOption(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase();
        if (v.isEmpty() || "NONE".equals(v)) return null;
        return switch (v) {
            case "INDIRECT", "DIRECT", "ADULT" -> v;
            default -> null;
        };
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
    /** Backwards-compat overload without company / email. */
    private void appendAddress(StringBuilder xml, String nameField, String name,
                                String line1, String line2, String line3,
                                String city, String state, String postal, String country,
                                String phone) {
        appendAddress(xml, nameField, name, null, null,
                line1, line2, line3, city, state, postal, country, phone);
    }

    /**
     * Sprint 51 — company + email overload. SWSIM v135 accepts
     * {@code <Company>} + {@code <EmailAddress>} inside From / To
     * address blocks. Blank company or email = element omitted (matches
     * the pre-Sprint-51 pattern for optional address fields).
     */
    private void appendAddress(StringBuilder xml, String nameField, String name,
                                String company, String email,
                                String line1, String line2, String line3,
                                String city, String state, String postal, String country,
                                String phone) {
        if (StringUtils.hasText(name)) {
            xml.append("<").append(nameField).append(">")
                    .append(xmlEscape(name))
                    .append("</").append(nameField).append(">");
        }
        if (StringUtils.hasText(company)) {
            xml.append("<Company>").append(xmlEscape(company)).append("</Company>");
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
        if (StringUtils.hasText(email)) xml.append("<EmailAddress>").append(xmlEscape(email)).append("</EmailAddress>");
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
        // F6-C note: unlike UPS/FedEx/DHL, USPS via Stamps.com has NO
        // dedicated envelope field for clearanceOption (SENDER vs RECIPIENT
        // vs DDU vs DDP). USPS encodes duty payment via SERVICE CLASS
        // ("Priority Mail Intl - DDU" vs "Priority Mail Intl - DDP" are
        // distinct SWSIM ServiceType codes). So `intl.getClearanceOption()`
        // is intentionally not read here; the resolver's account-level
        // clearanceOption should ideally flow into service-code selection
        // upstream (out of scope for F6-C — see follow-up ticket if the
        // client needs USPS DDP routing).
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
                // SWSIM XML is case-sensitive and every sibling element in
                // this CustomsLine block is PascalCase (Description,
                // Quantity, Value, WeightOz, HSTariffNumber, CountryOfOrigin).
                // Pre-fix this was lowercased `<sku>` which SWSIM's parser
                // silently dropped — the SKU disappeared from the printed
                // customs form (silent data loss on international shipments).
                xml.append("<SKU>").append(xmlEscape(c.getSku())).append("</SKU>");
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

    /**
     * Total shipment weight in ounces — the unit SWSIM speaks natively.
     *
     * <p>Throws {@link IllegalArgumentException} when UnitConverter can't
     * convert the input (unrecognised weight unit, null weight, negative
     * value). Pre-fix, this silently fell back to {@code "0"} which
     * SWSIM would either quote a bogus near-free rate for OR reject with
     * a confusing error further down — a classic silent-fallback that
     * the codebase actively hunts (5-batch silent-fallback audit shipped
     * as PRs #410-#414). Failing early with a diagnosable message beats
     * shipping a fake-weight envelope every time.
     */
    private static String weightInOz(com.multiship.backend.dto.PackageDetailDTO p) {
        java.math.BigDecimal oz = com.multiship.backend.util.UnitConverter
                .toOunces(p.getWeight(), p.getWeightUnit());
        if (oz == null) {
            throw new IllegalArgumentException(
                    "Stamps.com: could not convert package weight to ounces. "
                            + "Value=" + p.getWeight() + " unit=" + p.getWeightUnit()
                            + " — provide a positive numeric weight in LB, OZ, KG, or G.");
        }
        return oz.toPlainString();
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
     *
     * <p>Fault handling: SWSIM can return HTTP 200 with a SOAP
     * {@code <faultstring>} inside the envelope (e.g. "insufficient postage",
     * "USPS service unavailable" — these don't 4xx at the transport level).
     * Pre-fix, a fault response missing a TrackingNumber silently returned a
     * ShipmentResult with null tracking/URL/PDF, which the MPS aggregator
     * treated as a successful piece — the operator saw a "created" shipment
     * with no label and no error. Every sibling parser in this class
     * (parseCancelIndiciumResponse, parseSchedulePickupResponse,
     * parseCreateScanFormResponse) checks for faultstring; createIndicium
     * was the outlier. Now aligned: a fault response throws an
     * IllegalStateException that the createShipment loop catches, routes
     * through CarrierExceptionMapper, and triggers the MPS rollback for
     * any pieces that DID succeed earlier in the batch.
     */
    private ShipmentResult parseCreateIndiciumResponse(String responseXml, ShipmentRequestDTO request) {
        String tracking = extractElement(responseXml, "TrackingNumber");
        String url = extractElement(responseXml, "URL");
        // Fault-first check: a valid label response ALWAYS has a TrackingNumber.
        // If it's missing, look for a SOAP fault and throw so the caller sees
        // a diagnosable failure instead of a null-tracking "success".
        if (!StringUtils.hasText(tracking)) {
            String fault = extractSoapFault(responseXml);
            throw new IllegalStateException(
                    "SWSIM CreateIndicium returned no TrackingNumber for order "
                            + (request == null ? "?" : request.getReferenceNumber())
                            + ". Fault: " + fault);
        }
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
        String trackingUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + tracking;
        // ETA — read from SWSIM's response. Pre-fix this was hardcoded to
        // now().plusDays(5) for EVERY service class regardless of what SWSIM
        // said, so a Priority Mail (1-3d) label and a Media Mail (2-8d)
        // label both surfaced "delivered in 5 days" on the receipt. That
        // misleads recipients and doesn't match what parseGetRatesResponse
        // reads from the same response shape (see the DeliveryDate lookup
        // ~445 lines above in parseGetRatesResponse).
        //
        // SWSIM CreateIndicium responses may or may not include
        // DeliveryDate depending on service class — falling back to null
        // when absent is deliberate: the receipt UI already renders "—"
        // for null ETA, which is honest.
        java.time.LocalDateTime estimated = parseSwsimTimestamp(
                extractElement(responseXml, "DeliveryDate"));
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
            } catch (Exception ex2) {
                log.debug("Stamps parseDateTime: unparseable timestamp '{}' (neither OffsetDateTime nor LocalDateTime matched)", value);
                return null;
            }
        }
    }
}
