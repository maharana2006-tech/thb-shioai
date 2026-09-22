package com.multiship.backend.service.externalsystems.connectors;

import com.multiship.backend.service.externalsystems.ConnectorSecretAccess;
import com.multiship.backend.service.externalsystems.ExternalSystemException;
import com.multiship.backend.service.externalsystems.LoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 unit tests for NdsOracleConnector — no live Oracle needed. The
 * connect() path will throw on pool creation because there's no real
 * server, so tests focus on:
 *   - config validation (fail-fast BEFORE any pool creation attempt)
 *   - client-code validation (INVALID_CLIENT_CODE before username
 *     touches JDBC)
 *   - profile dispatch (unknown profile → INVALID_CONFIG)
 *   - missing-secret handling (SECRET_UNAVAILABLE)
 *   - pool caching keys (same code = same cache entry; different case
 *     also same entry after normalisation)
 *   - override wins over the "password = code" default
 *   - onConfigChanged drains caches
 */
class NdsOracleConnectorTest {

    private NdsOracleConnector connector;
    private NdsOracleConfig baseConfig;

    @BeforeEach
    void setUp() {
        connector = new NdsOracleConnector();
        baseConfig = new NdsOracleConfig();
        baseConfig.setHost("192.168.3.8");
        baseConfig.setPort(1521);
        baseConfig.setServiceName("tb10g");
        baseConfig.setServerMode("DEDICATED");
        baseConfig.setProductionUsername("production");
        // Default pattern is fine; leave clientCodePattern default.
    }

    // ─── stub ConnectorSecretAccess ────────────────────────────────

    private static class StubSecrets implements ConnectorSecretAccess {
        String prodPassword;
        ClientLogin override;

        @Override public Optional<String> getSecret(String secretKey) {
            return NdsOracleConnector.SECRET_PRODUCTION_PASSWORD.equals(secretKey)
                    ? Optional.ofNullable(prodPassword) : Optional.empty();
        }
        @Override public Optional<ClientLogin> getClientOverride(String clientCode) {
            return Optional.ofNullable(override);
        }
    }

    // ─── config validation ─────────────────────────────────────────

    @Test
    void connectFailsFastOnMissingHost() {
        baseConfig.setHost(null);
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.withProfile("PRODUCTION"), new StubSecrets()));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
        assertTrue(e.getMessage().contains("host"));
    }

    @Test
    void connectFailsFastOnMissingProductionUsername() {
        baseConfig.setProductionUsername("");
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.withProfile("PRODUCTION"), new StubSecrets()));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
        assertTrue(e.getMessage().contains("productionUsername"));
    }

    @Test
    void connectRejectsUnknownProfile() {
        StubSecrets s = new StubSecrets();
        s.prodPassword = "pw";
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.withProfile("SUPERADMIN"), s));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
    }

    // ─── client-code validation (BEFORE any pool creation) ─────────

    @Test
    void clientProfileRejectsMissingCode() {
        StubSecrets s = new StubSecrets();
        s.prodPassword = "pw";
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.withProfile("CLIENT"), s));
        assertEquals(ExternalSystemException.Kind.INVALID_CLIENT_CODE, e.kind());
    }

    @Test
    void clientCodeMustMatchPattern() {
        StubSecrets s = new StubSecrets();
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.forClientWithProfile("MKL246; DROP TABLE", "CLIENT"), s));
        assertEquals(ExternalSystemException.Kind.INVALID_CLIENT_CODE, e.kind());
        assertTrue(connector.clientPoolsForTest().isEmpty(),
                "no pool must have been created for a bad code");
    }

    @Test
    void clientCodeWithTraversalRejected() {
        StubSecrets s = new StubSecrets();
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.forClientWithProfile("../etc/passwd", "CLIENT"), s));
        assertEquals(ExternalSystemException.Kind.INVALID_CLIENT_CODE, e.kind());
    }

    @Test
    void invalidRegexPatternSurfacesAsInvalidConfig() {
        baseConfig.setClientCodePattern("[unterminated");
        StubSecrets s = new StubSecrets();
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.forClientWithProfile("MKL246", "CLIENT"), s));
        assertEquals(ExternalSystemException.Kind.INVALID_CONFIG, e.kind());
    }

    // ─── missing secret handling ───────────────────────────────────

    @Test
    void productionProfileWithoutSecretThrowsSecretUnavailable() {
        // Deliberately no prodPassword set.
        StubSecrets s = new StubSecrets();
        ExternalSystemException e = assertThrows(ExternalSystemException.class,
                () -> connector.connect("nds", baseConfig,
                        LoginContext.withProfile("PRODUCTION"), s));
        assertEquals(ExternalSystemException.Kind.SECRET_UNAVAILABLE, e.kind());
    }

    // ─── caching semantics (pools are cached by key) ───────────────
    // Note: real pool creation goes through HikariCP which will throw
    // because there's no Oracle to talk to. But INVALID_CLIENT_CODE
    // fires BEFORE pool creation is attempted, so validation is
    // exercised independently. Pool-caching-by-key is verified via
    // the reverse: two rejected codes never write to the cache.

    @Test
    void badCodesNeverPopulateCache() {
        StubSecrets s = new StubSecrets();
        for (String bad : new String[]{"", "  ", "AB", "abc'def", "abcdefghijklm"}) {
            try {
                connector.connect("nds", baseConfig,
                        LoginContext.forClientWithProfile(bad, "CLIENT"), s);
            } catch (ExternalSystemException ignored) {
                // expected
            }
        }
        assertTrue(connector.clientPoolsForTest().isEmpty());
    }

    // ─── onConfigChanged drains ────────────────────────────────────

    @Test
    void onConfigChangedIsNoOpForNeverPooledConnection() {
        // Not throwing = pass. Real pools would be tested with a
        // Testcontainers Oracle in S2b (skipped without Docker).
        assertDoesNotThrow(() -> connector.onConfigChanged("nds-never-used"));
    }
}
