package com.multiship.backend.service.externalsystems;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.model.ExternalSystemConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S1 framework — ExternalSystemRegistry unit tests using stub
 * connectors so the SPI contract is exercised without needing a
 * real Oracle / REST implementation.
 */
class ExternalSystemRegistryTest {

    // ─── stub connectors ────────────────────────────────────────────

    /** Simple JDBC-like connector — its handle is a String pool-name. */
    static class StubJdbcConnector implements ExternalSystemConnector<StubConfig, String> {
        HealthCheckResult next = HealthCheckResult.up("?", "STUB_JDBC");
        int connectCalls = 0;
        int reloadCalls = 0;

        @Override public String systemType()                        { return "STUB_JDBC"; }
        @Override public Class<StubConfig> configType()             { return StubConfig.class; }
        @Override public String connect(String name, StubConfig cfg, LoginContext ctx, ConnectorSecretAccess s) {
            connectCalls++;
            return name + ":" + (cfg == null ? "?" : cfg.host);
        }
        @Override public HealthCheckResult healthCheck(String name, StubConfig cfg, ConnectorSecretAccess s) {
            return HealthCheckResult.up(name, systemType());
        }
        @Override public void onConfigChanged(String name)          { reloadCalls++; }
    }

    /** Stub that always fails healthCheck — verifies aggregation. */
    static class StubRestConnector implements ExternalSystemConnector<StubConfig, String> {
        @Override public String systemType()            { return "STUB_REST"; }
        @Override public Class<StubConfig> configType() { return StubConfig.class; }
        @Override public String connect(String name, StubConfig cfg, LoginContext ctx, ConnectorSecretAccess s) {
            return "rest:" + name;
        }
        @Override public HealthCheckResult healthCheck(String name, StubConfig cfg, ConnectorSecretAccess s) {
            return HealthCheckResult.down(name, systemType(), "stub always down");
        }
    }

    /** Deliberately broken configType() so healthCheck's exception path triggers. */
    static class ThrowingConnector implements ExternalSystemConnector<StubConfig, String> {
        @Override public String systemType()            { return "STUB_THROW"; }
        @Override public Class<StubConfig> configType() { return StubConfig.class; }
        @Override public String connect(String name, StubConfig cfg, LoginContext ctx, ConnectorSecretAccess s) {
            throw new RuntimeException("cannot connect");
        }
        @Override public HealthCheckResult healthCheck(String name, StubConfig cfg, ConnectorSecretAccess s) {
            throw new RuntimeException("health explodes");
        }
    }

    public static class StubConfig {
        public String host = "example";
        public int port = 5432;
    }

    // ─── fixtures ───────────────────────────────────────────────────

    private ExternalSystemConfigService config;
    private ObjectMapper objectMapper;
    private StubJdbcConnector jdbc;
    private StubRestConnector rest;
    private ThrowingConnector broken;

    @BeforeEach
    void setUp() {
        config = mock(ExternalSystemConfigService.class);
        objectMapper = new ObjectMapper();
        jdbc = new StubJdbcConnector();
        rest = new StubRestConnector();
        broken = new ThrowingConnector();
    }

    private ExternalSystemRegistry newRegistry(ExternalSystemConnector<?, ?>... connectors) {
        when(config.listActive()).thenReturn(List.of());
        return new ExternalSystemRegistry(config, objectMapper, List.of(connectors));
    }

    private ExternalSystemConnection row(String name, String type, boolean active, String json) {
        ExternalSystemConnection c = new ExternalSystemConnection();
        c.setId(1L);
        c.setName(name);
        c.setSystemType(type);
        c.setActive(active);
        c.setConfigJson(json);
        return c;
    }

    // ─── tests ──────────────────────────────────────────────────────

    @Test
    void connectDispatchesToRightConnectorByType() {
        ExternalSystemRegistry reg = newRegistry(jdbc, rest);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", true, "{\"host\":\"192.168.3.8\",\"port\":1521}")));

        Object handle = reg.connect("nds", LoginContext.platform());
        assertEquals("nds:192.168.3.8", handle);
        assertEquals(1, jdbc.connectCalls);
    }

    @Test
    void systemTypeMatchIsCaseInsensitive() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "stub_jdbc", true, "{\"host\":\"x\"}"))); // lowercase

        Object handle = reg.connect("nds", LoginContext.platform());
        assertEquals("nds:x", handle);
    }

    @Test
    void unknownSystemTypeThrowsNoConnector() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "SFTP_NEVER_REGISTERED", true, "{}")));

        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> reg.connect("nds", LoginContext.platform()));
        assertEquals(ExternalSystemException.Kind.NO_CONNECTOR, e.kind());
    }

    @Test
    void missingConnectionThrowsConnectionNotFound() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nope")).thenReturn(Optional.empty());

        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> reg.connect("nope", LoginContext.platform()));
        assertEquals(ExternalSystemException.Kind.CONNECTION_NOT_FOUND, e.kind());
    }

    @Test
    void inactiveConnectionThrowsConnectionNotFound() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", false, "{}")));

        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> reg.connect("nds", LoginContext.platform()));
        assertEquals(ExternalSystemException.Kind.CONNECTION_NOT_FOUND, e.kind());
    }

    @Test
    void invalidJsonConfigThrowsInvalidConfig() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", true, "{not json")));

        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> reg.connect("nds", LoginContext.platform()));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
    }

    @Test
    void healthCheckAggregatesActiveConnections() {
        ExternalSystemRegistry reg = newRegistry(jdbc, rest);
        when(config.listActive()).thenReturn(List.of(
                row("nds", "STUB_JDBC", true, "{}"),
                row("sap", "STUB_REST", true, "{}")));
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", true, "{}")));
        when(config.findByName("sap")).thenReturn(Optional.of(
                row("sap", "STUB_REST", true, "{}")));

        List<HealthCheckResult> results = reg.healthCheckAll();
        assertEquals(2, results.size());
        HealthCheckResult ndsResult = results.stream()
                .filter(r -> "nds".equals(r.connectionName())).findFirst().orElseThrow();
        assertEquals(HealthCheckResult.Status.UP, ndsResult.status());
        HealthCheckResult sapResult = results.stream()
                .filter(r -> "sap".equals(r.connectionName())).findFirst().orElseThrow();
        assertEquals(HealthCheckResult.Status.DOWN, sapResult.status());
    }

    @Test
    void healthCheckNeverThrowsEvenForBrokenConnector() {
        ExternalSystemRegistry reg = newRegistry(broken);
        when(config.findByName("broken")).thenReturn(Optional.of(
                row("broken", "STUB_THROW", true, "{}")));

        HealthCheckResult r = reg.healthCheck("broken");
        assertEquals(HealthCheckResult.Status.DOWN, r.status(),
                "connector threw during healthCheck — registry catches + reports DOWN");
        assertNotNull(r.message());
    }

    @Test
    void healthCheckReportsDownForMissingConnection() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nope")).thenReturn(Optional.empty());
        HealthCheckResult r = reg.healthCheck("nope");
        assertEquals(HealthCheckResult.Status.DOWN, r.status());
    }

    @Test
    void healthCheckReportsUnknownForInactive() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", false, "{}")));
        HealthCheckResult r = reg.healthCheck("nds");
        assertEquals(HealthCheckResult.Status.UNKNOWN, r.status());
    }

    @Test
    void healthCheckReportsDownForMissingConnector() {
        ExternalSystemRegistry reg = newRegistry(jdbc); // no STUB_REST registered
        when(config.findByName("sap")).thenReturn(Optional.of(
                row("sap", "STUB_REST", true, "{}")));
        HealthCheckResult r = reg.healthCheck("sap");
        assertEquals(HealthCheckResult.Status.DOWN, r.status());
    }

    @Test
    void reloadInvokesOnConfigChanged() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nds")).thenReturn(Optional.of(
                row("nds", "STUB_JDBC", true, "{}")));

        reg.reload("nds");
        assertEquals(1, jdbc.reloadCalls);
    }

    @Test
    void reloadIsNoOpForUnknownConnection() {
        ExternalSystemRegistry reg = newRegistry(jdbc);
        when(config.findByName("nope")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> reg.reload("nope"));
        assertEquals(0, jdbc.reloadCalls);
    }

    @Test
    void connectExceptionFromConnectorWrappedAsExternalSystemException() {
        ExternalSystemRegistry reg = newRegistry(broken);
        when(config.findByName("broken")).thenReturn(Optional.of(
                row("broken", "STUB_THROW", true, "{}")));

        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> reg.connect("broken", LoginContext.platform()));
        assertEquals(ExternalSystemException.Kind.OTHER, e.kind());
        assertNotNull(e.getCause());
    }
}
