package com.multiship.backend.config;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 50 Tier 0.5 PR A — locks in the JWT wire format so future changes
 * don't accidentally drop or rename the {@code clientCode} claim (which
 * every PR B-F service-layer check will depend on).
 */
class JwtClientCodeClaimTest {

    private JwtService newService() {
        return new JwtService("test-only-jwt-secret-do-not-use-in-production-32b", 86_400_000L);
    }

    @Test
    void tokenCarriesClientCodeWhenSupplied() {
        JwtService svc = newService();
        String token = svc.generateToken("alice", "USER", "ACME");
        Claims claims = svc.parseClaims(token);

        assertEquals("alice", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
        assertEquals("ACME", claims.get("clientCode", String.class));
    }

    @Test
    void clientCodeClaimAbsentWhenNull() {
        // Legacy internal ADMIN + USER: no clientCode. Filter's DB-fallback
        // handles the transition. The token itself must NOT carry an empty
        // string (would confuse "opted-out" vs "unset").
        JwtService svc = newService();
        String token = svc.generateToken("root", "ADMIN", null);
        Claims claims = svc.parseClaims(token);

        assertEquals("root", claims.getSubject());
        assertEquals("ADMIN", claims.get("role", String.class));
        assertNull(claims.get("clientCode", String.class));
        assertFalse(claims.containsKey("clientCode"),
                "absent claim must be missing from the map, not set to null / empty");
    }

    @Test
    void clientCodeClaimAbsentWhenBlank() {
        // Whitespace-only clientCode is treated as null (same rationale
        // as above: unset vs opted-out must stay distinguishable).
        JwtService svc = newService();
        String token = svc.generateToken("root", "ADMIN", "   ");
        Claims claims = svc.parseClaims(token);

        assertFalse(claims.containsKey("clientCode"));
    }

    @Test
    void deprecatedTwoArgOverloadStillWorks() {
        // Existing callers that pre-date PR A continue to compile and run;
        // they issue tokens without the clientCode claim (fallback path).
        JwtService svc = newService();
        @SuppressWarnings("deprecation")
        String token = svc.generateToken("legacy", "USER");
        Claims claims = svc.parseClaims(token);

        assertEquals("legacy", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
        assertFalse(claims.containsKey("clientCode"));
    }

    @Test
    void tokenIsRoundTrippable() {
        // Sanity: signature verifies, claims come back with correct types.
        JwtService svc = newService();
        String token = svc.generateToken("bob", "TENANT", "BOB");
        Claims c1 = svc.parseClaims(token);
        Claims c2 = svc.parseClaims(token);
        assertEquals(c1.getSubject(), c2.getSubject());
        assertEquals(c1.get("clientCode"), c2.get("clientCode"));
        // exp populated (rolled in claims map)
        assertTrue(c1.getExpiration().after(new java.util.Date()));
    }
}
