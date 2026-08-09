package com.multiship.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 49 Tier 0 — fail-fast on JWT secret misconfig.
 *
 * <p>The old default {@code multishipSecretKeyForJWTTokenGeneration2026}
 * shipped in git and was publicly readable; anyone could forge admin
 * tokens. This guard rejects: empty secret, the compromised legacy value,
 * and any secret under 32 bytes (HS256 minimum).
 */
class JwtServiceTest {

    @Test
    void emptySecretRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtService.validateSecret(""));
        assertTrue(ex.getMessage().contains("JWT_SECRET"),
                "message should name the env var so ops knows what to set");
    }

    @Test
    void nullSecretRejected() {
        assertThrows(IllegalStateException.class,
                () -> JwtService.validateSecret(null));
    }

    @Test
    void compromisedLegacySecretRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtService.validateSecret(JwtService.COMPROMISED_DEFAULT));
        assertTrue(ex.getMessage().contains("rotate"),
                "message should tell ops to rotate, not just refuse");
    }

    @Test
    void shortSecretRejected() {
        // 20 bytes < 32 byte HS256 minimum
        assertThrows(IllegalStateException.class,
                () -> JwtService.validateSecret("too-short-for-hs256!"));
    }

    @Test
    void thirtyOneByteSecretRejected() {
        // Exactly one byte short of the minimum — boundary check.
        String s = "a".repeat(31);
        assertThrows(IllegalStateException.class, () -> JwtService.validateSecret(s));
    }

    @Test
    void thirtyTwoByteSecretAccepted() {
        // Exactly the minimum — allowed.
        String s = "a".repeat(32);
        assertDoesNotThrow(() -> JwtService.validateSecret(s));
    }

    @Test
    void longSecretAccepted() {
        String s = "a".repeat(64);
        assertDoesNotThrow(() -> JwtService.validateSecret(s));
    }
}
