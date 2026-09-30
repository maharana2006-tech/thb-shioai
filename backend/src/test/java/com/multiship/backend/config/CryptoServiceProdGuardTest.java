package com.multiship.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group A P0 A3 — prod profile must refuse to boot without
 * {@code SECRETS_ENCRYPTION_KEY}. Dev / test / no-profile boot must still
 * work (feature simply disabled).
 */
class CryptoServiceProdGuardTest {

    @Test
    void prodProfileWithoutKeyFailsPostConstruct() {
        MockEnvironment prod = new MockEnvironment().withProperty("dummy", "x");
        prod.setActiveProfiles("prod");

        CryptoService svc = new CryptoService("", prod);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                svc::assertAvailableInProd);
        assertTrue(ex.getMessage().contains("SECRETS_ENCRYPTION_KEY"),
                "error must name the env var so ops knows what to set");
        assertTrue(ex.getMessage().contains("prod"),
                "error must call out that this is a prod-profile guard");
    }

    @Test
    void devProfileWithoutKeyBootsFine() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        CryptoService svc = new CryptoService("", dev);
        assertDoesNotThrow(svc::assertAvailableInProd);
    }

    @Test
    void prodProfileWithKeyBootsFine() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        // 32 zero bytes, base64-encoded — valid AES-256 key length.
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        CryptoService svc = new CryptoService(key, prod);
        assertDoesNotThrow(svc::assertAvailableInProd);
    }
}
