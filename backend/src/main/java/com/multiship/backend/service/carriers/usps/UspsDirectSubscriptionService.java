package com.multiship.backend.service.carriers.usps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.UspsDirectSubscriptionRequest;
import com.multiship.backend.model.UspsDirectSubscription;
import com.multiship.backend.model.UspsDirectSubscription.FilterType;
import com.multiship.backend.model.UspsDirectSubscription.Status;
import com.multiship.backend.repository.UspsDirectSubscriptionRepository;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.carriers.HttpClients;
import com.multiship.backend.service.carriers.UspsDirectConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * USPS_DIRECT PR-B — CRUD for USPS Subscriptions-Tracking v3
 * subscriptions.
 *
 * <p>Wraps USPS' {@code /subscriptions-tracking/v3/subscriptions[/{id}]}
 * REST endpoint (POST / GET / DELETE) so the admin controller can manage
 * push-event subscriptions from a single admin surface. Every USPS call
 * mints a fresh platform Bearer token via {@link UspsOAuthTokenCache}
 * (from the {@code USPS_PLATFORM_CLIENT_ID / _SECRET} system-settings) —
 * missing creds throw the same {@link IllegalStateException} shape the
 * connector's boundary guards emit.
 *
 * <p>The HMAC signing secret is minted server-side (32 random bytes,
 * base64) on every create so callers never provide secret material over
 * the wire, and encryption at rest is handled by the entity's
 * {@link com.multiship.backend.config.EncryptedStringConverter} — the
 * plaintext is only ever held in the POST body USPS receives. DTO mapping
 * strips {@link UspsDirectSubscription#getSecretEncrypted()} entirely so
 * an admin GET never leaks it either.
 */
@Slf4j
@Service
public class UspsDirectSubscriptionService {

    /** USPS' documented REST path for the Subscriptions-Tracking API.
     *  Note: some USPS docs render this as {@code subscriptions-trackingv3r2};
     *  the REST path (per developers.usps.com's OpenAPI spec) is
     *  {@code /subscriptions-tracking/v3/subscriptions} — flip to the
     *  {@code v3r2} form only if a runtime 404 surfaces. */
    static final String SUBSCRIPTION_PATH = "/subscriptions-tracking/v3/subscriptions";

    /** Length of the freshly-minted HMAC secret. USPS documents "32-char
     *  minimum". SecureRandom + base64 URL-safe → 32 chars from 24 bytes. */
    static final int SECRET_BYTES = 24;

    private final UspsDirectSubscriptionRepository repository;
    private final UspsOAuthTokenCache tokenCache;
    private final SystemSettingService systemSettingService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    public UspsDirectSubscriptionService(UspsDirectSubscriptionRepository repository,
                                          UspsOAuthTokenCache tokenCache,
                                          SystemSettingService systemSettingService,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.tokenCache = tokenCache;
        this.systemSettingService = systemSettingService;
        this.objectMapper = objectMapper;
        this.secureRandom = new SecureRandom();
    }

    // ============================================================
    // Public CRUD surface — invoked by UspsDirectSubscriptionAdminController.
    // ============================================================

    /**
     * Register a new subscription with USPS + persist locally.
     *
     * <p>Fails loudly (before any HTTP call) when:
     * <ul>
     *   <li>Platform creds are missing → {@link IllegalStateException}</li>
     *   <li>Filter type is unrecognised → {@link IllegalArgumentException}</li>
     *   <li>Listener URL is blank or not HTTPS → {@link IllegalArgumentException}</li>
     * </ul>
     * On USPS 4xx / 5xx the exception wraps the response body — no local
     * row is persisted.
     */
    @Transactional
    public UspsDirectSubscription create(UspsDirectSubscriptionRequest req) {
        validateRequest(req);
        FilterType filterType = FilterType.valueOf(req.getFilterType());
        String environment = normalizeEnvironment(req.getEnvironment());
        String accessToken = mintPlatformToken(environment);

        String secret = mintSecret();
        Map<String, Object> body = buildCreateBody(req, filterType, secret);

        String url = UspsOAuthTokenCache.baseUrl(environment) + SUBSCRIPTION_PATH;
        String response = postJson(url, body, accessToken, "create-subscription");
        if (response == null) {
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking create returned no response body. "
                            + "Check USPS support if this repeats — no local row was saved.");
        }
        String uspsSubscriptionId = parseSubscriptionId(response);
        if (!StringUtils.hasText(uspsSubscriptionId)) {
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking create returned no subscriptionId. Body: "
                            + safeBody(response));
        }
        // Guard the unique constraint with an explicit check — friendlier
        // than a DataIntegrityViolationException stack.
        repository.findByUspsSubscriptionId(uspsSubscriptionId).ifPresent(existing -> {
            throw new IllegalStateException(
                    "USPS returned subscriptionId " + uspsSubscriptionId
                            + " which is already known locally (row #" + existing.getId()
                            + "). Refusing to duplicate.");
        });

        UspsDirectSubscription row = UspsDirectSubscription.builder()
                .uspsSubscriptionId(uspsSubscriptionId)
                .filterType(filterType.name())
                .filterValue(req.getFilterValue().trim())
                .listenerUrl(req.getListenerURL().trim())
                .secretEncrypted(secret)   // JPA converter encrypts on write
                .eventTypes(StringUtils.hasText(req.getEventTypes()) ? req.getEventTypes().trim() : null)
                .environment(environment)
                .status(Status.ACTIVE)
                .build();
        return repository.save(row);
    }

    /** List every ACTIVE subscription this platform has registered.
     *  Soft-deleted rows are filtered out. */
    public List<UspsDirectSubscription> list() {
        return repository.findByStatus(Status.ACTIVE.name());
    }

    /** Read a single row by primary key (regardless of status). */
    public Optional<UspsDirectSubscription> getById(Long id) {
        if (id == null) return Optional.empty();
        return repository.findById(id);
    }

    /**
     * DELETE the subscription against USPS, then soft-delete the local
     * row. USPS 404 is treated as success (the subscription is already
     * gone on their side; we still soft-delete locally). Any other error
     * throws + leaves the local row untouched so retry is safe.
     *
     * @return the soft-deleted entity, or {@link Optional#empty()} when
     *         no row matches {@code id}
     */
    @Transactional
    public Optional<UspsDirectSubscription> delete(Long id) {
        Optional<UspsDirectSubscription> maybe = repository.findById(id);
        if (maybe.isEmpty()) return Optional.empty();
        UspsDirectSubscription row = maybe.get();
        if (row.getStatus() == Status.DELETED) {
            // Idempotent — already deleted, nothing to do.
            return Optional.of(row);
        }

        String accessToken = mintPlatformToken(row.getEnvironment());
        String url = UspsOAuthTokenCache.baseUrl(row.getEnvironment())
                + SUBSCRIPTION_PATH + "/" + row.getUspsSubscriptionId();
        deleteAgainstUsps(url, accessToken);

        row.setStatus(Status.DELETED);
        row.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        return Optional.of(repository.save(row));
    }

    // ============================================================
    // Internal helpers — package-private for the service test.
    // ============================================================

    void validateRequest(UspsDirectSubscriptionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(req.getFilterType())) {
            throw new IllegalArgumentException("filterType is required (MID | TRACKING_NUMBERS | STID)");
        }
        try {
            FilterType.valueOf(req.getFilterType());
        } catch (IllegalArgumentException notInEnum) {
            throw new IllegalArgumentException(
                    "filterType must be one of MID / TRACKING_NUMBERS / STID (got: "
                            + req.getFilterType() + ")");
        }
        if (!StringUtils.hasText(req.getFilterValue())) {
            throw new IllegalArgumentException("filterValue is required");
        }
        if (!StringUtils.hasText(req.getListenerURL())) {
            throw new IllegalArgumentException("listenerURL is required");
        }
        String url = req.getListenerURL().trim();
        if (!url.toLowerCase().startsWith("https://")) {
            throw new IllegalArgumentException(
                    "listenerURL must use HTTPS (USPS refuses plain HTTP push targets)");
        }
    }

    /** Read platform OAuth creds from system_setting + mint a token. On
     *  missing creds throw the same shape the connector's boundary guards
     *  use — an admin who forgot to configure USPS gets an actionable
     *  message instead of a null Bearer header. */
    String mintPlatformToken(String environment) {
        String clientId = readSetting(UspsDirectConnector.SETTING_CLIENT_ID);
        String clientSecret = readSetting(UspsDirectConnector.SETTING_CLIENT_SECRET);
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            throw new IllegalStateException(
                    "USPS Direct is not configured platform-wide — no OAuth token available. "
                            + "Set USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET in "
                            + "/settings/system.");
        }
        return tokenCache.getToken(clientId, clientSecret, environment)
                .filter(t -> !t.contains("-local-"))
                .orElseThrow(() -> new IllegalStateException(
                        "USPS Direct OAuth token mint failed (env=" + environment + "). "
                                + "Verify USPS_PLATFORM_CLIENT_ID / USPS_PLATFORM_CLIENT_SECRET "
                                + "in /settings/system and that USPS is reachable."));
    }

    private String readSetting(String key) {
        if (systemSettingService == null) return null;
        try {
            return systemSettingService.getDecrypted(key).orElse(null);
        } catch (Exception ex) {
            log.warn("SystemSetting[{}] read failed: {}", key, ex.getMessage());
            return null;
        }
    }

    /** 24 SecureRandom bytes → 32-char URL-safe base64. USPS documents a
     *  32-character-minimum secret so this comfortably clears the bar. */
    String mintSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Shape the POST body USPS expects — camelCase per their OpenAPI
     *  spec. Filter properties are nested under {@code filterProperties};
     *  {@code trackingNumbers} is a JSON array not a CSV string. */
    Map<String, Object> buildCreateBody(UspsDirectSubscriptionRequest req,
                                        FilterType filterType, String secret) {
        Map<String, Object> filterProps = new LinkedHashMap<>();
        String value = req.getFilterValue().trim();
        switch (filterType) {
            case MID -> filterProps.put("MID", value);
            case STID -> filterProps.put("STID", value);
            case TRACKING_NUMBERS -> {
                List<String> tns = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                filterProps.put("trackingNumbers", tns);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listenerURL", req.getListenerURL().trim());
        body.put("filterProperties", filterProps);
        body.put("secret", secret);
        body.put("format", "JSON");
        if (StringUtils.hasText(req.getEventTypes())) {
            List<String> types = Arrays.stream(req.getEventTypes().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            body.put("eventTypes", types);
        }
        return body;
    }

    /** POST + return response body. Thrown errors carry the wrapped
     *  {@link RestClientResponseException} so the caller can surface a
     *  useful message. Package-visible so the service test can spy /
     *  override. */
    String postJson(String url, Map<String, Object> body, String accessToken, String context) {
        try {
            RestClient client = HttpClients.newBuilder().baseUrl(url).build();
            return client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.warn("USPS Direct {} rejected (HTTP {}): {}",
                    context, ex.getStatusCode().value(), safeBody(ex.getResponseBodyAsString()));
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking " + context + " failed ("
                            + ex.getStatusCode().value() + "): "
                            + safeBody(ex.getResponseBodyAsString()), ex);
        } catch (Exception ex) {
            log.warn("USPS Direct {} failed: {}", context, ex.getMessage());
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking " + context + " failed: " + ex.getMessage(), ex);
        }
    }

    /** DELETE against USPS. 404 is treated as already-gone (we still
     *  soft-delete locally). Package-visible so tests can spy / override. */
    void deleteAgainstUsps(String url, String accessToken) {
        try {
            RestClient client = HttpClients.newBuilder().baseUrl(url).build();
            client.delete()
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.info("USPS Subscriptions-Tracking DELETE 404 — subscription already gone; "
                        + "soft-deleting local row anyway.");
                return;
            }
            log.warn("USPS Direct delete-subscription rejected (HTTP {}): {}",
                    ex.getStatusCode().value(), safeBody(ex.getResponseBodyAsString()));
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking delete failed ("
                            + ex.getStatusCode().value() + "): "
                            + safeBody(ex.getResponseBodyAsString()), ex);
        } catch (Exception ex) {
            log.warn("USPS Direct delete-subscription failed: {}", ex.getMessage());
            throw new IllegalStateException(
                    "USPS Subscriptions-Tracking delete failed: " + ex.getMessage(), ex);
        }
    }

    /** Extract the {@code subscriptionId} field USPS returns on POST. */
    String parseSubscriptionId(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson == null ? "{}" : responseJson);
            // USPS' OpenAPI spec varies by version — check the primary +
            // a fallback shape observed in older CAT builds.
            String id = root.path("subscriptionId").asText(null);
            if (!StringUtils.hasText(id)) id = root.path("subscriptionID").asText(null);
            if (!StringUtils.hasText(id)) id = root.path("id").asText(null);
            return id;
        } catch (Exception ex) {
            log.warn("USPS subscription-create response unparseable: {}", ex.getMessage());
            return null;
        }
    }

    static String normalizeEnvironment(String env) {
        if (!StringUtils.hasText(env)) return "PRODUCTION";
        String trimmed = env.trim().toUpperCase();
        return "SANDBOX".equals(trimmed) ? "SANDBOX" : "PRODUCTION";
    }

    private static String safeBody(String body) {
        if (body == null) return "null";
        return body.length() > 800 ? body.substring(0, 800) + "…(truncated)" : body;
    }
}
