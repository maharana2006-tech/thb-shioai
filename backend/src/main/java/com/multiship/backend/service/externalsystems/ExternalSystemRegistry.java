package com.multiship.backend.service.externalsystems;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Auto-discovers every {@link ExternalSystemConnector} bean at startup
 * and dispatches {@code connect} + {@code healthCheck} calls to the
 * right one based on a connection row's {@code system_type}.
 *
 * <p>Discovery is by Spring bean injection ({@code List<ExternalSystemConnector<?, ?>>}
 * — every implementing @Component is picked up). Missing connectors
 * for known rows are logged at WARN on startup and become no-op at
 * call time (throws {@link ExternalSystemException.Kind#NO_CONNECTOR}).
 *
 * <p>Callers of {@link #connect(String, LoginContext)} know what handle
 * type they're going to get (they picked the connection by name) and
 * cast the returned {@code Object} accordingly. This is why the
 * connector's generic parameter {@code H} doesn't leak to the registry
 * API — the registry is protocol-agnostic.
 */
@Slf4j
@Service
public class ExternalSystemRegistry {

    private final ExternalSystemConfigService config;
    private final ObjectMapper objectMapper;
    /** system_type (uppercase) → connector. Populated in {@link #init()}. */
    private final Map<String, ExternalSystemConnector<?, ?>> byType = new HashMap<>();

    public ExternalSystemRegistry(ExternalSystemConfigService config,
                                  ObjectMapper objectMapper,
                                  List<ExternalSystemConnector<?, ?>> connectors) {
        this.config = config;
        this.objectMapper = objectMapper;
        for (ExternalSystemConnector<?, ?> c : connectors) {
            String key = normalizeType(c.systemType());
            ExternalSystemConnector<?, ?> prev = byType.put(key, c);
            if (prev != null) {
                log.warn("external-systems: DUPLICATE connector for systemType={} — {} replaced {}",
                        key, c.getClass().getSimpleName(), prev.getClass().getSimpleName());
            }
        }
    }

    @PostConstruct
    void init() {
        log.info("external-systems: registered {} connector(s): {}",
                byType.size(), byType.keySet());
        // Non-fatal audit — surface rows whose system_type has no
        // matching connector so ops sees it in boot logs.
        for (ExternalSystemConnection row : config.listActive()) {
            if (!byType.containsKey(normalizeType(row.getSystemType()))) {
                log.warn("external-systems: connection name={} type={} has NO matching connector; will fail at dispatch time",
                        row.getName(), row.getSystemType());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        for (ExternalSystemConnector<?, ?> c : byType.values()) {
            try { c.shutdown(); } catch (Exception e) {
                log.warn("external-systems: {} shutdown failed: {}",
                        c.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** All registered connectors — for diagnostics / admin listing. */
    public List<ExternalSystemConnector<?, ?>> connectors() {
        return List.copyOf(byType.values());
    }

    /** All configured connection rows (active + inactive). */
    public List<ExternalSystemConnection> connections() {
        return config.listConnections();
    }

    /**
     * Resolve + dispatch: fetch the connection row by name, parse its
     * config into the connector's shape, and call
     * {@link ExternalSystemConnector#connect}. Caller casts the returned
     * object to their expected handle type.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object connect(String connectionName, LoginContext ctx) {
        ExternalSystemConnection row = requireActive(connectionName);
        ExternalSystemConnector connector = requireConnector(row);
        Object cfg = parseConfig(row, connector);
        try {
            return connector.connect(row.getName(), cfg, ctx, secretsFor(row.getId()));
        } catch (ExternalSystemException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.OTHER,
                    connectionName,
                    "Connector " + connector.getClass().getSimpleName()
                            + " failed to connect: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Health of one named connection. Missing connector / missing row
     * return {@link HealthCheckResult.Status#DOWN} with the reason;
     * never throws.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public HealthCheckResult healthCheck(String connectionName) {
        Optional<ExternalSystemConnection> maybe = config.findByName(connectionName);
        if (maybe.isEmpty()) {
            return HealthCheckResult.down(connectionName, "?",
                    "No external_system_connection row named '" + connectionName + "'.");
        }
        ExternalSystemConnection row = maybe.get();
        if (!row.isActive()) {
            return HealthCheckResult.unknown(row.getName(), row.getSystemType(),
                    "Connection is marked inactive.");
        }
        ExternalSystemConnector connector = byType.get(normalizeType(row.getSystemType()));
        if (connector == null) {
            return HealthCheckResult.down(row.getName(), row.getSystemType(),
                    "No ExternalSystemConnector registered for system_type='" + row.getSystemType() + "'.");
        }
        Object cfg;
        try {
            cfg = parseConfig(row, connector);
        } catch (ExternalSystemException e) {
            return HealthCheckResult.down(row.getName(), row.getSystemType(), e.getMessage());
        }
        try {
            return connector.healthCheck(row.getName(), cfg, secretsFor(row.getId()));
        } catch (Exception e) {
            return HealthCheckResult.down(row.getName(), row.getSystemType(),
                    "Connector threw during healthCheck: " + e.getMessage());
        }
    }

    /** Health of every active connection. Used by the Actuator indicator. */
    public List<HealthCheckResult> healthCheckAll() {
        List<HealthCheckResult> out = new ArrayList<>();
        for (ExternalSystemConnection row : config.listActive()) {
            out.add(healthCheck(row.getName()));
        }
        return out;
    }

    /**
     * V89 — dispatch a post-generate writeback. Loads the connection
     * row, parses its config, and invokes {@code connector.writeShipment}.
     * Missing / inactive rows return {@link WritebackAck#skipped}
     * without throwing — the caller (dispatcher) is fire-and-forget.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public WritebackAck writeShipment(String connectionName, WritebackPayload payload) {
        Optional<ExternalSystemConnection> maybe = config.findByName(connectionName);
        if (maybe.isEmpty()) {
            return WritebackAck.skipped("no external_system_connection named '" + connectionName + "'");
        }
        ExternalSystemConnection row = maybe.get();
        if (!row.isActive()) return WritebackAck.skipped("connection inactive");
        ExternalSystemConnector connector = byType.get(normalizeType(row.getSystemType()));
        if (connector == null) {
            return WritebackAck.skipped("no connector registered for system_type=" + row.getSystemType());
        }
        Object cfg;
        try { cfg = parseConfig(row, connector); }
        catch (ExternalSystemException e) { return WritebackAck.failed(e.getMessage()); }
        try {
            return connector.writeShipment(row.getName(), cfg, secretsFor(row.getId()), payload);
        } catch (Exception e) {
            return WritebackAck.failed(connector.getClass().getSimpleName()
                    + " threw during writeShipment: " + e.getMessage());
        }
    }

    /**
     * V89 — dispatch a post-void clear. Same lookup + parse pattern as
     * {@link #writeShipment}. The connector nulls the flagged fields on
     * the external row (or sets a VOIDED status marker).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public WritebackAck clearShipment(String connectionName, WritebackClearRequest req) {
        Optional<ExternalSystemConnection> maybe = config.findByName(connectionName);
        if (maybe.isEmpty()) {
            return WritebackAck.skipped("no external_system_connection named '" + connectionName + "'");
        }
        ExternalSystemConnection row = maybe.get();
        if (!row.isActive()) return WritebackAck.skipped("connection inactive");
        ExternalSystemConnector connector = byType.get(normalizeType(row.getSystemType()));
        if (connector == null) {
            return WritebackAck.skipped("no connector registered for system_type=" + row.getSystemType());
        }
        Object cfg;
        try { cfg = parseConfig(row, connector); }
        catch (ExternalSystemException e) { return WritebackAck.failed(e.getMessage()); }
        try {
            return connector.clearShipment(row.getName(), cfg, secretsFor(row.getId()), req);
        } catch (Exception e) {
            return WritebackAck.failed(connector.getClass().getSimpleName()
                    + " threw during clearShipment: " + e.getMessage());
        }
    }

    /** Notify the connector that its config / secrets changed. */
    public void reload(String connectionName) {
        Optional<ExternalSystemConnection> row = config.findByName(connectionName);
        if (row.isEmpty()) return;
        ExternalSystemConnector<?, ?> connector = byType.get(normalizeType(row.get().getSystemType()));
        if (connector != null) {
            try { connector.onConfigChanged(connectionName); } catch (Exception e) {
                log.warn("external-systems: {} onConfigChanged failed for {}: {}",
                        connector.getClass().getSimpleName(), connectionName, e.getMessage());
            }
        }
    }

    // ─────────────────────── internals ──────────────────────────────

    private static String normalizeType(String s) {
        return Objects.requireNonNullElse(s, "").trim().toUpperCase(Locale.ROOT);
    }

    private ExternalSystemConnection requireActive(String name) {
        ExternalSystemConnection row = config.findByName(name).orElseThrow(() ->
                new ExternalSystemException(
                        ExternalSystemException.Kind.CONNECTION_NOT_FOUND,
                        name,
                        "No external_system_connection row named '" + name + "'."));
        if (!row.isActive()) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.CONNECTION_NOT_FOUND,
                    name,
                    "Connection '" + name + "' is inactive.");
        }
        return row;
    }

    private ExternalSystemConnector<?, ?> requireConnector(ExternalSystemConnection row) {
        ExternalSystemConnector<?, ?> connector = byType.get(normalizeType(row.getSystemType()));
        if (connector == null) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.NO_CONNECTOR,
                    row.getName(),
                    "No ExternalSystemConnector registered for system_type='" + row.getSystemType() + "'.");
        }
        return connector;
    }

    private Object parseConfig(ExternalSystemConnection row,
                               ExternalSystemConnector<?, ?> connector) {
        try {
            return objectMapper.readValue(row.getConfigJson(), connector.configType());
        } catch (Exception e) {
            throw new ExternalSystemException(
                    ExternalSystemException.Kind.INVALID_CONFIG,
                    row.getName(),
                    "Failed to parse config_json for connection '" + row.getName()
                            + "' as " + connector.configType().getSimpleName() + ": " + e.getMessage(),
                    e);
        }
    }

    private ConnectorSecretAccess secretsFor(Long connectionId) {
        return new ConnectorSecretAccess() {
            @Override public Optional<String> getSecret(String secretKey) {
                return config.getSecret(connectionId, secretKey);
            }
            @Override public Optional<ClientLogin> getClientOverride(String clientCode) {
                return config.getClientOverride(connectionId, clientCode);
            }
        };
    }
}
