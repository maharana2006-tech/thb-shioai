package com.multiship.backend.service.externalsystems;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral health snapshot returned by an
 * {@code ExternalSystemConnector.healthCheck(name)} call. Aggregated
 * by {@code ExternalSystemHealthIndicator} for Actuator; also shown
 * to the admin on the {@code /settings/external-systems} page next
 * to each connection row.
 *
 * <p>{@link Status#UNKNOWN} is the correct return when the connector
 * genuinely can't tell (e.g. hasn't been dialled yet, or optional and
 * disabled). {@link Status#DOWN} means "I tried and it failed" —
 * caller should surface the ORA / HTTP code in {@link #message}.
 */
public final class HealthCheckResult {

    public enum Status { UP, DOWN, UNKNOWN }

    private final String connectionName;
    private final String systemType;
    private final Status status;
    private final String message;
    private final Instant checkedAt;
    private final Map<String, Object> details;

    private HealthCheckResult(String connectionName, String systemType,
                              Status status, String message,
                              Map<String, Object> details) {
        this.connectionName = connectionName;
        this.systemType = systemType;
        this.status = Objects.requireNonNull(status);
        this.message = message;
        this.checkedAt = Instant.now();
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static HealthCheckResult up(String connectionName, String systemType) {
        return new HealthCheckResult(connectionName, systemType, Status.UP, null, null);
    }

    public static HealthCheckResult up(String connectionName, String systemType,
                                       String message, Map<String, Object> details) {
        return new HealthCheckResult(connectionName, systemType, Status.UP, message, details);
    }

    public static HealthCheckResult down(String connectionName, String systemType,
                                         String message) {
        return new HealthCheckResult(connectionName, systemType, Status.DOWN, message, null);
    }

    public static HealthCheckResult down(String connectionName, String systemType,
                                         String message, Map<String, Object> details) {
        return new HealthCheckResult(connectionName, systemType, Status.DOWN, message, details);
    }

    public static HealthCheckResult unknown(String connectionName, String systemType,
                                            String message) {
        Map<String, Object> d = new HashMap<>();
        return new HealthCheckResult(connectionName, systemType, Status.UNKNOWN, message, d);
    }

    public String connectionName() { return connectionName; }
    public String systemType()     { return systemType; }
    public Status status()          { return status; }
    public String message()         { return message; }
    public Instant checkedAt()      { return checkedAt; }
    public Map<String, Object> details() { return details; }
}
