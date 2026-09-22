package com.multiship.backend.service.externalsystems.connectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Jackson shape for {@code external_system_connection.config_json}
 * when {@code system_type = 'NDS_ORACLE'}. All fields are non-secret
 * (secrets live in {@code external_system_secret}).
 *
 * <p>Defaults are set so a minimal admin write only needs to fill
 * {@code host} + {@code serviceName} + {@code productionUsername}.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NdsOracleConfig {

    // ─── server descriptor ──────────────────────────────────────────

    /** Oracle server hostname or IP (e.g. "192.168.3.8"). */
    private String host;

    /** TNS listener port. Defaults to Oracle's 1521. */
    private int port = 1521;

    /** Service name (e.g. "tb10g"). */
    private String serviceName;

    /** DEDICATED (default) or SHARED. NDS uses DEDICATED. */
    private String serverMode = "DEDICATED";

    // ─── login identities ───────────────────────────────────────────

    /**
     * PRODUCTION-login username. Corresponding password lives in
     * {@code external_system_secret} under secret_key
     * {@link NdsOracleConnector#SECRET_PRODUCTION_PASSWORD}.
     * Not nullable; connector throws if blank.
     */
    private String productionUsername;

    /**
     * Regex the client code must match before the CLIENT-login pool
     * is created (guards against SQL injection via the JDBC username
     * even though nothing here builds SQL from it). Uppercase
     * alphanumeric + `-`, 3–12 chars by default.
     */
    private String clientCodePattern = "^[A-Z0-9-]{3,12}$";

    // ─── HikariCP pool tuning (applied to BOTH pool families) ──────

    private int poolMaxSize = 5;
    private int poolMinIdle = 1;
    private long poolConnectionTimeoutMs = 10_000;
    private long poolIdleTimeoutMs = 600_000;
    private long poolMaxLifetimeMs = 1_800_000;

    /**
     * Per-client pool eviction — a client pool untouched for this
     * many minutes is closed on the next reap. Defaults to 30 min.
     */
    private int clientPoolEvictMinutes = 30;

    // Reserved for future connector versions:
    // - loginTimeoutSeconds
    // - additional Hikari data-source properties as a Map
    // - TLS descriptor (SECURITY, wallet path)
    // Add fields defensively; existing rows tolerate unknown-key
    // deserialization because of Jackson defaults.
}
