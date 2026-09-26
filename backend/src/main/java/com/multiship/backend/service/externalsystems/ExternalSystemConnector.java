package com.multiship.backend.service.externalsystems;

import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;

/**
 * Framework SPI. Each protocol / vendor implementation (Oracle-schema-
 * per-tenant, SAP-REST, SFTP, gRPC, ...) is a {@code @Component} bean
 * implementing this interface. {@link ExternalSystemRegistry} picks up
 * all implementations at startup and dispatches
 * {@link #connect(String, LoginContext, ConnectorSecretAccess)} calls
 * based on the connection row's {@code system_type}.
 *
 * <p><b>Type parameters</b>
 * <ul>
 *   <li>{@code C} — the Jackson-deserializable class describing this
 *       connector's non-secret config shape (host, port, pool params,
 *       base URL, OAuth scopes, etc.). Returned from {@link #configType()}
 *       so the framework can parse {@code connection.config_json} for
 *       you before dispatch.</li>
 *   <li>{@code H} — the handle callers actually work with. For a JDBC
 *       connector this is a {@code javax.sql.DataSource}; for a REST
 *       connector it might be a pre-configured {@code WebClient}; for
 *       SFTP a session factory. The registry's typed
 *       {@code connect(name, ctx)} returns {@code Object} — callers
 *       cast to the connector-specific handle type they know they'll
 *       get.</li>
 * </ul>
 *
 * <p><b>Lifecycle</b>
 * <ul>
 *   <li>{@link #connect} may cache internally (e.g. a
 *       {@code Map<clientCode, DataSource>}); the framework calls it
 *       every time so caching is the connector's responsibility.</li>
 *   <li>{@link #onConfigChanged} lets connectors drain / rebuild
 *       pools when an admin edits the row. Default is a no-op.</li>
 *   <li>{@link #shutdown} is invoked at bean disposal — close pools,
 *       cancel background tasks, etc. Default is a no-op.</li>
 * </ul>
 *
 * <p><b>Failure model</b>
 * <ul>
 *   <li>Configuration problems (missing secret, wrong shape, invalid
 *       tenant scope) → throw {@link ExternalSystemException} with the
 *       connection name + a plain-English cause.</li>
 *   <li>Network / auth failures at first-connect → same exception; the
 *       caller decides whether to bubble to the UI or fall back.</li>
 *   <li>{@link #healthCheck} MUST NOT throw — return
 *       {@link HealthCheckResult#down} instead so the admin table can
 *       still render a row for the failed connection.</li>
 * </ul>
 *
 * @param <C> connector-specific config shape
 * @param <H> handle type returned to callers
 */
public interface ExternalSystemConnector<C, H> {

    /**
     * Uppercase discriminator matching {@code external_system_connection.system_type}.
     * Convention: {@code {VENDOR}_{PROTOCOL}} (e.g. {@code NDS_ORACLE},
     * {@code SAP_REST}). Must be stable across restarts — changing this
     * orphans existing rows.
     */
    String systemType();

    /**
     * Jackson class for {@code connection.config_json} deserialization.
     * All fields must be reachable via public getters/setters or the
     * standard record accessor.
     */
    Class<C> configType();

    /**
     * Return a usable handle for the given connection + call context.
     * Implementations MAY cache; caller re-invokes on every use.
     *
     * @param connectionName the {@code external_system_connection.name}
     *                       — passed so cached pools can be keyed off it
     * @param config         deserialized non-secret settings
     * @param ctx            caller's tenant + login-profile hint
     * @param secrets        secret-lookup helper the connector uses to
     *                       read encrypted values from
     *                       {@code external_system_secret} + client-
     *                       login overrides. Framework-managed — no
     *                       raw DB access from the connector.
     * @return the connector-specific handle (DataSource, WebClient, ...)
     * @throws ExternalSystemException on any config / auth / connectivity
     *                                 failure surfaced to the caller
     */
    H connect(String connectionName, C config, LoginContext ctx, ConnectorSecretAccess secrets);

    /**
     * Snapshot of the connection's current health. Called by the
     * Actuator indicator + on demand from the admin UI. Must not throw
     * — return {@link HealthCheckResult#down} instead.
     */
    HealthCheckResult healthCheck(String connectionName, C config, ConnectorSecretAccess secrets);

    /**
     * Called when an admin edits the {@code external_system_connection}
     * row or one of its secrets. Default is a no-op; connectors that
     * cache open pools should drain + rebuild here.
     */
    default void onConfigChanged(String connectionName) {}

    /**
     * Called at bean disposal ({@code @PreDestroy}). Close any pools,
     * cancel background tasks, release native resources.
     */
    default void shutdown() {}

    /**
     * V89 — post-generate writeback. Push flagged shipment fields to
     * this external system. The dispatcher has already redacted the
     * payload fields whose per-connection flag is off (nulls carry
     * that meaning). Default no-op returns {@code SKIPPED}; connectors
     * that support writeback override.
     *
     * <p>Fire-and-forget: the dispatcher catches every exception. Prefer
     * returning {@link WritebackAck#failed} over throwing so the caller
     * gets a structured status in the log line.
     */
    default WritebackAck writeShipment(String connectionName, C config,
                                       ConnectorSecretAccess secrets,
                                       WritebackPayload payload) {
        return WritebackAck.skipped("writeShipment not implemented by " + getClass().getSimpleName());
    }

    /**
     * V89 — post-void clear. NULL out (or set to VOIDED) the same set
     * of flagged fields on the external row that {@link #writeShipment}
     * populated. The dispatcher enforces symmetry — same flags gate
     * both sides — so a connector that supports one MUST support the
     * other with the matching field set.
     */
    default WritebackAck clearShipment(String connectionName, C config,
                                       ConnectorSecretAccess secrets,
                                       WritebackClearRequest req) {
        return WritebackAck.skipped("clearShipment not implemented by " + getClass().getSimpleName());
    }
}
