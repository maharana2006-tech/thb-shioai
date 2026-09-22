package com.multiship.backend.service.externalsystems;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1 framework — Actuator indicator aggregation. Confirms that:
 *  - zero connections = UP (feature off);
 *  - all UP = UP with per-connection detail;
 *  - any DOWN = DOWN (bad rows aren't hidden behind healthy overall);
 *  - UNKNOWN doesn't flip to DOWN (unknown = "haven't checked yet").
 */
class ExternalSystemHealthIndicatorTest {

    private final ExternalSystemRegistry registry = mock(ExternalSystemRegistry.class);
    private final ExternalSystemHealthIndicator indicator = new ExternalSystemHealthIndicator(registry);

    @Test
    void zeroConnectionsReportsUp() {
        when(registry.healthCheckAll()).thenReturn(List.of());
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("none configured", h.getDetails().get("connections"));
    }

    @Test
    void allUpReportsUpWithCounts() {
        when(registry.healthCheckAll()).thenReturn(List.of(
                HealthCheckResult.up("a", "STUB_JDBC"),
                HealthCheckResult.up("b", "STUB_JDBC")));
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals(2, h.getDetails().get("up"));
        assertEquals(0, h.getDetails().get("down"));
        assertEquals(0, h.getDetails().get("unknown"));
    }

    @Test
    void anyDownFlipsOverallToDown() {
        when(registry.healthCheckAll()).thenReturn(List.of(
                HealthCheckResult.up("a", "STUB_JDBC"),
                HealthCheckResult.down("b", "STUB_JDBC", "auth failed")));
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        assertEquals(1, h.getDetails().get("up"));
        assertEquals(1, h.getDetails().get("down"));
    }

    @Test
    void unknownDoesNotFlipOverallToDown() {
        // A connection marked inactive (UNKNOWN) shouldn't nag ops via
        // Actuator — only real failures should.
        when(registry.healthCheckAll()).thenReturn(List.of(
                HealthCheckResult.unknown("a", "STUB_JDBC", "inactive"),
                HealthCheckResult.up("b", "STUB_JDBC")));
        Health h = indicator.health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals(1, h.getDetails().get("unknown"));
    }

    @Test
    void perConnectionDetailIncludesTypeStatusMessage() {
        when(registry.healthCheckAll()).thenReturn(List.of(
                HealthCheckResult.down("nds", "NDS_ORACLE", "ORA-01017: invalid username/password")));
        Health h = indicator.health();
        assertEquals(Status.DOWN, h.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> per = (Map<String, Object>) h.getDetails().get("connections");
        @SuppressWarnings("unchecked")
        Map<String, Object> nds = (Map<String, Object>) per.get("nds");
        assertEquals("NDS_ORACLE", nds.get("systemType"));
        assertEquals("DOWN", nds.get("status"));
        assertTrue(((String) nds.get("message")).contains("ORA-01017"));
    }
}
