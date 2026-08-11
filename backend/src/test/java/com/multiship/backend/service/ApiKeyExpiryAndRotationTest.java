package com.multiship.backend.service;

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
        service = new ApiKeyService(repo, encoder);
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
