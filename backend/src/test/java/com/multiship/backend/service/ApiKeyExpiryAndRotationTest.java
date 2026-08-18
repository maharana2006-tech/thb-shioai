package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import com.multiship.backend.service.ApiKeyService.AuthResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR C — locks in the expiry + rotation semantics.
 *
 * <p>The filter (ApiKeyAuthenticationFilter) branches on
 * {@link AuthResult.Kind} to write specific 401 bodies; getting the
 * classification wrong here would surface as a generic 401 without
 * {@code errorCode=API_KEY_EXPIRED} etc.
 */
class ApiKeyExpiryAndRotationTest {

    private ApiKeyRepository repo;
    private PasswordEncoder encoder;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        encoder = mock(PasswordEncoder.class);
        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pass-through.
        service = new ApiKeyService(repo, encoder,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    /* -------- token parsing / invalid -------- */

    @Test
    void nullTokenIsInvalid() {
        assertEquals(AuthResult.Kind.INVALID, service.authenticateDetailed(null).kind());
    }

    @Test
    void malformedTokenIsInvalid() {
        assertEquals(AuthResult.Kind.INVALID, service.authenticateDetailed("not-a-token").kind());
        assertEquals(AuthResult.Kind.INVALID, service.authenticateDetailed("msk_only_two").kind());
    }

    @Test
    void unknownPrefixIsInvalid() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(Optional.empty());
        assertEquals(AuthResult.Kind.INVALID,
                service.authenticateDetailed("msk_live_deadbeef_secretsecretsecret").kind());
    }

    @Test
    void secretMismatchIsInvalid() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(Optional.of(keyFixture(null, null)));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertEquals(AuthResult.Kind.INVALID,
                service.authenticateDetailed("msk_live_deadbeef_wrongsecret").kind());
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotIssueKeyForForeignTenant() {
        var authorities = java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            ApiKeyService scopedService = new ApiKeyService(repo, encoder,
                    new TenantScopeEnforcer(new AccessScopePolicy(true)));

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.issue("name", "OTHER", "live", null, "actor"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /* -------- happy path -------- */

    @Test
    void livePreExpiryKeyAuthorises() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(LocalDateTime.now().plusDays(30), null)));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthResult r = service.authenticateDetailed("msk_live_deadbeef_secret");

        assertEquals(AuthResult.Kind.AUTHORIZED, r.kind());
        assertFalse(r.deprecated());
        assertNull(r.sunsetAt());
    }

    @Test
    void nullExpiryKeyAuthorisesForever() {
        // Platform-only per policy: expiresAt=null means never expires.
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(null, null)));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthResult r = service.authenticateDetailed("msk_live_deadbeef_secret");
        assertEquals(AuthResult.Kind.AUTHORIZED, r.kind());
    }

    /* -------- expired -------- */

    @Test
    void expiredKeyReturnsExpired() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(LocalDateTime.now().minusHours(1), null)));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthResult r = service.authenticateDetailed("msk_live_deadbeef_secret");
        assertEquals(AuthResult.Kind.EXPIRED, r.kind());
        assertNotNull(r.key());
    }

    /* -------- rotation grace -------- */

    @Test
    void rotatedKeyInGraceWindowIsAuthorizedAndDeprecated() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().minusHours(1))));  // rotated 1h ago
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthResult r = service.authenticateDetailed("msk_live_deadbeef_secret");

        assertEquals(AuthResult.Kind.AUTHORIZED, r.kind());
        assertTrue(r.deprecated(), "grace-window request must set deprecated=true for RFC 8594 headers");
        assertNotNull(r.sunsetAt());
    }

    @Test
    void rotatedKeyPastGraceIsRotatedExpired() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().minusHours(25))));  // rotated >24h ago
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        AuthResult r = service.authenticateDetailed("msk_live_deadbeef_secret");
        assertEquals(AuthResult.Kind.ROTATED_EXPIRED, r.kind());
        // Filter has hard-revoked the key so future auths return INVALID cheaply.
        verify(repo).save(any(ApiKey.class));
    }

    /* -------- deprecated authenticate() delegates -------- */

    @Test
    void legacyAuthenticateReturnsEmptyForExpired() {
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(
                Optional.of(keyFixture(LocalDateTime.now().minusHours(1), null)));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        @SuppressWarnings("deprecation")
        Optional<ApiKey> legacy = service.authenticate("msk_live_deadbeef_secret");
        assertTrue(legacy.isEmpty(), "OAuthController should treat expired as usable=empty");
    }

    /* -------- rotate() -------- */

    @Test
    void rotateMintsChildWithParentIdSet() {
        ApiKey parent = keyFixture(LocalDateTime.now().plusDays(30), null);
        parent.setId(42L);
        when(repo.findById(42L)).thenReturn(Optional.of(parent));
        when(encoder.encode(anyString())).thenReturn("bcrypt");

        Optional<ApiKeyService.IssuedKey> result = service.rotate(42L, "admin");

        assertTrue(result.isPresent());
        assertEquals(42L, result.get().record().getRotatedFromId());
        assertNotNull(result.get().record().getExpiresAt(),
                "rotated child inherits the default 90-day expiry");
        assertNotNull(parent.getLastRotatedAt(),
                "parent stamped so subsequent auths compute the grace window");
    }

    @Test
    void rotateRevokedKeyReturnsEmpty() {
        ApiKey parent = keyFixture(null, null);
        parent.setActive(false);
        parent.setId(42L);
        when(repo.findById(42L)).thenReturn(Optional.of(parent));

        assertTrue(service.rotate(42L, "admin").isEmpty());
    }

    @Test
    void rotateUnknownKeyReturnsEmpty() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.rotate(99L, "admin").isEmpty());
    }

    /* -------- Audit R2 #341: lastUsedAt write coalesce -------- */

    @Test
    void authorizedAuth_persistsLastUsedOnlyOncePerWindow() {
        // Rapid succession of auths for the same key id → the pre-fix
        // path saved on every one; now the cache keeps the second call
        // from touching the DB while the write is still fresh.
        ApiKey k = keyFixture(LocalDateTime.now().plusDays(30), null);
        k.setId(777L);  // non-null id enables the coalesce cache
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(Optional.of(k));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        // First auth: must save. Second immediate auth: must NOT save.
        AuthResult first = service.authenticateDetailed("msk_live_deadbeef_secret");
        AuthResult second = service.authenticateDetailed("msk_live_deadbeef_secret");

        assertEquals(AuthResult.Kind.AUTHORIZED, first.kind());
        assertEquals(AuthResult.Kind.AUTHORIZED, second.kind());
        // Exactly one persistence — the coalesce blocks the second call.
        verify(repo, org.mockito.Mockito.times(1)).save(k);
    }

    @Test
    void authorizedAuth_nullIdSkipsCoalesceAndAlwaysSaves() {
        // Test fixtures without persisted id: fallback path always
        // writes (production keys are always persisted before this
        // code runs, so this path is only ever hit in tests).
        ApiKey k = keyFixture(LocalDateTime.now().plusDays(30), null);
        assertNull(k.getId(), "test fixture must start with null id");
        when(repo.findByKeyPrefixAndActiveTrue(anyString())).thenReturn(Optional.of(k));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        service.authenticateDetailed("msk_live_deadbeef_secret");
        service.authenticateDetailed("msk_live_deadbeef_secret");

        // Both auths persist — no coalesce because keyId is null.
        verify(repo, org.mockito.Mockito.times(2)).save(k);
    }

    /* -------- fixture -------- */

    private ApiKey keyFixture(LocalDateTime expiresAt, LocalDateTime lastRotatedAt) {
        return ApiKey.builder()
                .name("test")
                .clientCode("ACME")
                .environment("live")
                .keyPrefix("deadbeef")
                .keyHash("bcrypt-of-secret")
                .scopes("shipments rates")
                .active(true)
                .expiresAt(expiresAt)
                .lastRotatedAt(lastRotatedAt)
                .build();
    }
}
