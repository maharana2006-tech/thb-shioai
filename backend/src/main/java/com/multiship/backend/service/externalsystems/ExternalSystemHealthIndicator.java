package com.multiship.backend.service.externalsystems;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator health indicator {@code external-systems}. Aggregates
 * every active connection's snapshot:
 * <ul>
 *   <li>overall UP when every connection reports UP;</li>
 *   <li>overall UP with "warnings" detail when only UNKNOWN rows exist
 *       (nothing has failed, some just haven't been dialled);</li>
 *   <li>overall DOWN if any connection reports DOWN — actionable, one
 *       bad row shouldn't be hidden behind a healthy-looking overall.</li>
 * </ul>
 *
 * <p>Zero-configuration: no connections in the DB → indicator returns
 * UP with a "no external systems configured" detail so /actuator/health
 * doesn't nag operators about a feature they haven't opted into.
 */
@Component
@RequiredArgsConstructor
public class ExternalSystemHealthIndicator implements HealthIndicator {

    private final ExternalSystemRegistry registry;

    @Override
    public Health health() {
        List<HealthCheckResult> snapshots = registry.healthCheckAll();
        if (snapshots.isEmpty()) {
            return Health.up()
                    .withDetail("connections", "none configured")
                    .build();
        }
        int up = 0, down = 0, unknown = 0;
        Map<String, Object> per = new HashMap<>();
        for (HealthCheckResult r : snapshots) {
            switch (r.status()) {
                case UP -> up++;
                case DOWN -> down++;
                case UNKNOWN -> unknown++;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("systemType", r.systemType());
            row.put("status", r.status().name());
            if (r.message() != null) row.put("message", r.message());
            if (!r.details().isEmpty()) row.put("details", r.details());
            per.put(r.connectionName(), row);
        }
        Health.Builder builder = down > 0 ? Health.down() : Health.up();
        return builder
                .withDetail("up", up)
                .withDetail("down", down)
                .withDetail("unknown", unknown)
                .withDetail("connections", per)
                .build();
    }
}
