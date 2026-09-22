package com.multiship.backend.service.externalsystems.connectors;

import com.multiship.backend.service.externalsystems.ConnectorSecretAccess;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.HealthCheckResult;
import com.multiship.backend.service.externalsystems.LoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S5 — RestExternalConnector unit tests. Focus on validation +
 * auth-mode branching + caching. No real HTTP dial (would need a
 * mock server, deferred to S5b integration test if a real REST
 * integration lands).
 */
class RestExternalConnectorTest {

    private RestExternalConnector connector;
    private RestExternalConfig baseConfig;

    @BeforeEach
    void setUp() {
        connector = new RestExternalConnector();
        baseConfig = new RestExternalConfig();
        baseConfig.setBaseUrl("https://api.example.com/v1");
    }

    // ─── stub ConnectorSecretAccess ────────────────────────────────

    private static class StubSecrets implements ConnectorSecretAccess {
        String apiKey;
        String oauthClientSecret;

        @Override public Optional<String> getSecret(String secretKey) {
            return switch (secretKey) {
                case "apiKey" -> Optional.ofNullable(apiKey);
                case "oauthClientSecret" -> Optional.ofNullable(oauthClientSecret);
                default -> Optional.empty();
            };
        }
        @Override public Optional<ClientLogin> getClientOverride(String clientCode) {
            return Optional.empty();
        }
    }

    // ─── config validation ─────────────────────────────────────────

    @Test
    void connectRequiresBaseUrl() {
        baseConfig.setBaseUrl(null);
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("api", baseConfig, LoginContext.platform(), new StubSecrets()));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
    }

    @Test
    void connectRequiresHealthCheckPath() {
        baseConfig.setHealthCheckPath("");
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("api", baseConfig, LoginContext.platform(), new StubSecrets()));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
    }

    // ─── auth-mode branching + missing-secret handling ─────────────

    @Test
    void staticHeaderModeRequiresApiKeySecret() {
        StubSecrets s = new StubSecrets(); // no apiKey set
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("api", baseConfig, LoginContext.platform(), s));
        assertEquals(ExternalSystemException.Kind.SECRET_UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().contains("apiKey"));
    }

    @Test
    void staticHeaderModeBuildsClientWhenSecretPresent() {
        StubSecrets s = new StubSecrets();
        s.apiKey = "sk-xxx";
        assertDoesNotThrow(() ->
                connector.connect("api", baseConfig, LoginContext.platform(), s));
        assertEquals(1, connector.clientsForTest().size(),
                "one client cached per connection name");
    }

    @Test
    void oauthModeRequiresOauthClientSecret() {
        baseConfig.setOauthTokenUrl("https://api.example.com/oauth/token");
        StubSecrets s = new StubSecrets(); // no oauthClientSecret set
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("api", baseConfig, LoginContext.platform(), s));
        assertEquals(ExternalSystemException.Kind.SECRET_UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().contains("oauthClientSecret"));
    }

    @Test
    void oauthModeBuildsStubbedClientWhenSecretPresent() {
        // Real OAuth token-dance is deferred to S5b; this stub returns
        // a working RestClient without an auth header + logs a warn.
        // Behavior verified: no exception + client cached.
        baseConfig.setOauthTokenUrl("https://api.example.com/oauth/token");
        StubSecrets s = new StubSecrets();
        s.oauthClientSecret = "oauth-secret";
        assertDoesNotThrow(() ->
                connector.connect("api", baseConfig, LoginContext.platform(), s));
        assertEquals(1, connector.clientsForTest().size());
    }

    // ─── caching semantics ─────────────────────────────────────────

    @Test
    void repeatedConnectReturnsSameClient() {
        StubSecrets s = new StubSecrets();
        s.apiKey = "sk-xxx";
        Object a = connector.connect("api", baseConfig, LoginContext.platform(), s);
        Object b = connector.connect("api", baseConfig, LoginContext.platform(), s);
        assertSame(a, b, "clients cached by connection name");
        assertEquals(1, connector.clientsForTest().size());
    }

    @Test
    void differentConnectionNamesGetDifferentClients() {
        StubSecrets s = new StubSecrets();
        s.apiKey = "sk-xxx";
        connector.connect("api-1", baseConfig, LoginContext.platform(), s);
        connector.connect("api-2", baseConfig, LoginContext.platform(), s);
        assertEquals(2, connector.clientsForTest().size());
    }

    @Test
    void onConfigChangedClearsCachedClientForThatConnectionOnly() {
        StubSecrets s = new StubSecrets();
        s.apiKey = "sk-xxx";
        connector.connect("api-1", baseConfig, LoginContext.platform(), s);
        connector.connect("api-2", baseConfig, LoginContext.platform(), s);
        connector.onConfigChanged("api-1");
        assertEquals(1, connector.clientsForTest().size());
        assertFalse(connector.clientsForTest().containsKey("api-1"));
        assertTrue(connector.clientsForTest().containsKey("api-2"));
    }

    // ─── healthCheck fail path (no real server so it must DOWN, not throw) ─

    @Test
    void healthCheckWithMissingApiKeyReturnsDownNotThrows() {
        StubSecrets s = new StubSecrets(); // no apiKey → build fails
        HealthCheckResult result = connector.healthCheck("api", baseConfig, s);
        assertEquals(HealthCheckResult.Status.DOWN, result.status());
        assertTrue(result.message().contains("apiKey"));
    }

    @Test
    void healthCheckWithInvalidConfigReturnsDownNotThrows() {
        baseConfig.setBaseUrl(null);
        HealthCheckResult result = connector.healthCheck("api", baseConfig, new StubSecrets());
        assertEquals(HealthCheckResult.Status.DOWN, result.status());
    }
}
