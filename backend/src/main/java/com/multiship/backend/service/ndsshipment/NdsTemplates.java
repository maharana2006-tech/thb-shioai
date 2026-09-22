package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import com.multiship.backend.service.externalsystems.LoginContext;
import com.multiship.backend.service.externalsystems.connectors.NdsOracleConnector;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Thin per-call helper that dispenses a {@link NamedParameterJdbcTemplate}
 * from the S1 external-systems framework's {@code nds-default} connection.
 *
 * <p>Deliberately NOT cached — the registry already caches the underlying
 * Hikari pools per (connection, login-profile, clientCode). Wrapping each
 * pool in a fresh {@link NamedParameterJdbcTemplate} is cheap (thin
 * façade) and avoids any thread-safety questions if a future connector
 * config-change invalidates the DataSource mid-flight.
 *
 * <p>Two entry points:
 * <ol>
 *   <li>{@link #production()} — PRODUCTION login. For cross-client
 *       lookups (TB_SHIP_CONTAINER by container_id to find the tenant,
 *       TB_BILLABLE_CONTAINERS to resolve batch → client).</li>
 *   <li>{@link #forClient(String)} — CLIENT login. For per-tenant schema
 *       reads (OEHEAD / OE_SHIP_CONTAINER / OE_SEND_TO / SHIPVIA / etc.).
 *       Client-code validation lives inside the NDS connector — an
 *       invalid code throws {@code ExternalSystemException} with
 *       {@code Kind = INVALID_CLIENT_CODE} before any JDBC username
 *       is materialised.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class NdsTemplates {

    /** Well-known connection name seeded by V78. */
    public static final String NDS_CONNECTION = "nds-default";

    private final ExternalSystemRegistry registry;

    /** PRODUCTION-login JDBC template for cross-client lookups. */
    public NamedParameterJdbcTemplate production() {
        DataSource ds = asDataSource(registry.connect(NDS_CONNECTION,
                LoginContext.withProfile(NdsOracleConnector.PROFILE_PRODUCTION)));
        return new NamedParameterJdbcTemplate(ds);
    }

    /**
     * Per-tenant JDBC template. The connector applies the
     * {@code username = clientCode + password = clientCode} default rule
     * (or the row-level override) so callers just supply the code and
     * get a template bound to that client's schema.
     */
    public NamedParameterJdbcTemplate forClient(String clientCode) {
        Objects.requireNonNull(clientCode, "clientCode required for NDS CLIENT login");
        DataSource ds = asDataSource(registry.connect(NDS_CONNECTION,
                LoginContext.forClientWithProfile(clientCode,
                        NdsOracleConnector.PROFILE_CLIENT)));
        return new NamedParameterJdbcTemplate(ds);
    }

    private static DataSource asDataSource(Object handle) {
        if (!(handle instanceof DataSource ds)) {
            throw new IllegalStateException(
                    "NDS connector returned a non-DataSource handle: "
                            + (handle == null ? "null" : handle.getClass().getName()));
        }
        return ds;
    }
}
