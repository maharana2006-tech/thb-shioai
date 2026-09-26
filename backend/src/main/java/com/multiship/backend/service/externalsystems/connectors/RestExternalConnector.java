package com.multiship.backend.service.externalsystems.connectors;

import com.multiship.backend.service.carriers.HttpClients;
import com.multiship.backend.service.externalsystems.ConnectorSecretAccess;
import com.multiship.backend.service.externalsystems.ExternalSystemConnector;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.ExternalSystemException.Kind;
import com.multiship.backend.service.externalsystems.HealthCheckResult;
import com.multiship.backend.service.externalsystems.LoginContext;
import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S5 — REST/JSON connector. Second SPI implementation after
 * {@link NdsOracleConnector}; proves the framework generalizes
 * beyond JDBC. Handle type is Spring's {@link RestClient}.
 *
 * <p>Auth model — two supported flavours picked by config shape:
 * <ol>
 *   <li><b>Static header (default)</b> — no
 *       {@link RestExternalConfig#getOauthTokenUrl() oauthTokenUrl}
 *       configured. Reads secret {@code apiKey}; injects it as the
 *       configured {@link RestExternalConfig#getApiKeyHeader() apiKeyHeader}
 *       (default {@code X-API-Key}) on every request.</li>
 *   <li><b>OAuth 2.0 client-credentials</b> — {@code oauthTokenUrl}
 *       is set. Reads secret {@code oauthClientSecret} +
 *       {@code oauthClientId} (as a config field NOT a secret since
 *       client IDs are non-sensitive). Token acquisition is a
 *       future consumer's job — this stub declares the shape but
 *       doesn't perform the token dance (S5b if a real REST
 *       integration needs it).</li>
 * </ol>
 *
 * <p>Health check is a {@code GET {baseUrl}{healthCheckPath}} with a
 * short timeout (default 3s) — 200-range = UP, 4xx/5xx or timeout
 * = DOWN with the HTTP status / exception message in details.
 *
 * <p>Client caching: one {@link RestClient} per connection name is
 * cached in a {@code ConcurrentHashMap}. Cheap to rebuild so no
 * eviction sweep; {@link #onConfigChanged} clears the entry.
 */
@Slf4j
@Component
public class RestExternalConnector implements ExternalSystemConnector<RestExternalConfig, RestClient> {

    public static final String SYSTEM_TYPE = "REST_JSON";

    /** Secret keys the connector reads via ConnectorSecretAccess. */
    public static final String SECRET_API_KEY = "apiKey";
    public static final String SECRET_OAUTH_CLIENT_SECRET = "oauthClientSecret";

    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    @Override public String systemType() { return SYSTEM_TYPE; }

    @Override public Class<RestExternalConfig> configType() { return RestExternalConfig.class; }

    @Override
    public RestClient connect(String connectionName, RestExternalConfig cfg,
                              LoginContext ctx, ConnectorSecretAccess secrets) {
        validateConfig(connectionName, cfg);
        return clients.computeIfAbsent(connectionName, name -> buildClient(name, cfg, secrets));
    }

    @Override
    public HealthCheckResult healthCheck(String connectionName, RestExternalConfig cfg,
                                         ConnectorSecretAccess secrets) {
        try {
            validateConfig(connectionName, cfg);
        } catch (ExternalSystemException e) {
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE, e.getMessage());
        }
        RestClient client;
        try {
            client = clients.computeIfAbsent(connectionName, name -> buildClient(name, cfg, secrets));
        } catch (ExternalSystemException e) {
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE, e.getMessage());
        }
        Map<String, Object> details = new HashMap<>();
        details.put("baseUrl", cfg.getBaseUrl());
        details.put("healthCheckPath", cfg.getHealthCheckPath());
        try {
            String body = client.get()
                    .uri(cfg.getHealthCheckPath())
                    .retrieve()
                    .body(String.class);
            String preview = body == null ? "" : body.substring(0, Math.min(80, body.length()));
            details.put("bodyPreview", preview);
            return HealthCheckResult.up(connectionName, SYSTEM_TYPE,
                    "GET " + cfg.getHealthCheckPath() + " OK", details);
        } catch (Exception e) {
            details.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE,
                    "Health check failed: " + e.getMessage(), details);
        }
    }

    @Override
    public WritebackAck writeShipment(String connectionName, RestExternalConfig cfg,
                                      ConnectorSecretAccess secrets, WritebackPayload payload) {
        RestClient client;
        try {
            client = clients.computeIfAbsent(connectionName, n -> buildClient(n, cfg, secrets));
        } catch (ExternalSystemException e) {
            return WritebackAck.failed("client build failed: " + e.getMessage());
        }
        Map<String, Object> body = toBody(payload);
        String path = cfg.getWritebackPath() == null || cfg.getWritebackPath().isBlank()
                ? "/shipments" : cfg.getWritebackPath();
        try {
            client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return WritebackAck.ok("POST " + path + " OK — keys=" + body.keySet());
        } catch (Exception e) {
            return WritebackAck.failed("POST " + path + " failed: " + e.getMessage());
        }
    }

    @Override
    public WritebackAck clearShipment(String connectionName, RestExternalConfig cfg,
                                      ConnectorSecretAccess secrets, WritebackClearRequest req) {
        RestClient client;
        try {
            client = clients.computeIfAbsent(connectionName, n -> buildClient(n, cfg, secrets));
        } catch (ExternalSystemException e) {
            return WritebackAck.failed("client build failed: " + e.getMessage());
        }
        String template = cfg.getWritebackClearPath() == null || cfg.getWritebackClearPath().isBlank()
                ? "/shipments/{tracking}" : cfg.getWritebackClearPath();
        // Two conventions: {tracking} placeholder → DELETE; otherwise POST body.
        boolean hasTrackingSlot = template.contains("{tracking}");
        try {
            if (hasTrackingSlot) {
                String tracking = req.trackingNumber() == null ? "" : req.trackingNumber();
                String path = template.replace("{tracking}", java.net.URLEncoder.encode(
                        tracking, java.nio.charset.StandardCharsets.UTF_8));
                client.delete().uri(path).retrieve().toBodilessEntity();
                return WritebackAck.ok("DELETE " + path + " OK");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("trackingNumber", req.trackingNumber());
            body.put("orderNo", req.orderNo());
            body.put("clientCode", req.clientCode());
            client.post()
                    .uri(template)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return WritebackAck.ok("POST " + template + " OK");
        } catch (Exception e) {
            return WritebackAck.failed("clear " + template + " failed: " + e.getMessage());
        }
    }

    /**
     * Body shape for the generate-side writeback. Includes only the
     * fields the dispatcher didn't redact — null-valued keys are
     * dropped so the JSON stays clean.
     */
    static Map<String, Object> toBody(WritebackPayload p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", p.orderNo());
        body.put("clientCode", p.clientCode());
        if (p.trackingNumber() != null) body.put("trackingNumber", p.trackingNumber());
        if (p.shipDate() != null) body.put("shipDate", p.shipDate().toString());
        if (p.status() != null) body.put("status", p.status());
        if (p.carrierCode() != null) body.put("carrierCode", p.carrierCode());
        if (p.serviceCode() != null) body.put("serviceCode", p.serviceCode());
        if (p.freightAmount() != null) {
            body.put("freightAmount", p.freightAmount());
            if (p.currency() != null) body.put("currency", p.currency());
        }
        if (p.batchId() != null) body.put("batchId", p.batchId());
        if (p.scannedValue() != null) body.put("scannedValue", p.scannedValue());
        return body;
    }

    @Override
    public void onConfigChanged(String connectionName) {
        clients.remove(connectionName);
        log.info("rest-connector: cleared cached client for connection '{}'", connectionName);
    }

    @Override
    public void shutdown() {
        clients.clear();
    }

    // ─────────────────────────── internals ──────────────────────────

    private void validateConfig(String connectionName, RestExternalConfig cfg) {
        if (cfg == null) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "REST config is null.");
        }
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "baseUrl required for REST_JSON connection.");
        }
        String path = cfg.getHealthCheckPath();
        if (path == null || path.isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "healthCheckPath required for REST_JSON connection.");
        }
    }

    private RestClient buildClient(String connectionName, RestExternalConfig cfg,
                                   ConnectorSecretAccess secrets) {
        RestClient.Builder builder = HttpClients.newBuilder().baseUrl(normalizeBaseUrl(cfg.getBaseUrl()));
        boolean oauthMode = cfg.getOauthTokenUrl() != null && !cfg.getOauthTokenUrl().isBlank();
        if (oauthMode) {
            Optional<String> clientSecret = secrets.getSecret(SECRET_OAUTH_CLIENT_SECRET);
            if (clientSecret.isEmpty() || clientSecret.get().isBlank()) {
                throw new ExternalSystemException(Kind.SECRET_UNAVAILABLE, connectionName,
                        "OAuth mode requested (oauthTokenUrl set) but secret '"
                                + SECRET_OAUTH_CLIENT_SECRET + "' is missing.");
            }
            // Real token acquisition deferred to S5b — a production consumer
            // will typically bring its own OAuth interceptor. This stub logs
            // and returns a client without an auth header so the health
            // check reveals it's misconfigured.
            log.warn("rest-connector[{}]: OAuth mode requested but this stub does not perform the token dance; "
                    + "requests will go unauthenticated until a real OAuth interceptor is wired.",
                    connectionName);
        } else {
            String headerName = (cfg.getApiKeyHeader() == null || cfg.getApiKeyHeader().isBlank())
                    ? "X-API-Key"
                    : cfg.getApiKeyHeader();
            Optional<String> apiKey = secrets.getSecret(SECRET_API_KEY);
            if (apiKey.isEmpty() || apiKey.get().isBlank()) {
                throw new ExternalSystemException(Kind.SECRET_UNAVAILABLE, connectionName,
                        "Static-header auth requested (no oauthTokenUrl) but secret '"
                                + SECRET_API_KEY + "' is missing.");
            }
            builder.defaultHeader(headerName, apiKey.get());
        }
        RestClient client = builder.build();
        log.info("rest-connector: built client for connection '{}' → baseUrl={} (auth-mode={})",
                connectionName, cfg.getBaseUrl(), oauthMode ? "oauth-stub" : "static-header");
        return client;
    }

    private static String normalizeBaseUrl(String raw) {
        String s = raw.trim();
        // RestClient's URI resolution treats a trailing slash + a
        // leading slash on the path as duplicate segments. Strip
        // trailing slash so `baseUrl + "/health"` stays clean.
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // Test-only.
    Map<String, RestClient> clientsForTest() { return clients; }
}
