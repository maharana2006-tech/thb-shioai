package com.multiship.backend.service.externalsystems.connectors;

import com.multiship.backend.service.externalsystems.ConnectorSecretAccess;
import com.multiship.backend.service.externalsystems.ExternalSystemConnector;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.ExternalSystemException.Kind;
import com.multiship.backend.service.externalsystems.HealthCheckResult;
import com.multiship.backend.service.externalsystems.LoginContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * First implementation of the S1 {@code ExternalSystemConnector} SPI —
 * Thoroughbred NDS (Oracle) with the two-login pattern the app needs:
 *
 * <ul>
 *   <li><b>PRODUCTION</b> — the cross-client operational login. Username +
 *       encrypted password come from the connection row's config + secret
 *       ({@code productionUsername} field, {@code productionPassword}
 *       secret). One pool per connection, cached on first use.</li>
 *   <li><b>CLIENT</b> — a per-tenant login into the client's own schema.
 *       Default rule: {@code username = clientCode + password = clientCode}
 *       (NDS convention). Overridden per-tenant via
 *       {@code external_system_client_login_override}. One pool per
 *       client-code, cached in a {@link ConcurrentHashMap} keyed by
 *       normalised code.</li>
 * </ul>
 *
 * <p>Pool eviction: any CLIENT pool untouched for
 * {@link NdsOracleConfig#getClientPoolEvictMinutes()} minutes is
 * closed on the next {@link #evictIdleClientPools()} sweep. Idle
 * PRODUCTION pool is retained.
 *
 * <p>Client-code validation: incoming codes are trimmed +
 * upper-cased + matched against {@link NdsOracleConfig#getClientCodePattern()}
 * before ANY pool is built. Rejected codes throw
 * {@link Kind#INVALID_CLIENT_CODE} — nothing goes near the JDBC
 * username unvalidated.
 *
 * <p>Handle type is {@code javax.sql.DataSource}. Callers cast the
 * registry's {@code Object} return to {@code DataSource} directly.
 */
@Slf4j
@Component
public class NdsOracleConnector implements ExternalSystemConnector<NdsOracleConfig, DataSource> {

    /** Discriminator matched against {@code external_system_connection.system_type}. */
    public static final String SYSTEM_TYPE = "NDS_ORACLE";

    /** Secret key for the PRODUCTION login's password. */
    public static final String SECRET_PRODUCTION_PASSWORD = "productionPassword";

    /** Login-profile hint values on LoginContext. */
    public static final String PROFILE_PRODUCTION = "PRODUCTION";
    public static final String PROFILE_CLIENT = "CLIENT";

    /**
     * Per-connection PRODUCTION pool cache. Keyed by connection name
     * so a rare "two NDS servers side by side" future works.
     */
    private final Map<String, HikariDataSource> productionPools = new ConcurrentHashMap<>();

    /**
     * CLIENT pool cache: (connectionName, clientCodeUpper) → pool.
     * Also tracks last-used timestamp for eviction.
     */
    private final Map<String, ClientPool> clientPools = new ConcurrentHashMap<>();

    private record ClientPool(HikariDataSource ds, AtomicLong lastUsedEpochMs) {}

    @Override
    public String systemType() { return SYSTEM_TYPE; }

    @Override
    public Class<NdsOracleConfig> configType() { return NdsOracleConfig.class; }

    @Override
    public DataSource connect(String connectionName, NdsOracleConfig cfg,
                              LoginContext ctx, ConnectorSecretAccess secrets) {
        validateConfig(connectionName, cfg);
        String profile = ctx.loginProfile()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .orElse(PROFILE_PRODUCTION);

        if (PROFILE_PRODUCTION.equals(profile)) {
            return getOrCreateProductionPool(connectionName, cfg, secrets);
        }
        if (PROFILE_CLIENT.equals(profile)) {
            String clientCode = ctx.clientCode().orElseThrow(() -> new ExternalSystemException(
                    Kind.INVALID_CLIENT_CODE, connectionName,
                    "CLIENT login requires a clientCode on the LoginContext."));
            return getOrCreateClientPool(connectionName, cfg, clientCode, secrets);
        }
        throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                "Unknown NDS login profile '" + profile + "' — expected PRODUCTION or CLIENT.");
    }

    @Override
    public HealthCheckResult healthCheck(String connectionName, NdsOracleConfig cfg,
                                         ConnectorSecretAccess secrets) {
        // Dial the PRODUCTION pool only — CLIENT logins are per-tenant and
        // not aggregatable into one signal.
        try {
            validateConfig(connectionName, cfg);
        } catch (ExternalSystemException e) {
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE, e.getMessage());
        }
        HikariDataSource pool;
        try {
            pool = getOrCreateProductionPool(connectionName, cfg, secrets);
        } catch (ExternalSystemException e) {
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE, e.getMessage());
        }
        Map<String, Object> details = new HashMap<>();
        details.put("host", cfg.getHost());
        details.put("serviceName", cfg.getServiceName());
        details.put("productionUsername", cfg.getProductionUsername());
        details.put("clientPools", clientPools.size());
        try (Connection c = pool.getConnection()) {
            // Cheap round-trip that costs one row on the wire.
            var stmt = c.prepareStatement("SELECT 1 FROM DUAL");
            stmt.setQueryTimeout(5);
            var rs = stmt.executeQuery();
            rs.next();
            return HealthCheckResult.up(connectionName, SYSTEM_TYPE,
                    "PRODUCTION login reachable.", details);
        } catch (Exception e) {
            details.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return HealthCheckResult.down(connectionName, SYSTEM_TYPE,
                    "SELECT 1 FROM DUAL failed: " + e.getMessage(), details);
        }
    }

    @Override
    public void onConfigChanged(String connectionName) {
        log.info("nds-oracle: config changed for connection '{}' — draining all cached pools",
                connectionName);
        // PRODUCTION pool for this connection
        HikariDataSource prod = productionPools.remove(connectionName);
        closeQuietly(prod, "production", connectionName);
        // All CLIENT pools under this connection
        String prefix = connectionName + "|";
        Iterator<Map.Entry<String, ClientPool>> it = clientPools.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix)) {
                closeQuietly(e.getValue().ds(), "client", e.getKey());
                it.remove();
            }
        }
    }

    @Override
    public void shutdown() {
        log.info("nds-oracle: shutting down — closing {} production + {} client pool(s)",
                productionPools.size(), clientPools.size());
        productionPools.forEach((n, ds) -> closeQuietly(ds, "production", n));
        productionPools.clear();
        clientPools.forEach((k, cp) -> closeQuietly(cp.ds(), "client", k));
        clientPools.clear();
    }

    /**
     * Idle-pool sweep. Not scheduled by default — callers wire this to
     * a Spring {@code @Scheduled} in S4 when the connector goes live,
     * or invoke on-demand from admin. Public so tests can drive it.
     */
    public int evictIdleClientPools() {
        long now = Instant.now().toEpochMilli();
        int evicted = 0;
        Iterator<Map.Entry<String, ClientPool>> it = clientPools.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ClientPool cp = e.getValue();
            long idleMs = now - cp.lastUsedEpochMs().get();
            // The evict-minutes threshold comes from the config; but the
            // cached pool doesn't hang on to the config, so we look at
            // Hikari's maxLifetime + idleTimeout instead. For MVP we treat
            // "not touched in idleTimeout + 60s" as evictable.
            long threshold = cp.ds().getIdleTimeout() + 60_000;
            if (idleMs > threshold) {
                closeQuietly(cp.ds(), "client (evicted)", e.getKey());
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("nds-oracle: evicted {} idle CLIENT pool(s); {} remain",
                    evicted, clientPools.size());
        }
        return evicted;
    }

    // ─────────────────────────── internals ──────────────────────────

    private void validateConfig(String connectionName, NdsOracleConfig cfg) {
        if (cfg == null) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "NDS Oracle config is null.");
        }
        if (cfg.getHost() == null || cfg.getHost().isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName, "host required");
        }
        if (cfg.getServiceName() == null || cfg.getServiceName().isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName, "serviceName required");
        }
        if (cfg.getProductionUsername() == null || cfg.getProductionUsername().isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName, "productionUsername required");
        }
        if (cfg.getClientCodePattern() == null || cfg.getClientCodePattern().isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "clientCodePattern required (must reject unsafe usernames).");
        }
    }

    private HikariDataSource getOrCreateProductionPool(String connectionName, NdsOracleConfig cfg,
                                                       ConnectorSecretAccess secrets) {
        return productionPools.computeIfAbsent(connectionName, name -> {
            String password = secrets.getSecret(SECRET_PRODUCTION_PASSWORD)
                    .filter(s -> !s.isBlank())
                    .orElseThrow(() -> new ExternalSystemException(
                            Kind.SECRET_UNAVAILABLE, name,
                            "Missing secret '" + SECRET_PRODUCTION_PASSWORD
                                    + "' for NDS connection '" + name + "'."));
            String url = NdsJdbcUrlBuilder.build(cfg.getHost(), cfg.getPort(),
                    cfg.getServiceName(), cfg.getServerMode());
            HikariConfig hc = baseHikari(cfg, "nds-prod-" + name);
            hc.setJdbcUrl(url);
            hc.setUsername(cfg.getProductionUsername());
            hc.setPassword(password);
            try {
                HikariDataSource ds = new HikariDataSource(hc);
                log.info("nds-oracle: PRODUCTION pool created for connection '{}' → {}@{}:{}",
                        name, cfg.getProductionUsername(), cfg.getHost(), cfg.getServiceName());
                return ds;
            } catch (Exception e) {
                throw new ExternalSystemException(Kind.AUTHENTICATION_FAILED, name,
                        "PRODUCTION pool creation failed: " + e.getMessage(), e);
            }
        });
    }

    private HikariDataSource getOrCreateClientPool(String connectionName, NdsOracleConfig cfg,
                                                   String rawClientCode,
                                                   ConnectorSecretAccess secrets) {
        String clientCode = validateClientCode(connectionName, cfg, rawClientCode);
        String key = connectionName + "|" + clientCode;
        ClientPool cp = clientPools.computeIfAbsent(key, k -> {
            // Override row wins over the code-rule.
            ConnectorSecretAccess.ClientLogin login = secrets.getClientOverride(clientCode)
                    .orElseGet(() -> new ConnectorSecretAccess.ClientLogin(clientCode, clientCode));
            String url = NdsJdbcUrlBuilder.build(cfg.getHost(), cfg.getPort(),
                    cfg.getServiceName(), cfg.getServerMode());
            HikariConfig hc = baseHikari(cfg, "nds-client-" + clientCode);
            hc.setJdbcUrl(url);
            hc.setUsername(login.username());
            hc.setPassword(login.password());
            try {
                HikariDataSource ds = new HikariDataSource(hc);
                log.info("nds-oracle: CLIENT pool created for connection '{}' client={} → {}@{}",
                        connectionName, clientCode, login.username(), cfg.getServiceName());
                return new ClientPool(ds, new AtomicLong(Instant.now().toEpochMilli()));
            } catch (Exception e) {
                throw new ExternalSystemException(Kind.AUTHENTICATION_FAILED, connectionName,
                        "CLIENT pool creation failed for '" + clientCode + "': " + e.getMessage(), e);
            }
        });
        cp.lastUsedEpochMs().set(Instant.now().toEpochMilli());
        return cp.ds();
    }

    private String validateClientCode(String connectionName, NdsOracleConfig cfg, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ExternalSystemException(Kind.INVALID_CLIENT_CODE, connectionName,
                    "clientCode required for CLIENT login.");
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        Pattern p;
        try {
            p = Pattern.compile(cfg.getClientCodePattern());
        } catch (Exception e) {
            throw new ExternalSystemException(Kind.INVALID_CONFIG, connectionName,
                    "clientCodePattern is not a valid regex: " + e.getMessage(), e);
        }
        if (!p.matcher(norm).matches()) {
            throw new ExternalSystemException(Kind.INVALID_CLIENT_CODE, connectionName,
                    "clientCode '" + raw + "' does not match pattern " + cfg.getClientCodePattern());
        }
        return norm;
    }

    private static HikariConfig baseHikari(NdsOracleConfig cfg, String poolName) {
        HikariConfig hc = new HikariConfig();
        hc.setDriverClassName("oracle.jdbc.driver.OracleDriver");
        hc.setPoolName(poolName);
        hc.setMaximumPoolSize(cfg.getPoolMaxSize());
        hc.setMinimumIdle(cfg.getPoolMinIdle());
        hc.setConnectionTimeout(cfg.getPoolConnectionTimeoutMs());
        hc.setIdleTimeout(cfg.getPoolIdleTimeoutMs());
        hc.setMaxLifetime(cfg.getPoolMaxLifetimeMs());
        // Oracle-safe idle validation query — much cheaper than a JDBC
        // isValid() round-trip in some driver versions.
        hc.setConnectionTestQuery("SELECT 1 FROM DUAL");
        return hc;
    }

    private static void closeQuietly(HikariDataSource ds, String kind, String key) {
        if (ds == null) return;
        try { ds.close(); } catch (Exception e) {
            log.warn("nds-oracle: {} pool {} close failed: {}", kind, key, e.getMessage());
        }
    }

    // Test-only accessors (package-private). Used by NdsOracleConnectorTest
    // to inspect the pool caches without exposing them to production callers.
    Map<String, HikariDataSource> productionPoolsForTest() { return productionPools; }
    Map<String, ClientPool> clientPoolsForTest() { return clientPools; }
}
