package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BUG-150 — only the ApiKeyScope vocabulary can be stored on a key. */
class ApiKeyServiceScopesTest {

    private ApiKeyRepository repo;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("bcrypt");
        service = new ApiKeyService(repo, encoder, new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @Test
    void normalizeScopes_acceptsKnownTokensInAnyCaseOrSeparator() {
        assertEquals("shipments rates landed-cost", ApiKeyService.normalizeScopes("Shipments, rates  landed-cost"));
    }

    @Test
    void normalizeScopes_rejectsUnknownToken() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ApiKeyService.normalizeScopes("shipments foo"));
        assertTrue(ex.getMessage().contains("'foo'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("shipments, rates, tracking, void, addresses, pickups, landed-cost"), ex.getMessage());
    }

    @Test
    void issue_refusesUnknownScopeBeforeSaving() {
        assertThrows(IllegalArgumentException.class,
                () -> service.issue("QA", "ACME", "live", "foo bar", "admin"));
        verify(repo, never()).save(any());
    }

    @Test
    void rotate_dropsLegacyUnknownScopesInsteadOfFailing() {
        ApiKey parent = ApiKey.builder().name("old").clientCode("ACME").environment("live")
                .keyPrefix("deadbeef").keyHash("h").scopes("foo shipments").active(true)
                .expiresAt(LocalDateTime.now().plusDays(30)).build();
        parent.setId(42L);
        when(repo.findById(42L)).thenReturn(Optional.of(parent));

        Optional<ApiKeyService.IssuedKey> rotated = service.rotate(42L, "admin");

        assertTrue(rotated.isPresent());
        assertEquals("shipments", rotated.get().record().getScopes());
    }
}
